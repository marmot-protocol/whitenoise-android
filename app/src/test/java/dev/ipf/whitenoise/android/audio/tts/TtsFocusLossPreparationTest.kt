package dev.ipf.whitenoise.android.audio.tts

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Losing audio focus while read-aloud prepares keeps it silent until the user asks again (#3708). */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TtsFocusLossPreparationTest {
    /** A transient loss during a first start's preparation cancels it, and it never reacquires focus. */
    @Test
    fun transientLossDuringFirstPreparationCancelsTheStart() =
        runTest {
            val harness = SessionHarness(this)

            val started =
                harness.controller.speakAsync(listOf(harness.entry("m1")), Locale.US) {
                    harness.focus.loseFocus()
                    true
                }
            advanceUntilIdle()

            assertFalse(started)
            assertTrue(harness.controller.state.value is TtsState.Idle)
            assertTrue(harness.spokenTexts().isEmpty())
            assertEquals("no second acquisition after the loss", 1, harness.focus.acquires)
        }

    /** A permanent loss or in-app surrender during a first start's preparation cancels it too. */
    @Test
    fun surrenderDuringFirstPreparationCancelsTheStart() =
        runTest {
            val harness = SessionHarness(this)

            val started =
                harness.controller.speakAsync(listOf(harness.entry("m1")), Locale.US) {
                    harness.focus.surrender()
                    true
                }
            advanceUntilIdle()

            assertFalse(started)
            assertTrue(harness.controller.state.value is TtsState.Idle)
            assertTrue(harness.spokenTexts().isEmpty())
            assertEquals(1, harness.focus.acquires)
        }

    /** A loss while a session is parked for a seek pauses it; the loaded target stays silent. */
    @Test
    fun lossWhileParkedForSeekPausesWithoutSpeaking() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m1")
            harness.pager.newerPages.addLast(listOf(harness.record("m2")))
            val gate = CompletableDeferred<Unit>()
            harness.pager.loadNewerGate = gate
            harness.speakConversation("m1")
            harness.session.requestSentenceSeek("m2", 2uL, 0, "")
            runCurrent()
            harness.engine.complete(0)
            assertTrue(harness.controller.state.value is TtsState.Preparing)
            val acquiresBeforeLoss = harness.focus.acquires

            harness.focus.loseFocus()
            gate.complete(Unit)
            advanceUntilIdle()

            assertTrue(harness.controller.state.value is TtsState.Paused)
            assertEquals(listOf("Nm1: Text m1."), harness.spokenTexts())
            assertEquals("the parked session does not reacquire focus", acquiresBeforeLoss, harness.focus.acquires)
        }

    /** Ordinary speech still pauses in place on a loss and resumes on an explicit request. */
    @Test
    fun speakingSessionStillPausesAndResumes() =
        runTest {
            val harness = SessionHarness(this)
            harness.speakConversation("m1")

            harness.focus.loseFocus()
            assertTrue(harness.controller.state.value is TtsState.Paused)

            harness.controller.resume()
            assertFalse(harness.controller.state.value is TtsState.Paused)
        }
}
