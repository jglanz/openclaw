package ai.openclaw.app.voice

internal object VoiceSessionCommandVocabulary {
  internal enum class Action {
    Start,
    Stop,
    Pause,
    Resume,
    Mute,
    Unmute,
    TurnOn,
    TurnOff,
  }

  internal enum class TokenType {
    Action,
    SessionTarget,
    AudioTarget,
    Voice,
    New,
    Filler,
  }

  internal data class Rule(
    val words: List<String>,
    val type: TokenType,
    val action: Action? = null,
  )

  val actionRules: List<Rule> =
    listOf(
      Action.Start to listOf("start", "begin", "open", "launch"),
      Action.TurnOn to listOf("turn on", "switch on"),
      Action.Start to listOf("fire up", "kick off"),
      Action.Stop to listOf("stop", "end", "finish", "close", "quit", "exit", "terminate"),
      Action.TurnOff to listOf("turn off", "switch off"),
      Action.Stop to listOf("hang up", "shut down", "shut off"),
      Action.Pause to listOf("pause", "hold", "suspend"),
      Action.Pause to listOf("hold on", "put on hold"),
      Action.Resume to listOf("resume", "continue", "unpause"),
      Action.Resume to listOf("un pause", "carry on"),
      Action.Mute to listOf("mute", "silence"),
      Action.Mute to listOf("put on mute"),
      Action.Unmute to listOf("unmute", "un mute"),
    ).flatMap { (action, phrases) ->
      phrases.map { phrase -> Rule(phrase.split(' '), TokenType.Action, action) }
    }

  val sessionTargetRules: List<Rule> =
    listOf(
      "session",
      "conversation",
      "chat",
      "call",
      "talk",
      "talk mode",
      "voice mode",
      "voice chat",
      "listening",
    ).map { phrase -> Rule(phrase.split(' '), TokenType.SessionTarget) }

  val audioTargetRules: List<Rule> =
    listOf("speaker", "sound", "audio", "volume", "yourself")
      .map { word -> Rule(listOf(word), TokenType.AudioTarget) }

  val fillerRules: List<Rule> =
    listOf(
      "please",
      "can",
      "could",
      "would",
      "will",
      "you",
      "just",
      "now",
      "ok",
      "okay",
      "so",
      "then",
      "right",
      "thanks",
      "kindly",
      "a",
      "an",
      "the",
      "my",
      "this",
      "that",
      "our",
      "your",
      "current",
      "it",
    ).map { word -> Rule(listOf(word), TokenType.Filler) } +
      listOf(
        "go ahead and",
        "let s",
        "let us",
        "i want",
        "i want to",
        "i d like",
        "i need to",
        "for me",
        "thank you",
      ).map { phrase -> Rule(phrase.split(' '), TokenType.Filler) }

  val newRule = Rule(listOf("new"), TokenType.New)
  val voiceRule = Rule(listOf("voice"), TokenType.Voice)

  val soundAlikes: Map<String, String> =
    mapOf(
      "paws" to "pause",
      "moot" to "mute",
      "sesh" to "session",
      "sesion" to "session",
      "cession" to "session",
      "sessions" to "session",
    )

  val fuzzyMatchWords: Set<String> =
    (actionRules + sessionTargetRules + audioTargetRules)
      .flatMap { it.words }
      .filter { it.length >= 5 }
      .toSet()

  val rules: List<Rule> =
    (actionRules + sessionTargetRules + audioTargetRules + fillerRules + newRule + voiceRule)
      .sortedByDescending { it.words.size }
}
