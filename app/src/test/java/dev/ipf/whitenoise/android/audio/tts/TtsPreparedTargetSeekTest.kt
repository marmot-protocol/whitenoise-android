package dev.ipf.whitenoise.android.audio.tts

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TtsPreparedTargetSeekTest {
    @Test
    fun pendingSeekSurvivesOldQueueCompletion() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m1")
            harness.pager.newerPages.addLast(listOf(harness.record("m2")))
            val gate = CompletableDeferred<Unit>()
            harness.pager.loadNewerGate = gate
            harness.speakConversation("m1")
            val sessionId = harness.controller.state.value.sessionId
            harness.session.requestSentenceSeek("m2", 2uL, 0, "")
            runCurrent()
            harness.engine.complete(0)
            assertTrue(harness.controller.state.value is TtsState.Preparing)
            assertEquals(null, harness.controller.state.value.passage)
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(sessionId, harness.controller.state.value.sessionId)
            assertEquals("Nm2: Text m2.", harness.spokenTexts().last())
        }

    @Test
    fun timedOutSeekPausesDrainedQueueWithoutReplay() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m1")
            harness.pager.newerPages.addLast(listOf(harness.record("m2")))
            harness.pager.loadNewerGate = CompletableDeferred()
            harness.speakConversation("m1")
            val sessionId = harness.controller.state.value.sessionId
            harness.session.requestSentenceSeek("m2", 2uL, 0, "")
            runCurrent()
            harness.engine.complete(0)
            advanceUntilIdle()
            assertTrue(harness.controller.state.value is TtsState.Paused)
            assertEquals(sessionId, harness.controller.state.value.sessionId)
            assertEquals(1, harness.spokenTexts().size)
            assertTrue(harness.session.edgeState.value is TtsHistoryEdgeState.Failed)
        }

    @Test
    fun loadedTargetKeepsSessionAndStartsRequestedSentence() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m1")
            harness.pager.newerPages.addLast(listOf(harness.record("m2", sentences = 2)))
            harness.speakConversation("m1")
            val sessionId = harness.controller.state.value.sessionId

            assertTrue(harness.session.requestSentenceSeek("m2", 2uL, 1, ""))
            advanceUntilIdle()

            assertEquals(sessionId, harness.controller.state.value.sessionId)
            assertEquals(1, harness.controller.state.value.sentenceIndexWithinMessage)
            assertEquals("Nm2: More m2 2.", harness.spokenTexts().last())
        }

    @Test
    fun revisionMismatchLeavesAudibleCursorUntouched() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m1", "m2")
            harness.speakConversation("m1")
            val before = harness.controller.state.value

            harness.session.requestSentenceSeek("m2", 2uL, 0, "stale-projection")
            advanceUntilIdle()

            assertEquals(before, harness.controller.state.value)
            assertTrue(harness.session.edgeState.value is TtsHistoryEdgeState.Failed)
        }

    @Test
    fun targetRecoveryHasOneCumulativeSixPageBudget() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m1")
            (2..10).forEach { harness.pager.newerPages.addLast(listOf(harness.record("m$it"))) }
            harness.speakConversation("m1")

            harness.session.requestSentenceSeek("m10", 10uL, 0, "")
            advanceUntilIdle()

            assertEquals(6, harness.pager.loadNewerCalls)
            assertEquals(0, harness.pager.projectSpeakableCalls)
            assertEquals(listOf("m1"), harness.controller.queuedMessageIds())
        }

    @Test
    fun supersededLoadCannotCommitOverANewerSeek() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m1", "m2")
            harness.pager.newerPages.addLast(listOf(harness.record("m3")))
            val gate = CompletableDeferred<Unit>()
            harness.pager.loadNewerGate = gate
            harness.speakConversation("m1")
            harness.session.requestSentenceSeek("m3", 3uL, 0, "")
            runCurrent()
            harness.session.requestSentenceSeek("m2", 2uL, 0, "")
            advanceUntilIdle()
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("m1", "m2"), harness.controller.queuedMessageIds())
            assertEquals("Nm2: Text m2.", harness.spokenTexts().last())
        }
}
