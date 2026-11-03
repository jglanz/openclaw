package ai.openclaw.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceSessionCommandTest {
  @Test
  fun parsesSessionCommandsWithTalkVoiceAndPunctuationVariants() {
    assertEquals(VoiceSessionCommand.Start, VoiceSessionCommandParser.parse("Please, start the talk session!"))
    assertEquals(VoiceSessionCommand.Start, VoiceSessionCommandParser.parse("BEGIN my voice session."))
    assertEquals(VoiceSessionCommand.Stop, VoiceSessionCommandParser.parse("stop session"))
    assertEquals(VoiceSessionCommand.Stop, VoiceSessionCommandParser.parse("end voice session"))
    assertEquals(VoiceSessionCommand.Pause, VoiceSessionCommandParser.parse("pause my talk session"))
    assertEquals(VoiceSessionCommand.Resume, VoiceSessionCommandParser.parse("continue session"))
    assertEquals(VoiceSessionCommand.Mute, VoiceSessionCommandParser.parse("mute the voice session"))
    assertEquals(VoiceSessionCommand.Unmute, VoiceSessionCommandParser.parse("unmute my talk session"))
    assertEquals(VoiceSessionCommand.Unmute, VoiceSessionCommandParser.parse("un-mute talk session"))
    assertEquals(VoiceSessionCommand.Unmute, VoiceSessionCommandParser.parse("un mute session"))
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