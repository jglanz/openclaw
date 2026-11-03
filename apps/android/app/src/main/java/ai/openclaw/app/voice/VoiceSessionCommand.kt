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
  fun parse(command: String): VoiceSessionCommand? {
    val words =
      command
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .split(Regex("\\s+"))
        .filter(String::isNotEmpty)
        .toMutableList()
    if (words.firstOrNull() == "please") words.removeAt(0)
    if (words.isEmpty()) return null

    val action =
      when {
        words.first() in setOf("start", "begin") -> VoiceSessionCommand.Start
        words.first() in setOf("stop", "end") -> VoiceSessionCommand.Stop
        words.first() == "pause" -> VoiceSessionCommand.Pause
        words.first() in setOf("resume", "continue") -> VoiceSessionCommand.Resume
        words.first() == "mute" -> VoiceSessionCommand.Mute
        words.first() == "unmute" || words.take(2) == listOf("un", "mute") -> VoiceSessionCommand.Unmute
        else -> return null
      }
    val actionWordCount =
      when {
        words.first() == "unmute" -> 1
        words.take(2) == listOf("un", "mute") -> 2
        else -> 1
      }
    val suffix = words.drop(actionWordCount).toMutableList()
    if (suffix.firstOrNull() in setOf("the", "my")) suffix.removeAt(0)
    if (suffix.firstOrNull() in setOf("talk", "voice")) suffix.removeAt(0)
    return action.takeIf { suffix == listOf("session") }
  }

  fun match(
    transcript: String,
    triggers: List<String>,
  ): VoiceSessionCommand? =
    VoiceWakePhraseMatcher.match(transcript, triggers)?.command?.let(::parse)
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