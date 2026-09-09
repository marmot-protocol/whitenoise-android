package dev.ipf.whitenoise.android.audio.tts

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class TtsAsyncPreparationTest {
    @Test
    fun preparingOwnsTheServiceBeforeTextWorkAndCommitsTheSameSession() =
        runTest {
            val harness = SessionHarness(this)
            var sessionId = 0L
            val result =
                harness.controller.speakAsync(listOf(harness.entry("m1")), Locale.US) {
                    val state = harness.controller.state.value
                    sessionId = state.sessionId
                    assertTrue(state is TtsState.Preparing)
                    assertEquals(TtsPlaybackSessionModel(true, false, false, true), TtsPlaybackSessionModel.from(state))
                    true
                }
            assertTrue(result)
            assertEquals(sessionId, harness.controller.state.value.sessionId)
            assertTrue(harness.controller.state.value is TtsState.Speaking)
        }

    @Test
    fun stopDuringPreparationCannotPublishAfterwards() =
        runTest {
            val harness = SessionHarness(this)
            val result =
                harness.controller.speakAsync(listOf(harness.entry("m1")), Locale.US) {
                    harness.controller.stop()
                    true
                }
            assertFalse(result)
            assertTrue(harness.controller.state.value is TtsState.Idle)
            assertTrue(harness.spokenTexts().isEmpty())
        }

    @Test
    fun rejectedForegroundOwnerCancelsPreparation() =
        runTest {
            val harness = SessionHarness(this)
            val result = harness.controller.speakAsync(listOf(harness.entry("m1")), Locale.US) { false }
            assertFalse(result)
            assertTrue(harness.controller.state.value is TtsState.Idle)
            assertTrue(harness.spokenTexts().isEmpty())
        }
}
