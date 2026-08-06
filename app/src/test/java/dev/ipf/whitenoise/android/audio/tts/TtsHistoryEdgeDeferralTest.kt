package dev.ipf.whitenoise.android.audio.tts

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * A read-aloud session whose final chunk finishes while a history page is still
 * in flight: the queue parks that terminal instead of ending, and each way the
 * request can settle resolves it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TtsHistoryEdgeDeferralTest {
    @Test
    fun aFinalUtteranceFinishingDuringANewerPageLoadPlaysTheLoadedTarget() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m1")
            harness.pager.newerPages.addLast(listOf(harness.record("m2")))
            harness.speakConversation("m1")
            val gate = CompletableDeferred<Unit>()
            harness.pager.loadNewerGate = gate

            harness.session.nextMessage()
            // The job is suspended INSIDE loadNewer, so the queue genuinely
            // runs out of chunks while the page is still in flight.
            runCurrent()
            harness.engine.complete(0)
            assertTrue(harness.controller.state.value is TtsState.Speaking)
            assertEquals(0, harness.focus.releases)

            gate.complete(Unit)
            advanceUntilIdle()

            assertNull(harness.session.edgeState.value)
            assertEquals(listOf("m1", "m2"), harness.controller.queuedMessageIds())
            val state = harness.controller.state.value as TtsState.Speaking
            assertEquals(1, state.messageIndex)
            assertEquals(
                "Nm2: Text m2.",
                harness.engine.spoken
                    .last()
                    .text,
            )
            assertEquals(0, harness.focus.releases)
        }

    @Test
    fun aParkedTerminalCompletesNaturallyWhenTheEdgeReachesTheEndOfHistory() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m1")
            // The page arrives but holds nothing speakable, so the walk ends on
            // a genuine end of history.
            harness.pager.newerPages.addLast(listOf(harness.unspeakableRecord("u1")))
            harness.speakConversation("m1")
            val gate = CompletableDeferred<Unit>()
            harness.pager.loadNewerGate = gate

            harness.session.nextMessage()
            runCurrent()
            harness.engine.complete(0)
            assertTrue(harness.controller.state.value is TtsState.Speaking)
            gate.complete(Unit)
            advanceUntilIdle()

            assertNull(harness.session.edgeState.value)
            // The request survived the terminal chunk and reached its verdict.
            assertTrue(harness.pager.newerPages.isEmpty())
            val state = harness.controller.state.value as TtsState.Idle
            assertEquals(1, state.messageIndex)
            assertEquals(1, state.messageCount)
            assertEquals(1, state.chunkCount)
            // Natural completion, not a replay: focus drops exactly once and no
            // chunk is spoken twice.
            assertEquals(1, harness.focus.releases)
            assertEquals(1, harness.engine.spoken.size)
            assertTrue(harness.session.allowsLiveAppend())
        }

    @Test
    fun aParkedTerminalAtTheOlderEdgeRestartsTheFirstMessageWhenHistoryRunsOut() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m2")
            harness.pager.olderPages.addLast(listOf(harness.unspeakableRecord("u1")))
            harness.speakConversation("m2")
            val gate = CompletableDeferred<Unit>()
            harness.pager.loadOlderGate = gate

            harness.session.previousMessage()
            runCurrent()
            harness.engine.complete(0)
            gate.complete(Unit)
            advanceUntilIdle()

            // The older edge keeps its own pre-paging semantics: the tap
            // restarts the first message rather than ending the session.
            assertNull(harness.session.edgeState.value)
            val state = harness.controller.state.value as TtsState.Speaking
            assertEquals(0, state.chunkIndex)
            assertEquals(2, harness.engine.spoken.size)
            assertEquals(0, harness.focus.releases)
        }

    @Test
    fun aParkedTerminalKeepsARetryableCursorWhenTheEdgeLoadFails() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m1")
            harness.pager.newerPages.addLast(listOf(harness.record("m2")))
            harness.pager.failNextNewerLoads = 1
            harness.speakConversation("m1")
            val stateBefore = harness.controller.state.value
            val gate = CompletableDeferred<Unit>()
            harness.pager.loadNewerGate = gate

            harness.session.nextMessage()
            runCurrent()
            harness.engine.complete(0)
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(
                TtsHistoryEdgeState.Failed(TtsHistoryDirection.Newer),
                harness.session.edgeState.value,
            )
            assertEquals(stateBefore, harness.controller.state.value)
            assertEquals(listOf("m1"), harness.controller.queuedMessageIds())
            assertEquals(0, harness.focus.releases)
            assertEquals(1, harness.engine.spoken.size)

            harness.session.nextMessage()
            advanceUntilIdle()

            assertNull(harness.session.edgeState.value)
            assertEquals(listOf("m1", "m2"), harness.controller.queuedMessageIds())
            assertEquals(1, (harness.controller.state.value as TtsState.Speaking).messageIndex)
            assertEquals(
                "Nm2: Text m2.",
                harness.engine.spoken
                    .last()
                    .text,
            )
        }

    @Test
    fun aParkedTerminalCompletesWhenTheEdgeRequestStopsAtItsPageBound() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m1")
            repeat(7) { page -> harness.pager.newerPages.addLast(listOf(harness.unspeakableRecord("u$page"))) }
            harness.speakConversation("m1")
            val gate = CompletableDeferred<Unit>()
            harness.pager.loadNewerGate = gate

            harness.session.nextMessage()
            runCurrent()
            harness.engine.complete(0)
            gate.complete(Unit)
            advanceUntilIdle()

            assertNull(harness.session.edgeState.value)
            assertEquals(6, harness.pager.loadNewerCalls)
            assertTrue(harness.controller.state.value is TtsState.Idle)
            assertEquals(1, harness.engine.spoken.size)
            assertEquals(1, harness.focus.releases)
        }

    @Test
    fun aParkedTerminalCompletesWhenTheLoadedTargetTurnsOutUnspeakable() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m1", "m2")
            harness.speakConversation("m1")
            // The record projects, but to nothing sayable, so the window
            // extension is refused and the park has nothing left to play.
            harness.pager.speakableTextById["m2"] = " "

            harness.session.nextMessage()
            harness.engine.complete(0)
            assertTrue(harness.controller.state.value is TtsState.Speaking)
            advanceUntilIdle()

            assertNull(harness.session.edgeState.value)
            assertTrue(harness.controller.state.value is TtsState.Idle)
            assertEquals(1, harness.engine.spoken.size)
            assertEquals(1, harness.focus.releases)
        }

    @Test
    fun anEdgeResolveLandingAfterTheQueueStoppedResurrectsNothing() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m1", "m2")
            harness.speakConversation("m1")
            // Stopping from inside the walk models an audio-focus surrender:
            // the queue is gone while the session's own clear is still queued
            // behind this job, so the resolve really does run against it.
            harness.pager.onProjectSpeakable = { harness.controller.stop() }

            harness.session.nextMessage()
            harness.engine.complete(0)
            assertTrue(harness.controller.state.value is TtsState.Speaking)
            advanceUntilIdle()

            assertNull(harness.session.edgeState.value)
            assertTrue(harness.controller.state.value is TtsState.Idle)
            assertTrue(harness.controller.queuedMessageIds().isEmpty())
            assertEquals(1, harness.engine.spoken.size)
            // The stop released focus, the stale resolve must not release again.
            assertEquals(1, harness.focus.releases)
        }

    @Test
    fun aLiveArrivalAfterAFailedEdgeLoadResumesProgressOnTheAppendedMessage() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m1")
            harness.pager.newerPages.addLast(listOf(harness.record("m2")))
            harness.pager.failNextNewerLoads = 1
            harness.speakConversation("m1")
            val gate = CompletableDeferred<Unit>()
            harness.pager.loadNewerGate = gate

            harness.session.nextMessage()
            runCurrent()
            harness.engine.complete(0)
            gate.complete(Unit)
            advanceUntilIdle()

            // The failed request retained the cursor, so appends are allowed
            // again and must land on a queue whose terminal already parked.
            assertTrue(harness.session.allowsLiveAppend())
            assertTrue(harness.controller.appendSpeech(harness.entry("m3"), Locale.US))
            assertEquals(1, (harness.controller.state.value as TtsState.Speaking).messageIndex)
            assertEquals(
                "Nm3: Text m3.",
                harness.engine.spoken
                    .last()
                    .text,
            )

            harness.engine.complete(1)

            assertTrue(harness.controller.state.value is TtsState.Idle)
        }
}
