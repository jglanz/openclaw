package ai.openclaw.app.voice

/** Shared Talk-mode timing defaults used by capture, parser, and UI fallback paths. */
internal object TalkDefaults {
  const val defaultSilenceTimeoutMs = 1400L
}

internal object TalkSilenceWindow {
  private const val rmsVoiceActivityThresholdDb = 2f

  fun isVoiceActivity(rmsDb: Float): Boolean = rmsDb >= rmsVoiceActivityThresholdDb

  fun effectiveLastHeardAtMs(
    lastTranscriptAtMs: Long?,
    lastVoiceActivityAtMs: Long?,
    windowMs: Long,
  ): Long? {
    val transcriptAt = lastTranscriptAtMs ?: return null
    val voiceAt = lastVoiceActivityAtMs ?: return transcriptAt
    return maxOf(transcriptAt, minOf(voiceAt, transcriptAt + windowMs.coerceAtLeast(0L)))
  }

  fun isSilenceElapsed(
    nowMs: Long,
    lastTranscriptAtMs: Long?,
    lastVoiceActivityAtMs: Long?,
    windowMs: Long,
  ): Boolean {
    val lastHeardAt = effectiveLastHeardAtMs(lastTranscriptAtMs, lastVoiceActivityAtMs, windowMs) ?: return false
    return nowMs - lastHeardAt >= windowMs
  }
}
