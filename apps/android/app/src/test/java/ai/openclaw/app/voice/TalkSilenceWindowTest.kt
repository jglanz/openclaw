package ai.openclaw.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TalkSilenceWindowTest {
  @Test
  fun defaultSilenceWindowIsDoubled() {
    assertEquals(1_400L, TalkDefaults.defaultSilenceTimeoutMs)
  }

  @Test
  fun voiceActivityExtendsSilenceWindowButTranscriptOnlyDoesNot() {
    assertEquals(1_000L, TalkSilenceWindow.effectiveLastHeardAtMs(1_000L, null, 1_400L))
    assertEquals(1_800L, TalkSilenceWindow.effectiveLastHeardAtMs(1_000L, 1_800L, 1_400L))
    assertFalse(TalkSilenceWindow.isSilenceElapsed(3_100L, 1_000L, 1_800L, 1_400L))
    assertTrue(TalkSilenceWindow.isSilenceElapsed(3_200L, 1_000L, 1_800L, 1_400L))
  }

  @Test
  fun noisyRmsActivityIsCappedAndThresholded() {
    assertEquals(2_400L, TalkSilenceWindow.effectiveLastHeardAtMs(1_000L, 5_000L, 1_400L))
    assertTrue(TalkSilenceWindow.isSilenceElapsed(3_800L, 1_000L, 5_000L, 1_400L))
    assertTrue(TalkSilenceWindow.isVoiceActivity(2f))
    assertFalse(TalkSilenceWindow.isVoiceActivity(1.9f))
    assertNull(TalkSilenceWindow.effectiveLastHeardAtMs(null, 5_000L, 1_400L))
  }
}
