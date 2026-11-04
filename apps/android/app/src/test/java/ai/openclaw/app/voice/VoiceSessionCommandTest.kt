package ai.openclaw.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSessionCommandTest {
  @Test
  fun parsesNaturalBareSessionCommands() {
    assertEquals(VoiceSessionCommand.Stop, VoiceSessionCommandParser.parse("could you end the conversation"))
    assertEquals(VoiceSessionCommand.Stop, VoiceSessionCommandParser.parse("let's stop the session"))
    assertEquals(VoiceSessionCommand.Stop, VoiceSessionCommandParser.parse("stop the session, Jarvis", ignoredWords = listOf("jarvis")))
    assertEquals(VoiceSessionCommand.Stop, VoiceSessionCommandParser.parse("stop voice session now"))
    assertEquals(VoiceSessionCommand.Stop, VoiceSessionCommandParser.parse("can you stop voice session"))
    assertEquals(VoiceSessionCommand.Stop, VoiceSessionCommandParser.parse("end the sesion"))
    assertEquals(VoiceSessionCommand.Pause, VoiceSessionCommandParser.parse("pause listening"))
    assertEquals(VoiceSessionCommand.Pause, VoiceSessionCommandParser.parse("paws the voice session"))
    assertEquals(VoiceSessionCommand.Mute, VoiceSessionCommandParser.parse("mute the speaker"))
    assertEquals(VoiceSessionCommand.Mute, VoiceSessionCommandParser.parse("please turn off the sound"))
    assertEquals(VoiceSessionCommand.Unmute, VoiceSessionCommandParser.parse("un-mute my talk session"))
    assertEquals(VoiceSessionCommand.Resume, VoiceSessionCommandParser.parse("could you carry on the conversation"))
  }

  @Test
  fun wakeWordAllowsActionWithoutTarget() {
    assertEquals(VoiceSessionCommand.Pause, VoiceSessionCommandParser.parse("pause", wakeWordUsed = true))
    assertEquals(VoiceSessionCommand.Stop, VoiceSessionCommandParser.parse("turn off", wakeWordUsed = true))
    assertEquals(VoiceSessionCommand.Start, VoiceSessionCommandParser.parse("start a new voice session", wakeWordUsed = true))
    assertEquals(VoiceSessionCommand.Resume, VoiceSessionCommandParser.parse("Hey Jarvis carry on", wakeWordUsed = true, ignoredWords = listOf("Jarvis")))
    assertEquals(VoiceSessionCommand.Mute, VoiceSessionCommandParser.parse("mute yourself", wakeWordUsed = true))
  }

  @Test
  fun rejectsUnknownWordsNegationsQuestionsAndMultipleActions() {
    val rejected =
      listOf(
        "stop",
        "stop it",
        "pause",
        "how do I stop the session",
        "don't stop the session",
        "did you end the call",
        "start a session about cooking",
        "stop and start the session",
        "start talking session",
        "could you stop the session and tell me the weather",
        "please can you stop voice session now okay so then right kindly thanks",
      )

    rejected.forEach { assertNull("Expected ordinary speech: $it", VoiceSessionCommandParser.parse(it)) }
  }

  @Test
  fun resolvesTurnOnAndOffFromAudioTargets() {
    assertEquals(VoiceSessionCommand.Mute, VoiceSessionCommandParser.parse("turn off the sound"))
    assertEquals(VoiceSessionCommand.Mute, VoiceSessionCommandParser.parse("switch off your speaker"))
    assertEquals(VoiceSessionCommand.Unmute, VoiceSessionCommandParser.parse("turn on the audio"))
    assertEquals(VoiceSessionCommand.Unmute, VoiceSessionCommandParser.parse("switch on speaker"))
    assertEquals(VoiceSessionCommand.Stop, VoiceSessionCommandParser.parse("turn off the conversation"))
    assertEquals(VoiceSessionCommand.Stop, VoiceSessionCommandParser.parse("turn off", wakeWordUsed = true))
    assertEquals(VoiceSessionCommand.Start, VoiceSessionCommandParser.parse("turn on", wakeWordUsed = true))
    assertNull(VoiceSessionCommandParser.parse("pause the speaker"))
    assertNull(VoiceSessionCommandParser.parse("start the audio"))
  }

  @Test
  fun acceptsActionAndTargetSynonymsAndMishearings() {
    assertEquals(VoiceSessionCommand.Start, VoiceSessionCommandParser.parse("please fire up a new talk session"))
    assertEquals(VoiceSessionCommand.Start, VoiceSessionCommandParser.parse("kick off the voice chat"))
    assertEquals(VoiceSessionCommand.Stop, VoiceSessionCommandParser.parse("hang up the call"))
    assertEquals(VoiceSessionCommand.Stop, VoiceSessionCommandParser.parse("shut down this talk mode"))
    assertEquals(VoiceSessionCommand.Pause, VoiceSessionCommandParser.parse("put on hold the conversation"))
    assertEquals(VoiceSessionCommand.Resume, VoiceSessionCommandParser.parse("un pause listening"))
    assertEquals(VoiceSessionCommand.Mute, VoiceSessionCommandParser.parse("put on mute the call"))
    assertEquals(VoiceSessionCommand.Unmute, VoiceSessionCommandParser.parse("unmute the speaker"))
    assertEquals(VoiceSessionCommand.Resume, VoiceSessionCommandParser.parse("resum voice session"))
    assertEquals(VoiceSessionCommand.Mute, VoiceSessionCommandParser.parse("moot speaker"))
  }

  @Test
  fun matchesConfiguredWakePrefixAndRejectsOtherPhrases() {
    val triggers = listOf("Jarvis")

    assertEquals(VoiceSessionCommand.Start, VoiceSessionCommandParser.match("Jarvis start session", triggers))
    assertEquals(VoiceSessionCommand.Start, VoiceSessionCommandParser.match("Jarvis, start talk session!", triggers))
    assertNull(VoiceSessionCommandParser.match("Jarvis start session about cooking", triggers))
    assertNull(VoiceSessionCommandParser.match("start session", triggers))
    assertNull(VoiceSessionCommandParser.parse("start talking session"))
  }

  @Test
  fun matchesWakePrefixedStartWithIndefiniteArticle() {
    val triggers = listOf("Jarvis")

    assertEquals(VoiceSessionCommand.Start, VoiceSessionCommandParser.match("Hey Jarvis start a voice session", triggers))
    assertEquals(VoiceSessionCommand.Start, VoiceSessionCommandParser.match("Hey Jarvis, start a voice session.", triggers))
    assertEquals(VoiceSessionCommand.Start, VoiceSessionCommandParser.match("Jarvis begin a talk session", triggers))
    assertEquals(VoiceSessionCommand.Start, VoiceSessionCommandParser.match("Jarvis start a session", triggers))
    assertEquals(VoiceSessionCommand.Start, VoiceSessionCommandParser.match("Hey Jarvis start a new voice session", triggers))
    assertEquals(VoiceSessionCommand.Start, VoiceSessionCommandParser.match("Hey Jarvis start a the voice session", triggers))
    assertEquals(VoiceSessionCommand.Start, VoiceSessionCommandParser.match("Jarvis please start new talk session", triggers))
    assertEquals(VoiceSessionCommand.Resume, VoiceSessionCommandParser.match("Hey Jarvis resume a voice session", triggers))

    assertNull(VoiceSessionCommandParser.match("Hey Jarvis start a voice session about cooking", triggers))
    assertNull(VoiceSessionCommandParser.match("Hey Jarvis how do I start a voice session", triggers))
    assertNull(VoiceSessionCommandParser.match("Hey Jarvis stop a new voice session", triggers))
    assertNull(VoiceSessionCommandParser.match("Hey Jarvis start a new voice", triggers))
    assertNull(VoiceSessionCommandParser.match("start a voice session", triggers))
  }

  @Test
  fun matchesDeviceWakePrefixedStartTranscripts() {
    val triggers = listOf("openclaw", "jarvis")

    assertEquals(VoiceSessionCommand.Start, VoiceSessionCommandParser.match("A Jarvis start my voice session", triggers))
    assertEquals(VoiceSessionCommand.Start, VoiceSessionCommandParser.match("Hey Jarvis start my voice session", triggers))
  }

  @Test
  fun biasingPhrasesIncludeWakeWordsAndCommandExamplesWithinLimit() {
    val triggers = listOf("jarvis", "openclaw", "computer")
    val phrases = VoiceSessionCommandParser.biasingPhrases(triggers)

    assertTrue(phrases.containsAll(triggers))
    assertTrue("jarvis stop voice session" in phrases)
    assertTrue("openclaw start voice session" in phrases)
    assertTrue(phrases.size <= 24)

    val manyTriggers = (1..VoiceWakePreferences.maxWords).map { "wake$it" }
    val cappedPhrases = VoiceSessionCommandParser.biasingPhrases(manyTriggers)
    assertTrue("wake1 stop voice session" in cappedPhrases)
    assertTrue("wake2 unmute voice session" in cappedPhrases)
    assertTrue(cappedPhrases.size <= 24)
  }

  @Test
  fun bareCommandsRequireAtLeastOneTarget() {
    assertEquals(VoiceSessionCommand.Stop, VoiceSessionCommandParser.parse("stop voice session"))
    assertEquals(VoiceSessionCommand.Stop, VoiceSessionCommandParser.parse("stop the session"))
    assertEquals(VoiceSessionCommand.Mute, VoiceSessionCommandParser.parse("please mute the voice session"))
    assertEquals(VoiceSessionCommand.Unmute, VoiceSessionCommandParser.parse("un-mute my talk session"))
    assertEquals(VoiceSessionCommand.Resume, VoiceSessionCommandParser.parse("resume talk session"))
    assertEquals(VoiceSessionCommand.Pause, VoiceSessionCommandParser.parse("Pause my talk session."))

    assertNull(VoiceSessionCommandParser.parse("stop"))
    assertNull(VoiceSessionCommandParser.parse("end the"))
    assertNull(VoiceSessionCommandParser.parse("stop the voice"))
    assertNull(VoiceSessionCommandParser.parse("start a new"))
  }

  @Test
  fun matchesInSessionCommandsWithOrWithoutWakePrefix() {
    val triggers = listOf("Jarvis")

    assertEquals(VoiceSessionCommand.Pause, VoiceSessionCommandParser.matchInSession("pause voice session", triggers))
    assertEquals(VoiceSessionCommand.Stop, VoiceSessionCommandParser.matchInSession("stop voice session", triggers))
    assertEquals(VoiceSessionCommand.Stop, VoiceSessionCommandParser.matchInSession("Jarvis stop session", triggers))
    assertEquals(VoiceSessionCommand.Resume, VoiceSessionCommandParser.matchInSession("Hey Jarvis resume voice session", triggers))
    assertEquals(VoiceSessionCommand.Stop, VoiceSessionCommandParser.matchInSession("stop a voice session", triggers))
    assertEquals(VoiceSessionCommand.Start, VoiceSessionCommandParser.matchInSession("Hey Jarvis start a voice session", triggers))

    assertEquals(VoiceSessionCommand.Start, VoiceSessionCommandParser.matchInSession("start session", triggers))
    assertNull(VoiceSessionCommandParser.matchInSession("start voice session about cooking", triggers))
    assertEquals(VoiceSessionCommand.Stop, VoiceSessionCommandParser.matchInSession("Jarvis stop voice session now", triggers))
    assertEquals(VoiceSessionCommand.Stop, VoiceSessionCommandParser.matchInSession("can you stop voice session", triggers))
  }

  @Test
  fun plansTransitionsForActiveAndPausedSessions() {
    assertEquals(VoiceSessionTransition.StartTalk, planVoiceSessionCommand(VoiceSessionCommand.Start, talkActive = false, paused = false))
    assertEquals(VoiceSessionTransition.None, planVoiceSessionCommand(VoiceSessionCommand.Start, talkActive = true, paused = false))
    assertEquals(VoiceSessionTransition.PauseTalk, planVoiceSessionCommand(VoiceSessionCommand.Pause, talkActive = true, paused = false))
    assertEquals(VoiceSessionTransition.ResumeTalk, planVoiceSessionCommand(VoiceSessionCommand.Resume, talkActive = false, paused = true))
    assertEquals(VoiceSessionTransition.None, planVoiceSessionCommand(VoiceSessionCommand.Resume, talkActive = false, paused = false))
    assertEquals(VoiceSessionTransition.StopTalk, planVoiceSessionCommand(VoiceSessionCommand.Stop, talkActive = false, paused = true))
    assertEquals(VoiceSessionTransition.MuteSpeaker, planVoiceSessionCommand(VoiceSessionCommand.Mute, talkActive = false, paused = false))
    assertEquals(VoiceSessionTransition.UnmuteSpeaker, planVoiceSessionCommand(VoiceSessionCommand.Unmute, talkActive = true, paused = false))
  }
}
