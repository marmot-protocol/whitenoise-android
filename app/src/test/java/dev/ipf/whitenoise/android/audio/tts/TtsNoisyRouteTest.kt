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

/** Unplugging headphones silences read-aloud in every state that can still produce speech. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TtsNoisyRouteTest {
    @Test
    fun disconnectPausesSpeechAndClosesTheListener() =
        runTest {
            val harness = SessionHarness(this)
            harness.speakConversation("m1")
            assertEquals(1, harness.noisyRoutes.listeners)

            harness.noisyRoutes.disconnect()

            assertTrue(harness.controller.state.value is TtsState.Paused)
            assertEquals(0, harness.noisyRoutes.listeners)
            assertEquals(harness.focus.acquires, harness.focus.releases)
        }

    @Test
    fun disconnectDuringFirstPreparationCancelsTheStart() =
        runTest {
            val harness = SessionHarness(this)

            val started =
                harness.controller.speakAsync(listOf(harness.entry("m1")), Locale.US) {
                    harness.noisyRoutes.disconnect()
                    true
                }

            assertFalse(started)
            assertTrue(harness.controller.state.value is TtsState.Idle)
            assertTrue(harness.spokenTexts().isEmpty())
            assertEquals(0, harness.noisyRoutes.listeners)
        }

    @Test
    fun disconnectWhileParkedForSeekKeepsTheLoadedTargetSilent() =
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

            harness.noisyRoutes.disconnect()
            gate.complete(Unit)
            advanceUntilIdle()

            assertTrue(harness.controller.state.value is TtsState.Paused)
            assertEquals(listOf("Nm1: Text m1."), harness.spokenTexts())
            assertEquals(0, harness.noisyRoutes.listeners)
        }

    @Test
    fun disconnectDuringLoadingSeekMovesTheCursorWithoutResuming() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m1")
            harness.pager.newerPages.addLast(listOf(harness.record("m2")))
            val gate = CompletableDeferred<Unit>()
            harness.pager.loadNewerGate = gate
            harness.speakConversation("m1")
            harness.session.requestSentenceSeek("m2", 2uL, 0, "")
            runCurrent()

            harness.noisyRoutes.disconnect()
            gate.complete(Unit)
            advanceUntilIdle()

            assertTrue(harness.controller.state.value is TtsState.Paused)
            assertEquals(1, harness.spokenTexts().size)
            assertEquals(0, harness.noisyRoutes.listeners)
            assertEquals(harness.focus.acquires, harness.focus.releases)
        }

    @Test
    fun laterTapToJumpStillResumesAfterADisconnect() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m1")
            harness.pager.newerPages.addLast(listOf(harness.record("m2")))
            harness.speakConversation("m1")
            harness.noisyRoutes.disconnect()

            assertTrue(harness.session.requestSentenceSeek("m2", 2uL, 0, ""))
            advanceUntilIdle()

            assertTrue(harness.controller.state.value is TtsState.Speaking)
            assertEquals("Nm2: Text m2.", harness.spokenTexts().last())
            assertEquals(1, harness.noisyRoutes.listeners)
        }

    @Test
    fun listenerClosesWhenSpeechEnds() =
        runTest {
            val completed = SessionHarness(this)
            completed.speakConversation("m1")
            completed.engine.complete(0)
            assertTrue(completed.controller.state.value is TtsState.Idle)
            assertEquals(0, completed.noisyRoutes.listeners)

            val stopped = SessionHarness(this)
            stopped.speakConversation("m1")
            stopped.controller.stop()
            assertEquals(0, stopped.noisyRoutes.listeners)
        }
}
