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
            // Natural completion, not a replay: one utterance, from the queue's
            // first generation, so no restart ran before or after the settle.
            assertEquals(listOf("Nm1: Text m1."), harness.spokenTexts())
            assertEquals(listOf(1L), harness.spokenGenerations())
            assertEquals(1, harness.focus.releases)
            assertTrue(harness.session.allowsLiveAppend())
        }

    @Test
    fun aNewerEdgeTapCompletesTheSessionWhenHistoryRunsOutMidMessage() =
        runTest {
            val harness = SessionHarness(this)
            harness.pager.loaded += harness.record("m1", sentences = 3)
            harness.speakEntries(listOf(harness.entry("m1", sentences = 3)))

            // Nothing parks — the tap alone has to end the session, exactly as
            // an undeferred tap at the tail always did.
            harness.session.nextMessage()
            advanceUntilIdle()

            assertNull(harness.session.edgeState.value)
            assertTrue(harness.controller.state.value is TtsState.Idle)
            assertEquals(3, harness.engine.spoken.size)
            assertEquals(1, harness.focus.releases)
        }

    @Test
    fun aParkedTerminalAtTheOlderEdgeRestartsTheFirstMessageWhenHistoryRunsOut() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m1", "m2", "m3")
            harness.pager.olderPages.addLast(listOf(harness.unspeakableRecord("u1")))
            harness.speakConversation("m1", "m2", "m3")
            val gate = CompletableDeferred<Unit>()
            harness.pager.loadOlderGate = gate

            harness.session.previousMessage()
            runCurrent()
            // The whole window plays out while the page is in flight, so by
            // settle time the cursor sits on the window's LAST message.
            harness.engine.complete(0)
            harness.engine.complete(1)
            harness.engine.complete(2)
            gate.complete(Unit)
            advanceUntilIdle()

            // The older edge keeps its own pre-paging semantics: the tap
            // restarts the first message rather than stepping back from
            // wherever playback drifted to.
            assertNull(harness.session.edgeState.value)
            val state = harness.controller.state.value as TtsState.Speaking
            assertEquals(0, state.chunkIndex)
            assertEquals(0, state.messageIndex)
            assertEquals(
                listOf("Nm1: Text m1.", "Nm2: Text m2.", "Nm3: Text m3."),
                harness.spokenTexts().takeLast(3),
            )
            assertEquals(listOf(1L, 1L, 1L, 2L, 2L, 2L), harness.spokenGenerations())
            assertEquals(0, harness.focus.releases)
        }

    @Test
    fun aParkedTerminalPausesAndKeepsARetryableCursorWhenTheEdgeLoadFails() =
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

            assertEquals(
                TtsHistoryEdgeState.Failed(TtsHistoryDirection.Newer),
                harness.session.edgeState.value,
            )
            // The parked chunk was already spoken, so a failed load has nothing
            // left to play: it pauses on the retryable cursor and hands audio
            // focus back instead of suppressing other apps in silence.
            assertEquals(pausedTts(0, 1, 0, 1, "Text m1."), harness.controller.state.value)
            assertEquals(listOf("m1"), harness.controller.queuedMessageIds())
            assertEquals(1, harness.focus.releases)
            assertEquals(1, harness.engine.spoken.size)

            harness.session.nextMessage()
            advanceUntilIdle()

            // Re-tapping pages again and repositions, like any paused
            // navigation: playback picks the target up on resume.
            assertNull(harness.session.edgeState.value)
            assertEquals(listOf("m1", "m2"), harness.controller.queuedMessageIds())
            assertEquals(
                pausedTts(1, 2, 1, 2, "Text m2.", messageProgressGeneration = 2L),
                harness.controller.state.value,
            )

            harness.controller.resume()

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
    fun aLiveArrivalAfterAFailedEdgeLoadQueuesBehindThePausedCursor() =
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
            // again — and land on a session the failure left paused.
            assertTrue(harness.session.allowsLiveAppend())
            assertTrue(harness.controller.appendSpeech(harness.entry("m3"), Locale.US))
            assertEquals(pausedTts(0, 2, 0, 2, "Text m1."), harness.controller.state.value)
            assertEquals(1, harness.engine.spoken.size)

            harness.controller.resume()

            assertEquals(
                listOf("Nm1: Text m1.", "Nm3: Text m3."),
                harness.spokenTexts().takeLast(2),
            )

            harness.engine.complete(1)
            harness.engine.complete(2)

            assertTrue(harness.controller.state.value is TtsState.Idle)
        }

    @Test
    fun aMultiSentenceTerminalParksOnlyAfterItsLastSentence() =
        runTest {
            val harness = SessionHarness(this)
            harness.pager.loaded += harness.record("m1", sentences = 3)
            harness.pager.newerPages.addLast(listOf(harness.record("m2")))
            harness.speakEntries(listOf(harness.entry("m1", sentences = 3)))
            val gate = CompletableDeferred<Unit>()
            harness.pager.loadNewerGate = gate

            harness.session.nextMessage()
            runCurrent()
            harness.engine.complete(0)
            harness.engine.complete(1)
            // Progress has to reach the last sentence: parking a chunk early
            // would strand it, reporting sentence 2 of 3 while 3 plays.
            assertEquals(2, harness.controller.state.value.sentenceIndexWithinMessage)
            harness.engine.complete(2)
            assertTrue(harness.controller.state.value is TtsState.Speaking)

            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(
                listOf("Nm1: Text m1.", "More m1 2.", "More m1 3.", "Nm2: Text m2."),
                harness.spokenTexts(),
            )
            assertEquals(1, (harness.controller.state.value as TtsState.Speaking).messageIndex)
            assertEquals(0, harness.focus.releases)
        }

    @Test
    fun aParkedTerminalAtTheNewerSentenceEdgePlaysTheLoadedTarget() =
        runTest {
            val harness = SessionHarness(this)
            harness.pager.loaded += harness.record("m1", sentences = 2)
            harness.pager.newerPages.addLast(listOf(harness.record("m2")))
            harness.speakEntries(listOf(harness.entry("m1", sentences = 2)))
            val gate = CompletableDeferred<Unit>()
            harness.pager.loadNewerGate = gate

            // Tapped on the last sentence, so this is a genuine newer crossing.
            harness.engine.complete(0)
            harness.session.nextSentence()
            runCurrent()
            harness.engine.complete(1)
            assertTrue(harness.controller.state.value is TtsState.Speaking)

            gate.complete(Unit)
            advanceUntilIdle()

            assertNull(harness.session.edgeState.value)
            assertEquals(listOf("m1", "m2"), harness.controller.queuedMessageIds())
            assertEquals(
                "Nm2: Text m2.",
                harness.engine.spoken
                    .last()
                    .text,
            )
            assertEquals(0, harness.focus.releases)
        }

    @Test
    fun aParkedTerminalAtTheOlderSentenceEdgeLandsOnTheLoadedLastSentence() =
        runTest {
            val harness = SessionHarness(this)
            harness.pager.loaded += harness.record("m2", sentences = 2)
            harness.pager.olderPages.addLast(listOf(harness.record("m1", sentences = 2)))
            harness.speakEntries(listOf(harness.entry("m2", sentences = 2)))
            val gate = CompletableDeferred<Unit>()
            harness.pager.loadOlderGate = gate

            harness.session.previousSentence()
            runCurrent()
            harness.engine.complete(0)
            harness.engine.complete(1)
            assertTrue(harness.controller.state.value is TtsState.Speaking)

            gate.complete(Unit)
            advanceUntilIdle()

            assertNull(harness.session.edgeState.value)
            assertEquals(listOf("m1", "m2"), harness.controller.queuedMessageIds())
            // A previous-sentence tap lands on the loaded message's LAST
            // sentence, not its first.
            assertEquals(
                speakingTts(
                    1,
                    4,
                    0,
                    2,
                    "Text m1. More m1 2.",
                    sentenceIndex = 1,
                    sentenceCount = 2,
                    messageProgressGeneration = 2L,
                ),
                harness.controller.state.value,
            )
            assertEquals("Nm1: More m1 2.", harness.engine.spoken[2].text)
            assertEquals(0, harness.focus.releases)
        }
}
