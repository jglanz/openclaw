package ai.openclaw.app.voice

import ai.openclaw.app.hasPermission
import ai.openclaw.app.i18n.NativeText
import ai.openclaw.app.i18n.nativeText
import ai.openclaw.app.i18n.resolveNativeText
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal sealed interface VoiceWakeRecognitionEvent {
  data object Ready : VoiceWakeRecognitionEvent

  data class Transcript(
    val text: String,
    val isFinal: Boolean,
    val endsSession: Boolean = true,
    val alternatives: List<String> = listOf(text),
  ) : VoiceWakeRecognitionEvent

  data class Error(
    val code: Int,
  ) : VoiceWakeRecognitionEvent
}

internal interface VoiceWakeRecognizer {
  val isAvailable: Boolean

  fun updateBiasingPhrases(phrases: List<String>) = Unit

  fun start(
    operationId: Long,
    onEvent: (VoiceWakeRecognitionEvent) -> Unit,
  )

  fun stop(operationId: Long)
}

internal class VoiceWakeRecognitionSession(
  private val onEvent: (VoiceWakeRecognitionEvent) -> Unit,
) {
  private val active = AtomicBoolean(true)

  fun emit(event: VoiceWakeRecognitionEvent) {
    if (active.get()) onEvent(event)
  }

  fun retire() {
    active.set(false)
  }
}

private class VoiceWakeAudioSource(
  val readDescriptor: ParcelFileDescriptor,
  private val writeStream: ParcelFileDescriptor.AutoCloseOutputStream,
  private val audioRecord: AudioRecord,
) {
  private var finishRequested = false
  private var inputFinished = false
  private var descriptorClosed = false
  var pumpJob: Job? = null

  @Synchronized
  fun requestFinish() {
    if (finishRequested) return
    finishRequested = true
    runCatching { audioRecord.stop() }
  }

  @Synchronized
  fun finishFromPump() {
    if (inputFinished) return
    inputFinished = true
    runCatching { audioRecord.stop() }
    runCatching { audioRecord.release() }
    runCatching { writeStream.close() }
  }

  @Synchronized
  fun close() {
    requestFinish()
    pumpJob?.cancel()
    finishFromPump()
    pumpJob = null
    if (!descriptorClosed) {
      descriptorClosed = true
      runCatching { readDescriptor.close() }
    }
  }
}

internal class AndroidOnDeviceVoiceWakeRecognizer(
  context: Context,
) : VoiceWakeRecognizer {
  private val appContext = context.applicationContext
  private val mainHandler = Handler(Looper.getMainLooper())
  private val pumpJob = SupervisorJob()
  private val pumpScope = CoroutineScope(Dispatchers.IO + pumpJob)
  override val isAvailable: Boolean =
    runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext) }.getOrDefault(false)

  @Volatile private var biasingPhrases: List<String> = emptyList()
  private val latestOperationId = AtomicLong(0)
  private val platformOwnerOperationId = AtomicLong(0)
  private var recognizer: SpeechRecognizer? = null
  private var recognitionSession: VoiceWakeRecognitionSession? = null
  private var audioSource: VoiceWakeAudioSource? = null

  override fun updateBiasingPhrases(phrases: List<String>) {
    biasingPhrases = phrases.toList()
  }

  override fun start(
    operationId: Long,
    onEvent: (VoiceWakeRecognitionEvent) -> Unit,
  ) {
    if (!claimOperation(operationId)) return
    platformOwnerOperationId.set(operationId)
    if (operationId != latestOperationId.get()) {
      platformOwnerOperationId.compareAndSet(operationId, 0)
      return
    }
    runOnMain {
      if (operationId != latestOperationId.get()) {
        platformOwnerOperationId.compareAndSet(operationId, 0)
        return@runOnMain
      }
      retireRecognizer()
      platformOwnerOperationId.set(operationId)
      if (!isAvailable) {
        platformOwnerOperationId.compareAndSet(operationId, 0)
        onEvent(VoiceWakeRecognitionEvent.Error(SpeechRecognizer.ERROR_SERVER_DISCONNECTED))
        return@runOnMain
      }
      val session = VoiceWakeRecognitionSession(onEvent)
      val source =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          runCatching { openAudioSource() }.getOrNull()
        } else {
          null
        }
      audioSource = source
      try {
        val active = createRecognizer(session)
        recognitionSession = session
        recognizer = active
        active.startListening(recognizerIntent(source))
      } catch (_: Throwable) {
        source?.close()
        audioSource = null
        session.retire()
        retireRecognizer()
        if (operationId == latestOperationId.get()) {
          onEvent(VoiceWakeRecognitionEvent.Error(SpeechRecognizer.ERROR_CLIENT))
        }
      }
    }
  }

  override fun stop(operationId: Long) {
    if (!claimOperation(operationId)) return
    if (platformOwnerOperationId.get() == 0L) return
    runOnMainSync {
      if (operationId == latestOperationId.get()) retireRecognizer()
    }
  }

  private fun claimOperation(operationId: Long): Boolean {
    while (true) {
      val current = latestOperationId.get()
      if (operationId <= current) return false
      if (latestOperationId.compareAndSet(current, operationId)) return true
    }
  }

  @SuppressLint("MissingPermission")
  private fun openAudioSource(): VoiceWakeAudioSource {
    check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
    val sampleRateHz = 16_000
    val minBufferSize =
      AudioRecord.getMinBufferSize(
        sampleRateHz,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
      )
    check(minBufferSize > 0) { "AudioRecord buffer unavailable" }

    val pipe = ParcelFileDescriptor.createPipe()
    var recorder: AudioRecord? = null
    var writeStream: ParcelFileDescriptor.AutoCloseOutputStream? = null
    try {
      recorder =
        AudioRecord(
          MediaRecorder.AudioSource.VOICE_RECOGNITION,
          sampleRateHz,
          AudioFormat.CHANNEL_IN_MONO,
          AudioFormat.ENCODING_PCM_16BIT,
          minBufferSize * 2,
        )
      check(recorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord initialization failed" }
      recorder.startRecording()
      check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "AudioRecord did not start" }
      val activeRecorder = recorder
      val activeWriteStream = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
      writeStream = activeWriteStream
      val source = VoiceWakeAudioSource(pipe[0], activeWriteStream, activeRecorder)
      source.pumpJob =
        pumpScope.launch(Dispatchers.IO) {
          val buffer = ByteArray(minBufferSize.coerceAtLeast(4_096))
          try {
            while (currentCoroutineContext().isActive) {
              val bytesRead = activeRecorder.read(buffer, 0, buffer.size)
              if (bytesRead <= 0) break
              activeWriteStream.write(buffer, 0, bytesRead)
            }
          } catch (_: IOException) {
            // pipe closed normally
          } finally {
            source.finishFromPump()
          }
        }
      return source
    } catch (err: Throwable) {
      runCatching { recorder?.stop() }
      runCatching { recorder?.release() }
      runCatching { writeStream?.close() }
      if (writeStream == null) runCatching { pipe[1].close() }
      runCatching { pipe[0].close() }
      throw err
    }
  }

  private fun createRecognizer(session: VoiceWakeRecognitionSession): SpeechRecognizer =
    SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext).also { active ->
      active.setRecognitionListener(
        object : SpeechRecognitionListenerAdapter() {
          override fun onReadyForSpeech(params: Bundle?) {
            session.emit(VoiceWakeRecognitionEvent.Ready)
          }

          override fun onResults(results: Bundle?) {
            val alternatives = transcripts(results)
            alternatives.firstOrNull()?.let {
              session.emit(VoiceWakeRecognitionEvent.Transcript(it, isFinal = true, endsSession = true, alternatives = alternatives))
            } ?: session.emit(VoiceWakeRecognitionEvent.Error(SpeechRecognizer.ERROR_NO_MATCH))
          }

          override fun onPartialResults(partialResults: Bundle?) {
            val alternatives = transcripts(partialResults)
            alternatives.firstOrNull()?.let {
              session.emit(VoiceWakeRecognitionEvent.Transcript(it, isFinal = false, endsSession = false, alternatives = alternatives))
            }
          }

          override fun onSegmentResults(segmentResults: Bundle) {
            val alternatives = transcripts(segmentResults)
            alternatives.firstOrNull()?.let {
              session.emit(VoiceWakeRecognitionEvent.Transcript(it, isFinal = true, endsSession = false, alternatives = alternatives))
            }
          }

          override fun onEndOfSegmentedSession() {
            session.emit(VoiceWakeRecognitionEvent.Error(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
          }

          override fun onError(error: Int) {
            session.emit(VoiceWakeRecognitionEvent.Error(error))
          }

          override fun onBeginningOfSpeech() = Unit

          override fun onEndOfSpeech() = Unit
        },
      )
    }

  private fun retireRecognizer() {
    // Retire callback ownership before cancel/destroy. Some recognizers emit a
    // late result or error after cancellation; it must not enter a new session.
    recognitionSession?.retire()
    recognitionSession = null
    platformOwnerOperationId.set(0)
    val source = audioSource
    audioSource = null
    source?.close()
    val active = recognizer
    recognizer = null
    runCatching { active?.cancel() }
    runCatching { active?.destroy() }
  }

  private fun runOnMain(action: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
  }

  private fun runOnMainSync(action: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
      action()
      return
    }
    val completed = CountDownLatch(1)
    val failure = AtomicReference<Throwable?>()
    check(
      mainHandler.post {
        try {
          action()
        } catch (err: Throwable) {
          failure.set(err)
        } finally {
          completed.countDown()
        }
      },
    ) { "main looper unavailable" }
    completed.await()
    failure.get()?.let { throw it }
  }

  private fun recognizerIntent(audioSource: VoiceWakeAudioSource?): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
      putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
      putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
      putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
      putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
      putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && biasingPhrases.isNotEmpty()) {
        putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(biasingPhrases))
      }
      if (audioSource != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, audioSource.readDescriptor)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
        putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
      }
    }

  @Suppress("DEPRECATION")
  private fun transcripts(bundle: Bundle?): List<String> {
    if (bundle == null) return emptyList()
    val list: List<String> =
      when (val raw = bundle.get(SpeechRecognizer.RESULTS_RECOGNITION)) {
        is ArrayList<*> -> raw.mapNotNull { it?.toString() }
        is Array<*> -> raw.mapNotNull { it?.toString() }
        is List<*> -> raw.mapNotNull { it?.toString() }
        is CharSequence -> listOf(raw.toString())
        else -> bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
      }
    return list
      .asSequence()
      .map(String::trim)
      .filter(String::isNotEmpty)
      .distinct()
      .take(3)
      .toList()
  }
}

internal enum class VoiceWakeSuppressionReason {
  Camera,
  Dictation,
  GatewaySync,
  VoiceCapture,
  VoiceNote,
  VoiceReplySpeech,
  MessageSpeech,
}

internal class VoiceWakeManager(
  context: Context,
  private val scope: CoroutineScope,
  private val recognizer: VoiceWakeRecognizer,
  initialTriggerWords: List<String>,
  private val onCommand: suspend (VoiceWakeMatch) -> Boolean,
  private val restartDelayMs: Long = 350L,
  private val hasRecordAudioPermission: () -> Boolean = {
    context.hasPermission(Manifest.permission.RECORD_AUDIO)
  },
) {
  private sealed interface RecognizerAction {
    val operationId: Long

    data class Start(
      override val operationId: Long,
      val sessionGeneration: Long,
    ) : RecognizerAction

    data class Stop(
      override val operationId: Long,
    ) : RecognizerAction
  }

  private val lock = Any()
  private var enabled = false
  private var foreground = false
  private var sessionGeneration = 0L
  private var sessionActive = false
  private var commandInFlight = false
  private var commandJob: Job? = null
  private var restartJob: Job? = null
  private var recognizerOperationId = 0L
  private val suppressionReasons = mutableSetOf<VoiceWakeSuppressionReason>()
  private val suppressionRevisions = mutableMapOf<VoiceWakeSuppressionReason, Long>()
  private var triggerWords = VoiceWakePreferences.sanitizeTriggerWords(initialTriggerWords)

  private val _isListening = MutableStateFlow(false)
  val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

  private val _statusText = MutableStateFlow<NativeText>(nativeText("Off"))
  val statusText: StateFlow<String> = _statusText.resolveNativeText()

  private val _lastTriggeredCommand = MutableStateFlow<String?>(null)
  val lastTriggeredCommand: StateFlow<String?> = _lastTriggeredCommand.asStateFlow()

  val isAvailable: Boolean
    get() = recognizer.isAvailable

  init {
    recognizer.updateBiasingPhrases(VoiceSessionCommandParser.biasingPhrases(triggerWords))
  }

  fun setEnabled(value: Boolean) =
    reconcile {
      enabled = value
    }

  fun setForeground(value: Boolean) =
    reconcile {
      foreground = value
    }

  fun setSuppressed(
    reason: VoiceWakeSuppressionReason,
    suppressed: Boolean,
    revision: Long? = null,
  ) {
    reconcile {
      if (revision != null) {
        val currentRevision = suppressionRevisions[reason] ?: 0L
        if (revision <= currentRevision) return
        suppressionRevisions[reason] = revision
      }
      if (suppressed) suppressionReasons += reason else suppressionReasons -= reason
    }
  }

  fun updateTriggerWords(words: List<String>) {
    val sanitized = VoiceWakePreferences.sanitizeTriggerWords(words)
    synchronized(lock) {
      triggerWords = sanitized
      recognizer.updateBiasingPhrases(VoiceSessionCommandParser.biasingPhrases(sanitized))
    }
  }

  fun refreshPermission() = reconcile {}

  private inline fun reconcile(update: () -> Unit) {
    val action =
      synchronized(lock) {
        update()
        reconcileLocked()
      }
    performRecognizerAction(action)
  }

  fun shutdown() {
    val action =
      synchronized(lock) {
        enabled = false
        foreground = false
        val pendingAction = stopSessionLocked(force = true)
        _statusText.value = nativeText("Off")
        pendingAction
      }
    performRecognizerAction(action)
  }

  private fun reconcileLocked(): RecognizerAction? {
    val blockedStatus = blockedStatusLocked()
    if (blockedStatus != null) {
      val action = stopSessionLocked(force = !enabled || !foreground)
      _statusText.value = blockedStatus
      return action
    }
    if (!sessionActive && !commandInFlight && restartJob?.isActive != true) {
      return startSessionLocked()
    }
    return null
  }

  private fun blockedStatusLocked(): NativeText? =
    when {
      !enabled -> nativeText("Off")
      !recognizer.isAvailable -> nativeText("On-device speech recognition unavailable")
      !hasRecordAudioPermission() -> nativeText("Microphone permission required")
      !foreground || suppressionReasons.isNotEmpty() -> nativeText("Paused")
      else -> null
    }

  private fun startSessionLocked(): RecognizerAction {
    sessionGeneration += 1
    val generation = sessionGeneration
    sessionActive = true
    _isListening.value = false
    _statusText.value = nativeText("Starting…")
    return RecognizerAction.Start(++recognizerOperationId, generation)
  }

  private fun handleRecognitionEvent(
    generation: Long,
    event: VoiceWakeRecognitionEvent,
  ) {
    val action =
      synchronized(lock) {
        if (generation != sessionGeneration || !sessionActive) return
        when (event) {
          VoiceWakeRecognitionEvent.Ready -> {
            _isListening.value = true
            _statusText.value = nativeText("Listening")
            null
          }

          is VoiceWakeRecognitionEvent.Transcript -> {
            // Android partials routinely stop mid-command. Only a final transcript
            // has the recognizer's end-of-utterance boundary and is safe to dispatch.
            if (!event.isFinal) return
            val transcriptAction = handleTranscriptLocked(event.alternatives.ifEmpty { listOf(event.text) })
            if (transcriptAction == null && event.endsSession) {
              sessionActive = false
              _isListening.value = false
              scheduleRestartLocked()
            }
            transcriptAction
          }

          is VoiceWakeRecognitionEvent.Error -> {
            sessionActive = false
            _isListening.value = false
            if (event.code == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
              _statusText.value = nativeText("Microphone permission required")
            } else if (event.code == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) {
              _statusText.value = nativeText("Device language not supported")
            } else if (event.code == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
              _statusText.value = nativeText("On-device language model unavailable")
            } else {
              scheduleRestartLocked(delayMs = retryDelayMs(event.code))
            }
            null
          }
        }
      }
    performRecognizerAction(action)
  }

  private fun handleTranscriptLocked(transcripts: List<String>): RecognizerAction? {
    if (commandInFlight) return null
    val matches = transcripts.mapNotNull { transcript -> VoiceWakePhraseMatcher.match(transcript, triggerWords) }
    val triggerMatch = matches.firstOrNull() ?: return null
    val match = triggerMatch.copy(alternativeCommands = matches.drop(1).map { it.command })
    sessionGeneration += 1
    val commandGeneration = sessionGeneration
    sessionActive = false
    commandInFlight = true
    _isListening.value = false
    _lastTriggeredCommand.value = match.command
    _statusText.value = nativeText("Triggered")
    val action = RecognizerAction.Stop(++recognizerOperationId)
    commandJob =
      scope.launch {
        var delivered = false
        try {
          delivered = onCommand(match)
        } finally {
          synchronized(lock) {
            if (commandGeneration == sessionGeneration) {
              commandJob = null
              commandInFlight = false
              scheduleRestartLocked()
              if (!delivered) _statusText.value = nativeText("Gateway unavailable")
            }
          }
        }
      }
    return action
  }

  private fun scheduleRestartLocked(delayMs: Long = restartDelayMs) {
    restartJob?.cancel()
    val blockedStatus = blockedStatusLocked()
    if (blockedStatus != null) {
      _statusText.value = blockedStatus
      return
    }
    _statusText.value = nativeText("Starting…")
    restartJob =
      scope.launch {
        delay(delayMs)
        val action =
          synchronized(lock) {
            restartJob = null
            reconcileLocked()
          }
        performRecognizerAction(action)
      }
  }

  private fun retryDelayMs(errorCode: Int): Long =
    when (errorCode) {
      SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> 15_000L

      SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
      SpeechRecognizer.ERROR_SERVER,
      SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
      SpeechRecognizer.ERROR_AUDIO,
      SpeechRecognizer.ERROR_CLIENT,
      -> 1_500L

      else -> restartDelayMs
    }

  private fun stopSessionLocked(force: Boolean): RecognizerAction? {
    restartJob?.cancel()
    restartJob = null
    commandJob?.cancel()
    commandJob = null
    commandInFlight = false
    sessionGeneration += 1
    val wasActive = sessionActive
    sessionActive = false
    _isListening.value = false
    return if (force || wasActive) RecognizerAction.Stop(++recognizerOperationId) else null
  }

  private fun performRecognizerAction(action: RecognizerAction?) {
    when (action) {
      is RecognizerAction.Start -> {
        recognizer.start(action.operationId) { event ->
          handleRecognitionEvent(action.sessionGeneration, event)
        }
      }

      is RecognizerAction.Stop -> {
        recognizer.stop(action.operationId)
      }

      null -> {}
    }
  }
}

internal class PreviewVoiceWakeRecognizer : VoiceWakeRecognizer {
  override val isAvailable: Boolean = true

  override fun start(
    operationId: Long,
    onEvent: (VoiceWakeRecognitionEvent) -> Unit,
  ) {
    onEvent(VoiceWakeRecognitionEvent.Ready)
  }

  override fun stop(operationId: Long) = Unit
}
