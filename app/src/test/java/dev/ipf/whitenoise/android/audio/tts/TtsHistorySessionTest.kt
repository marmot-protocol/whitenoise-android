package dev.ipf.whitenoise.android.audio.tts

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Session-level pagination behavior against the real controller, queue, and
 * chunker — only the speech engine and the conversation pager are fakes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TtsHistorySessionTest {
    /** Older-edge sentence navigation lands on the preceding message's final sentence. */
    @Test
    fun previousSentencePagesOlderAndTargetsTheLastLogicalSentence() =
        runTest {
            val harness = SessionHarness(this)
            harness.pager.loaded += harness.record("m2", sentences = 2)
            harness.pager.olderPages.addLast(listOf(harness.record("m1", sentences = 3)))
            harness.speakEntries(listOf(harness.entry("m2", sentences = 2)))

            harness.session.previousSentence()
            advanceUntilIdle()

            assertEquals(listOf("m1", "m2"), harness.controller.queuedMessageIds())
            val state = harness.controller.state.value as TtsState.Speaking
            assertEquals(0, state.messageIndex)
            assertEquals(2, state.sentenceIndexWithinMessage)
            assertTrue(harness.spokenTexts().any { it == "Nm1: More m1 3." })
        }

    /** Newer-edge sentence navigation pages once and targets the next message's first sentence. */
    @Test
    fun nextSentencePagesNewerAndTargetsTheFirstLogicalSentence() =
        runTest {
            val harness = SessionHarness(this)
            harness.pager.loaded += harness.record("m1", sentences = 2)
            harness.pager.newerPages.addLast(listOf(harness.record("m2", sentences = 3)))
            harness.speakEntries(listOf(harness.entry("m1", sentences = 2)))

            harness.session.nextSentence()
            assertEquals(1, harness.controller.state.value.sentenceIndexWithinMessage)
            harness.session.nextSentence()
            advanceUntilIdle()

            assertEquals(listOf("m1", "m2"), harness.controller.queuedMessageIds())
            val state = harness.controller.state.value as TtsState.Speaking
            assertEquals(1, state.messageIndex)
            assertEquals(0, state.sentenceIndexWithinMessage)
            assertTrue(harness.spokenTexts().any { it == "Nm2: Text m2." })
        }

    @Test
    fun conversationSourceUsesThePlaybackSessionAndClearsAtItsTerminalBoundary() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m1")
            harness.speakConversation("m1")

            assertEquals(
                TtsConversationSource(
                    accountRef = "account",
                    groupIdHex = "group",
                    sessionId = harness.controller.state.value.sessionId,
                ),
                harness.session.conversationSource.value,
            )

            harness.controller.stop()
            runCurrent()

            assertNull(harness.session.conversationSource.value)
        }

    @Test
    fun previousMessageAtHeadSpeaksTheNearestOlderSpeakableFromTheLoadedTimeline() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m1", "m2", "m3")
            harness.speakConversation("m3")

            harness.session.previousMessage()
            assertEquals(
                TtsHistoryEdgeState.Loading(TtsHistoryDirection.Older),
                harness.session.edgeState.value,
            )
            advanceUntilIdle()

            assertNull(harness.session.edgeState.value)
            assertEquals(listOf("m1", "m2", "m3"), harness.controller.queuedMessageIds())
            val state = harness.controller.state.value as TtsState.Speaking
            assertEquals(1, state.messageIndex)
            assertEquals(3, state.messageCount)
            assertEquals(
                "Nm2: Text m2.",
                harness.engine.spoken
                    .map { it.text }
                    .takeLast(2)
                    .first(),
            )
            assertEquals(0, harness.pager.loadOlderCalls)
        }

    @Test
    fun previousMessagePagesOlderWhenTheLoadedWindowIsExhausted() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m3")
            harness.pager.olderPages.addLast(listOf(harness.record("m1"), harness.record("m2")))
            harness.speakConversation("m3")

            harness.session.previousMessage()
            advanceUntilIdle()

            assertEquals(1, harness.pager.loadOlderCalls)
            assertEquals(listOf("m1", "m2", "m3"), harness.controller.queuedMessageIds())
            assertEquals(1, (harness.controller.state.value as TtsState.Speaking).messageIndex)
        }

    @Test
    fun previousMessageSkipsUnspeakableRecordsAcrossMultiplePages() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m4")
            harness.pager.olderPages.addLast(listOf(harness.unspeakableRecord("u3")))
            harness.pager.olderPages.addLast(listOf(harness.record("m2")))
            harness.speakConversation("m4")

            harness.session.previousMessage()
            advanceUntilIdle()

            assertEquals(2, harness.pager.loadOlderCalls)
            assertEquals(listOf("m2", "m4"), harness.controller.queuedMessageIds())
            assertEquals(
                "Nm2: Text m2.",
                harness.engine.spoken
                    .map { it.text }
                    .takeLast(2)
                    .first(),
            )
        }

    @Test
    fun trueBeginningOfHistoryRestartsTheFirstMessageWithoutErrorOrCompletion() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m1")
            harness.speakConversation("m1")

            harness.session.previousMessage()
            advanceUntilIdle()

            assertNull(harness.session.edgeState.value)
            val state = harness.controller.state.value
            assertTrue(state is TtsState.Speaking)
            assertEquals(0, state.chunkIndex)
        }

    @Test
    fun trueEndOfHistoryCompletesTheSessionNaturally() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m1")
            harness.speakConversation("m1")

            harness.session.nextMessage()
            advanceUntilIdle()

            assertNull(harness.session.edgeState.value)
            assertTrue(harness.controller.state.value is TtsState.Idle)
        }

    @Test
    fun nextMessageAtTailPagesNewerAndSpeaksTheTarget() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m1")
            harness.pager.newerPages.addLast(listOf(harness.record("m2"), harness.record("m3")))
            harness.speakConversation("m1")

            harness.session.nextMessage()
            advanceUntilIdle()

            assertEquals(1, harness.pager.loadNewerCalls)
            assertEquals(listOf("m1", "m2", "m3"), harness.controller.queuedMessageIds())
            val state = harness.controller.state.value as TtsState.Speaking
            assertEquals(1, state.messageIndex)
            assertEquals(
                "Nm2: Text m2.",
                harness.engine.spoken
                    .map { it.text }
                    .takeLast(2)
                    .first(),
            )
        }

    @Test
    fun failedEdgeLoadKeepsTheCursorAndTheNextTapRetriesOnlyThatRequest() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m2")
            harness.pager.olderPages.addLast(listOf(harness.record("m1")))
            harness.pager.failNextOlderLoads = 1
            harness.speakConversation("m2")
            val stateBefore = harness.controller.state.value

            harness.session.previousMessage()
            advanceUntilIdle()

            assertEquals(
                TtsHistoryEdgeState.Failed(TtsHistoryDirection.Older),
                harness.session.edgeState.value,
            )
            assertEquals(stateBefore, harness.controller.state.value)
            assertEquals(1, harness.pager.loadOlderCalls)
            assertEquals(listOf("m2"), harness.controller.queuedMessageIds())

            harness.session.previousMessage()
            advanceUntilIdle()

            assertNull(harness.session.edgeState.value)
            assertEquals(2, harness.pager.loadOlderCalls)
            assertEquals(listOf("m1", "m2"), harness.controller.queuedMessageIds())
        }

    @Test
    fun edgeTapsWhileLoadingCoalesceIntoASingleRequest() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m2")
            harness.pager.olderPages.addLast(listOf(harness.record("m1")))
            harness.speakConversation("m2")

            harness.session.previousMessage()
            harness.session.previousMessage()
            harness.session.previousMessage()
            advanceUntilIdle()

            assertEquals(1, harness.pager.loadOlderCalls)
            assertEquals(listOf("m1", "m2"), harness.controller.queuedMessageIds())
        }

    @Test
    fun aTapWhileAPageJobIsGenuinelyInFlightStartsNoSecondJob() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m2")
            harness.pager.olderPages.addLast(listOf(harness.record("m1")))
            harness.speakConversation("m2")
            val gate = CompletableDeferred<Unit>()
            harness.pager.loadOlderGate = gate

            harness.session.previousMessage()
            // The job is now suspended INSIDE loadOlder, not merely scheduled —
            // a second tap here exercises the concurrent-jobs guard for real.
            runCurrent()
            assertEquals(1, harness.pager.loadOlderCalls)
            harness.session.previousMessage()
            runCurrent()
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, harness.pager.loadOlderCalls)
            assertEquals(listOf("m1", "m2"), harness.controller.queuedMessageIds())
            assertEquals(0, (harness.controller.state.value as TtsState.Speaking).messageIndex)
        }

    @Test
    fun stopMidLoadCancelsThePageJobAndDropsItsCompletion() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m2")
            harness.pager.olderPages.addLast(listOf(harness.record("m1")))
            harness.speakConversation("m2")

            harness.session.previousMessage()
            harness.controller.stop()
            harness.session.onSessionCleared()
            advanceUntilIdle()

            assertNull(harness.session.edgeState.value)
            assertTrue(harness.controller.state.value is TtsState.Idle)
            assertTrue(harness.controller.queuedMessageIds().isEmpty())
        }

    @Test
    fun aNewManualStartMidLoadInvalidatesTheStaleCompletion() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m2")
            harness.pager.olderPages.addLast(listOf(harness.record("m1")))
            harness.speakConversation("m2")
            harness.session.previousMessage()

            harness.speakConversation("m9")
            advanceUntilIdle()

            assertNull(harness.session.edgeState.value)
            assertEquals(listOf("m9"), harness.controller.queuedMessageIds())
        }

    @Test
    fun anInvalidationWhileTheJobIsMidFlightLeavesItsLateCompletionInert() =
        runTest {
            val harness = SessionHarness(this)
            // The anchor is missing from the loaded window and unrecoverable,
            // so the walk would end in a retryable failure — but a new manual
            // start lands while the job is mid-flight, so that late failure
            // belongs to a dead generation and must not surface.
            harness.loadTimeline("t1")
            harness.speakConversation("m2")
            harness.pager.onEnsureLoaded = { harness.speakConversation("m9") }

            harness.session.previousMessage()
            advanceUntilIdle()

            assertNull(harness.session.edgeState.value)
            assertEquals(listOf("m9"), harness.controller.queuedMessageIds())
            assertTrue(harness.controller.state.value is TtsState.Speaking)
        }

    /** Proves invalidation cannot replace the queue during accepted edge settlement. */
    @Test
    fun invalidationWaitsForAtomicEdgeSettlementBeforeReplacingTheQueue() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m2")
            harness.pager.olderPages.addLast(listOf(harness.record("m1")))
            harness.speakConversation("m2")
            val invalidationEntered = CountDownLatch(1)
            val invalidationFinished = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            try {
                harness.session.settlementAwaiterForTests = {
                    executor.submit {
                        invalidationEntered.countDown()
                        harness.session.onSessionCleared()
                        harness.speakConversation("m9")
                        invalidationFinished.countDown()
                    }
                    assertTrue(invalidationEntered.await(5, TimeUnit.SECONDS))
                    assertFalse(
                        "queue replacement must wait until accepted settlement finishes",
                        invalidationFinished.await(100, TimeUnit.MILLISECONDS),
                    )
                }

                harness.session.previousMessage()
                advanceUntilIdle()
                assertTrue(invalidationFinished.await(5, TimeUnit.SECONDS))

                assertEquals(listOf("m9"), harness.controller.queuedMessageIds())
                assertTrue(harness.controller.state.value is TtsState.Speaking)
            } finally {
                harness.session.settlementAwaiterForTests = null
                executor.shutdownNow()
            }
        }

    @Test
    fun naturalCompletionDetachesTheSessionWithoutAnExplicitClear() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m1", "m2")
            // Queue tail m1 is not the timeline tail, so the session starts
            // detached — only the completion collector can restore appends.
            harness.speakConversation("m1")
            advanceUntilIdle()
            assertFalse(harness.session.allowsLiveAppend())

            var next = 0
            while (harness.controller.state.value is TtsState.Speaking) {
                harness.engine.complete(next)
                next += 1
            }
            assertTrue(harness.controller.state.value is TtsState.Idle)
            advanceUntilIdle()

            assertTrue(harness.session.allowsLiveAppend())
            harness.session.nextMessage()
            advanceUntilIdle()
            assertEquals(0, harness.pager.loadNewerCalls)
        }

    @Test
    fun pausedEdgeNavigationRepositionsSilentlyAndSpeaksOnResume() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m1", "m2")
            harness.speakConversation("m2")
            harness.controller.pause()
            val spokenBefore = harness.engine.spoken.size

            harness.session.previousMessage()
            advanceUntilIdle()

            assertNull(harness.session.edgeState.value)
            val paused = harness.controller.state.value as TtsState.Paused
            assertEquals(0, paused.messageIndex)
            assertEquals(2, paused.messageCount)
            assertEquals(spokenBefore, harness.engine.spoken.size)

            harness.controller.resume()

            assertEquals(
                "Nm1: Text m1.",
                harness.engine.spoken
                    .map { it.text }
                    .takeLast(2)
                    .first(),
            )
        }

    @Test
    fun pagingAwayFromTheLiveTailRefusesLiveAppends() =
        runTest {
            val harness = SessionHarness(this)
            val queued = (11..60).map { "m%02d".format(it) }
            val older = (1..10).map { "m%02d".format(it) }
            harness.loadTimeline(*(older + queued).toTypedArray())
            harness.speakConversation(*queued.toTypedArray())
            assertTrue(harness.session.allowsLiveAppend())
            harness.pager.loaded.add(harness.record("m61"))
            assertTrue(harness.session.allowsLiveAppend())
            assertTrue(harness.controller.appendSpeech(harness.entry("m61"), Locale.US))

            harness.session.previousMessage()
            advanceUntilIdle()

            // 51 queued + 10 older overflows the window cap and evicts the
            // newest tail, detaching the session from the live edge.
            assertEquals(TTS_HISTORY_WINDOW_MAX_MESSAGES, harness.controller.queuedMessageIds().size)
            assertEquals("m01", harness.controller.queuedMessageIds().first())
            assertFalse(harness.session.allowsLiveAppend())
        }

    @Test
    fun aTimelineWindowTrimIsNotMistakenForALiveArrival() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m1", "m2", "m3")
            harness.speakConversation("m1", "m2", "m3")
            assertTrue(harness.session.allowsLiveAppend())

            // Paging the chat older replaces the subscription window and trims
            // the newest rows — the window's new last id is an OLD message.
            harness.pager.loaded.removeAll { it.messageIdHex == "m3" }
            harness.pager.loaded.add(0, harness.record("m0"))

            assertFalse(harness.session.allowsLiveAppend())
        }

    @Test
    fun aGenuineArrivalKeepsAppendsFlowingAndAdvancesTheKnownTail() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m1", "m2")
            harness.speakConversation("m1", "m2")

            harness.pager.loaded.add(harness.record("m3"))
            assertTrue(harness.session.allowsLiveAppend())

            // The verified tail moved to m3, so a trim dropping m3 now refuses.
            harness.pager.loaded.removeAll { it.messageIdHex == "m3" }
            assertFalse(harness.session.allowsLiveAppend())
        }

    @Test
    fun aLocalOnlyTailIsNeverTrackedAsTheLiveTimelineTail() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m1", "m2")
            // An in-flight send sits at the window tail under a temp id that
            // disappears once its projection lands.
            harness.loadLocalOnlyRow("temp1")
            harness.speakConversation("m1", "m2")

            harness.pager.loaded.removeAll { it.messageIdHex == "temp1" }
            harness.pager.loaded.add(harness.record("m3"))

            assertTrue(harness.session.allowsLiveAppend())

            // The tracked tail advanced onto the arrival, so a trim of it refuses.
            harness.pager.loaded.removeAll { it.messageIdHex == "m3" }
            assertFalse(harness.session.allowsLiveAppend())
        }

    @Test
    fun appendsAreRefusedWhileAnEdgeLoadOwnsTheWindow() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m2")
            harness.pager.olderPages.addLast(listOf(harness.record("m1")))
            harness.speakConversation("m2")
            val gate = CompletableDeferred<Unit>()
            harness.pager.loadOlderGate = gate

            harness.session.previousMessage()
            assertFalse(harness.session.allowsLiveAppend())

            gate.complete(Unit)
            advanceUntilIdle()
            assertTrue(harness.session.allowsLiveAppend())
        }

    @Test
    fun cappedBacklogStartBeginsDetachedAndTheNewerEdgeReachesTheRemainder() =
        runTest {
            val harness = SessionHarness(this)
            val ids = (1..60).map { "m%02d".format(it) }
            harness.loadTimeline(*ids.toTypedArray())
            harness.speakConversation(*ids.toTypedArray())
            assertEquals(TTS_AUTO_READ_MAX_MESSAGES, harness.controller.queuedMessageIds().size)

            // The queue tail is unread #50, not the live tail — an arrival must
            // not splice mid-history where it would strand #51..N forever.
            assertFalse(harness.session.allowsLiveAppend())
            harness.pager.loaded.add(harness.record("m61"))
            assertFalse(harness.session.allowsLiveAppend())

            repeat(49) { harness.session.nextMessage() }
            harness.session.nextMessage()
            advanceUntilIdle()

            val state = harness.controller.state.value as TtsState.Speaking
            assertEquals("Text m51.", state.messagePreview)
            assertEquals("m60", harness.controller.queuedMessageIds().last())
        }

    @Test
    fun anUncappedBacklogStartStaysAttachedToTheLiveTail() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m1", "m2", "m3")
            harness.speakConversation("m1", "m2", "m3")

            assertTrue(harness.session.allowsLiveAppend())
        }

    @Test
    fun ordinaryBoundedAutoReadStopsAtItsCapWithoutPaginating() =
        runTest {
            val harness = SessionHarness(this)
            val ids = (1..60).map { "m%02d".format(it) }
            harness.loadTimeline(*ids.toTypedArray())
            harness.speakConversation(*ids.toTypedArray())

            assertEquals(TTS_AUTO_READ_MAX_MESSAGES, harness.controller.queuedMessageIds().size)
            var next = 0
            while (harness.controller.state.value is TtsState.Speaking) {
                harness.engine.complete(next)
                next += 1
            }

            assertTrue(harness.controller.state.value is TtsState.Idle)
            assertEquals(0, harness.pager.loadOlderCalls)
            assertEquals(0, harness.pager.loadNewerCalls)
        }

    @Test
    fun aMissingConversationControllerFailsRetryablyInsteadOfCompleting() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m1", "m2")
            harness.speakConversation("m2")
            harness.pagerAvailable = false
            val stateBefore = harness.controller.state.value

            harness.session.previousMessage()
            advanceUntilIdle()

            assertEquals(
                TtsHistoryEdgeState.Failed(TtsHistoryDirection.Older),
                harness.session.edgeState.value,
            )
            assertEquals(stateBefore, harness.controller.state.value)
        }

    @Test
    fun anAnchorOlderThanTheLoadedWindowRecoversByPagingOlder() =
        runTest {
            val harness = SessionHarness(this)
            // The chat was scrolled newer while listening: the window moved
            // past the anchor, which now sits in unloaded older history.
            harness.loadTimeline("m5", "m6")
            harness.pager.olderPages.addLast(
                listOf(harness.record("m2"), harness.record("m3"), harness.record("m4")),
            )
            harness.speakConversation("m3")

            harness.session.previousMessage()
            advanceUntilIdle()

            assertNull(harness.session.edgeState.value)
            assertEquals(listOf("m2", "m3"), harness.controller.queuedMessageIds())
            assertEquals("Text m2.", (harness.controller.state.value as TtsState.Speaking).messagePreview)
        }

    @Test
    fun anAnchorNewerThanTheLoadedWindowRecoversByPagingNewer() =
        runTest {
            val harness = SessionHarness(this)
            // The chat was scrolled older while listening: only paging NEWER
            // can bring the window back to the anchor.
            harness.loadTimeline("m1", "m2")
            harness.pager.newerPages.addLast(
                listOf(harness.record("m3"), harness.record("m4"), harness.record("m5")),
            )
            harness.speakConversation("m5")

            harness.session.previousMessage()
            advanceUntilIdle()

            assertNull(harness.session.edgeState.value)
            assertTrue(harness.pager.loadNewerCalls >= 1)
            assertEquals(0, harness.pager.loadOlderCalls)
            assertEquals(listOf("m1", "m2", "m3", "m4", "m5"), harness.controller.queuedMessageIds())
            assertEquals("Text m4.", (harness.controller.state.value as TtsState.Speaking).messagePreview)
        }

    @Test
    fun anUnrecoverableAnchorFailsRetryably() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("t1")
            harness.speakConversation("m2")

            harness.session.previousMessage()
            advanceUntilIdle()

            assertEquals(
                TtsHistoryEdgeState.Failed(TtsHistoryDirection.Older),
                harness.session.edgeState.value,
            )
            assertEquals(listOf("m2"), harness.controller.queuedMessageIds())
        }

    @Test
    fun anArrivalDuringANewerEdgeWalkNeverYieldsAFalseReattachment() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m1", "m2")
            // Detached start: the queue holds m1 while the timeline tail is m2.
            harness.speakConversation("m1")
            harness.pager.onProjectSpeakable = { id ->
                if (id == "m2") {
                    harness.pager.loaded.add(harness.record("m3"))
                    harness.pager.onProjectSpeakable = null
                }
            }

            harness.session.nextMessage()
            advanceUntilIdle()

            // The arrival landed between the walk and the apply: the final
            // window may not hold it, so the session must stay detached.
            assertFalse(harness.session.allowsLiveAppend())

            harness.session.nextMessage()
            advanceUntilIdle()

            assertEquals(listOf("m1", "m2", "m3"), harness.controller.queuedMessageIds())
            assertTrue(harness.session.allowsLiveAppend())
        }

    @Test
    fun aLongRunOfUnspeakableHistorySilentlyStopsAtThePageBound() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m9")
            harness.speakConversation("m9")
            repeat(7) { page ->
                harness.pager.olderPages.addLast(listOf(harness.unspeakableRecord("u$page")))
            }
            val stateBefore = harness.controller.state.value

            harness.session.previousMessage()
            advanceUntilIdle()

            assertNull(harness.session.edgeState.value)
            assertEquals(6, harness.pager.loadOlderCalls)
            assertEquals(listOf("m9"), harness.controller.queuedMessageIds())
            assertEquals(stateBefore, harness.controller.state.value)
        }

    @Test
    fun oneEdgeRequestCapsHowManyRecordsItTriesToProject() =
        runTest {
            val harness = SessionHarness(this)
            harness.pager.loaded += (1..250).map { harness.unspeakableRecord("u%03d".format(it)) }
            harness.loadTimeline("m999")
            harness.speakConversation("m999")

            harness.session.previousMessage()
            advanceUntilIdle()

            assertEquals(200, harness.pager.projectSpeakableCalls)
            // Exhausting the cap reads exactly like the end of nearby history:
            // no error state, no window change, the first message restarts.
            assertNull(harness.session.edgeState.value)
            assertEquals(listOf("m999"), harness.controller.queuedMessageIds())
            val state = harness.controller.state.value
            assertTrue(state is TtsState.Speaking)
            assertEquals(0, state.chunkIndex)
        }
}
