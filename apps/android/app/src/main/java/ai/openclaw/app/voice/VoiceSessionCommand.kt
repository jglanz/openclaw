package ai.openclaw.app.voice

import java.util.Locale

internal enum class VoiceSessionCommand {
  Start,
  Stop,
  Pause,
  Resume,
  Mute,
  Unmute,
}

internal enum class VoiceSessionTransition {
  StartTalk,
  ResumeTalk,
  StopTalk,
  PauseTalk,
  MuteSpeaker,
  UnmuteSpeaker,
  None,
}

internal object VoiceSessionCommandParser {
  fun parse(
    utterance: String,
    wakeWordUsed: Boolean = false,
    ignoredWords: List<String> = emptyList(),
  ): VoiceSessionCommand? {
    val ignoredTokens = ignoredWords.flatMap(::tokenize).toSet()
    val words = tokenize(utterance).filterNot { it in ignoredTokens }
    if (words.isEmpty() || words.size > maxWords) return null

    val normalizedWords = words.map { word -> VoiceSessionCommandVocabulary.soundAlikes[word] ?: fuzzyMatch(word) }
    val classified = classify(normalizedWords, wakeWordUsed) ?: return null
    val sessionTargetPositions =
      classified.indices.filter { index -> classified[index].rule.type == VoiceSessionCommandVocabulary.TokenType.SessionTarget }
    val hasSessionTarget = sessionTargetPositions.isNotEmpty()
    val hasAudioTarget =
      classified.any { it.rule.type == VoiceSessionCommandVocabulary.TokenType.AudioTarget } ||
        classified.indices.any { index ->
          classified[index].rule.type == VoiceSessionCommandVocabulary.TokenType.Voice &&
            sessionTargetPositions.none { targetIndex -> targetIndex > index }
        }
    val resolvedActions =
      classified
        .mapNotNull { token ->
          val action = token.rule.action ?: return@mapNotNull null
          when (action) {
            VoiceSessionCommandVocabulary.Action.TurnOn -> {
              if (hasAudioTarget) VoiceSessionCommand.Unmute else VoiceSessionCommand.Start
            }

            VoiceSessionCommandVocabulary.Action.TurnOff -> {
              if (hasAudioTarget) VoiceSessionCommand.Mute else VoiceSessionCommand.Stop
            }

            else -> {
              action.toCommand()
            }
          }
        }.toSet()
    if (resolvedActions.size != 1) return null

    val command = resolvedActions.single()
    if (classified.any { it.rule.type == VoiceSessionCommandVocabulary.TokenType.New } && command != VoiceSessionCommand.Start) return null
    if (hasAudioTarget && command !in setOf(VoiceSessionCommand.Mute, VoiceSessionCommand.Unmute)) return null
    if (!wakeWordUsed && !hasSessionTarget && !hasAudioTarget) return null
    return command
  }

  fun match(
    transcript: String,
    triggers: List<String>,
  ): VoiceSessionCommand? =
    VoiceWakePhraseMatcher.match(transcript, triggers)?.command?.let {
      parse(it, wakeWordUsed = true, ignoredWords = triggers)
    }

  // Used while Talk is active: wake word optional; the bare form requires a target.
  fun matchInSession(
    transcript: String,
    triggers: List<String>,
  ): VoiceSessionCommand? = match(transcript, triggers) ?: parse(transcript, ignoredWords = triggers)

  fun biasingPhrases(triggers: List<String>): List<String> {
    val wakeWords = VoiceWakePreferences.sanitizeTriggerWords(triggers).distinctBy { it.lowercase(Locale.ROOT) }
    val commandPhrases =
      wakeWords.take(2).flatMap { wakeWord ->
        VoiceSessionCommand.values().map { command -> "$wakeWord ${command.name.lowercase(Locale.ROOT)} voice session" }
      }
    val wakeWordsToInclude = wakeWords.take((maxBiasingPhrases - commandPhrases.size).coerceAtLeast(0))
    return (wakeWordsToInclude + commandPhrases).distinctBy { it.lowercase(Locale.ROOT) }.take(maxBiasingPhrases)
  }

  private data class ClassifiedToken(
    val rule: VoiceSessionCommandVocabulary.Rule,
  )

  private fun classify(
    words: List<String>,
    wakeWordUsed: Boolean,
  ): List<ClassifiedToken>? {
    val classified = mutableListOf<ClassifiedToken>()
    var index = 0
    while (index < words.size) {
      val remaining = words.subList(index, words.size)
      val rule = VoiceSessionCommandVocabulary.rules.firstOrNull { candidate -> remaining.startsWith(candidate.words) }
      val resolvedRule =
        rule ?: if (wakeWordUsed && words[index] == "hey") {
          VoiceSessionCommandVocabulary.Rule(listOf("hey"), VoiceSessionCommandVocabulary.TokenType.Filler)
        } else {
          return null
        }
      classified += ClassifiedToken(resolvedRule)
      index += resolvedRule.words.size
    }
    return classified
  }

  private fun fuzzyMatch(word: String): String {
    if (word.length < 5 || word in VoiceSessionCommandVocabulary.fuzzyMatchWords) return word
    val matches = VoiceSessionCommandVocabulary.fuzzyMatchWords.filter { candidate -> differsByOneLetter(word, candidate) }
    return matches.singleOrNull() ?: word
  }

  private fun differsByOneLetter(
    first: String,
    second: String,
  ): Boolean {
    if (kotlin.math.abs(first.length - second.length) > 1) return false
    var firstIndex = 0
    var secondIndex = 0
    var differences = 0
    while (firstIndex < first.length && secondIndex < second.length) {
      if (first[firstIndex] == second[secondIndex]) {
        firstIndex++
        secondIndex++
      } else {
        differences++
        if (differences > 1) return false
        when {
          first.length > second.length -> {
            firstIndex++
          }

          second.length > first.length -> {
            secondIndex++
          }

          else -> {
            firstIndex++
            secondIndex++
          }
        }
      }
    }
    if (firstIndex < first.length || secondIndex < second.length) differences++
    return differences == 1
  }

  private fun tokenize(text: String): List<String> =
    text
      .lowercase(Locale.ROOT)
      .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
      .trim()
      .split(Regex("\\s+"))
      .filter(String::isNotEmpty)

  private fun List<String>.startsWith(prefix: List<String>): Boolean = size >= prefix.size && subList(0, prefix.size) == prefix

  private fun VoiceSessionCommandVocabulary.Action.toCommand(): VoiceSessionCommand? =
    when (this) {
      VoiceSessionCommandVocabulary.Action.Start -> VoiceSessionCommand.Start

      VoiceSessionCommandVocabulary.Action.Stop -> VoiceSessionCommand.Stop

      VoiceSessionCommandVocabulary.Action.Pause -> VoiceSessionCommand.Pause

      VoiceSessionCommandVocabulary.Action.Resume -> VoiceSessionCommand.Resume

      VoiceSessionCommandVocabulary.Action.Mute -> VoiceSessionCommand.Mute

      VoiceSessionCommandVocabulary.Action.Unmute -> VoiceSessionCommand.Unmute

      VoiceSessionCommandVocabulary.Action.TurnOn,
      VoiceSessionCommandVocabulary.Action.TurnOff,
      -> null
    }

  private const val maxWords = 12
  private const val maxBiasingPhrases = 24
}

internal fun planVoiceSessionCommand(
  command: VoiceSessionCommand,
  talkActive: Boolean,
  paused: Boolean,
): VoiceSessionTransition =
  when (command) {
    VoiceSessionCommand.Start -> if (talkActive) VoiceSessionTransition.None else VoiceSessionTransition.StartTalk
    VoiceSessionCommand.Stop -> if (talkActive || paused) VoiceSessionTransition.StopTalk else VoiceSessionTransition.None
    VoiceSessionCommand.Pause -> if (talkActive) VoiceSessionTransition.PauseTalk else VoiceSessionTransition.None
    VoiceSessionCommand.Resume -> if (paused) VoiceSessionTransition.ResumeTalk else VoiceSessionTransition.None
    VoiceSessionCommand.Mute -> VoiceSessionTransition.MuteSpeaker
    VoiceSessionCommand.Unmute -> VoiceSessionTransition.UnmuteSpeaker
  }
