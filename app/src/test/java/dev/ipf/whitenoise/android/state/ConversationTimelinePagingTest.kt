package dev.ipf.whitenoise.android.state

import android.os.Looper
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.whitenoise.android.diagnostics.PerformanceOperation
import dev.ipf.whitenoise.android.diagnostics.PerformancePhase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * A page that the engine never answers must leave the reader a retry affordance rather than a
 * spinner the scroll-prefetch effect re-issues on every frame.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ConversationTimelinePagingTest {
    /** A window deadline reports TIMED_OUT, arms the older-page retry, and blocks further prefetch. */
    @Test
    fun timedOutOlderPageBlocksPrefetchAndArmsRetry() =
        runBlocking {
            val subscription = subscriptionWith(outcome(ConversationWindowUnchangedReason.TIMED_OUT))
            withController(subscription) { controller ->
                settle()

                val load = controller.loadOlderPageInternal()

                assertEquals(ConversationPageLoad.TIMED_OUT, load)
                assertTrue(controller.olderPageBlocked)
                assertFalse(controller.isLoadingOlder)
            }
        }

    /** A window that is not ready at first is waited out inside the budget and then advances. */
    @Test
    fun notReadyOlderPageRetriesWithinBudgetThenAdvances() =
        runBlocking {
            val subscription =
                subscriptionWith(
                    outcome(ConversationWindowUnchangedReason.NOT_READY),
                    olderPage(),
                )
            withController(subscription) { controller ->
                settle()

                val load = controller.loadOlderPageInternal()

                assertEquals(ConversationPageLoad.ADVANCED, load)
                assertFalse(controller.olderPageBlocked)
                assertEquals(2, subscription.backwardsCallCount)
            }
        }

    /** A window that stays not-ready spends the whole budget once, then leaves a retry affordance. */
    @Test
    fun notReadyOlderPageExhaustsBudgetAndBlocks() =
        runBlocking {
            val attempts = CONVERSATION_PAGE_NOT_READY_ATTEMPTS
            val stuck = Array(attempts) { outcome(ConversationWindowUnchangedReason.NOT_READY) }
            val subscription = subscriptionWith(*stuck)
            withController(subscription) { controller ->
                settle()

                val load = controller.loadOlderPageInternal()

                assertEquals(ConversationPageLoad.NOT_READY, load)
                assertTrue(controller.olderPageBlocked)
                assertEquals(CONVERSATION_PAGE_NOT_READY_ATTEMPTS, subscription.backwardsCallCount)
            }
        }

    /** Retrying after a blocked page clears the block and pages again. */
    @Test
    fun retryAfterABlockedPageClearsTheBlock() =
        runBlocking {
            val subscription =
                subscriptionWith(
                    outcome(ConversationWindowUnchangedReason.TIMED_OUT),
                    olderPage(),
                )
            withController(subscription) { controller ->
                settle()
                assertEquals(ConversationPageLoad.TIMED_OUT, controller.loadOlderPageInternal())
                assertTrue(controller.olderPageBlocked)

                controller.retryLoadFailure()
                settle()

                assertFalse(controller.olderPageBlocked)
            }
        }

    /** A superseded window is not the engine failing to answer, so it leaves no retry affordance. */
    @Test
    fun supersededOlderPageReportsNoProgressWithoutBlocking() =
        runBlocking {
            val subscription = subscriptionWith(outcome(ConversationWindowUnchangedReason.SUPERSEDED))
            withController(subscription) { controller ->
                settle()

                val load = controller.loadOlderPageInternal()

                assertEquals(ConversationPageLoad.NO_PROGRESS, load)
                assertFalse(controller.olderPageBlocked)
            }
        }

    /** The oldest visible row is reported as the window anchor before an older page is requested. */
    @Test
    fun olderPageReportsTheAnchorBeforePaging() =
        runBlocking {
            val subscription =
                subscriptionWith(olderPage())
            withController(subscription) { controller ->
                settle()

                controller.loadOlderPageInternal(anchorId = SEED_ID)

                assertEquals(listOf(SEED_ID), subscription.anchorReports)
                assertEquals(
                    listOf("setVisibleAnchor", "paginateBackwards"),
                    subscription.lifecycleEventOrder.filter { it in ANCHOR_THEN_PAGE },
                )
            }
        }

    /** A row the window no longer retains, such as an optimistic local id, is never sent as an anchor. */
    @Test
    fun unretainedRowIsNeverSentAsAnAnchor() =
        runBlocking {
            val subscription =
                subscriptionWith(olderPage())
            withController(subscription) { controller ->
                settle()

                controller.loadOlderPageInternal(anchorId = "0d2b6c1e-4f6a-4b1e-9c1d-optimistic")

                assertTrue(subscription.anchorReports.isEmpty())
                assertEquals(1, subscription.backwardsCallCount)
            }
        }

    /** A hydrated reply target retries a transient not-ready jump and installs the exact window. */
    @Test
    fun replyTargetRetriesNotReadyJumpAndMaterializes() =
        runBlocking {
            val target = record(TARGET_ID, timelineAt = 50uL)
            val subscription =
                ScriptedConversationTimelineSubscription(
                    snapshotPage = page(listOf(record(SEED_ID, timelineAt = 200uL)), hasMoreBefore = true),
                    jumpOutcomes =
                        mutableListOf(
                            ConversationJumpOutcome.Window(
                                outcome(ConversationWindowUnchangedReason.NOT_READY),
                            ),
                            ConversationJumpOutcome.Window(
                                TimelinePageOutcome.Advanced(page(listOf(target), hasMoreBefore = true)),
                            ),
                        ),
                )
            withController(subscription) { controller ->
                settle()

                val availability = controller.loadMessageAvailability(TARGET_ID)

                assertEquals(MessageAvailability.AVAILABLE, availability)
                assertEquals(2, subscription.jumpCallCount)
                assertTrue(controller.timeline.any { it.record.messageIdHex == TARGET_ID })
                assertEquals(0, subscription.backwardsCallCount)
            }
        }

    /** A revision race immediately retries against the newest installed window revision. */
    @Test
    fun replyTargetRetriesSupersededJumpAndMaterializes() =
        runBlocking {
            val target = record(TARGET_ID, timelineAt = 50uL)
            val subscription =
                ScriptedConversationTimelineSubscription(
                    snapshotPage = page(listOf(record(SEED_ID, timelineAt = 200uL)), hasMoreBefore = true),
                    jumpOutcomes =
                        mutableListOf(
                            ConversationJumpOutcome.Window(
                                outcome(ConversationWindowUnchangedReason.SUPERSEDED),
                            ),
                            ConversationJumpOutcome.Window(
                                TimelinePageOutcome.Advanced(page(listOf(target), hasMoreBefore = true)),
                            ),
                        ),
                )
            withController(subscription) { controller ->
                settle()

                val availability = controller.loadMessageAvailability(TARGET_ID)

                assertEquals(MessageAvailability.AVAILABLE, availability)
                assertEquals(2, subscription.jumpCallCount)
                assertEquals(0, subscription.backwardsCallCount)
            }
        }

    /** An advanced replacement that omits the requested id is delayed, never authoritative absence. */
    @Test
    fun replyTargetAdvancedWindowWithoutTargetRemainsRetryable() =
        runBlocking {
            val subscription =
                ScriptedConversationTimelineSubscription(
                    snapshotPage = page(listOf(record(SEED_ID, timelineAt = 200uL)), hasMoreBefore = true),
                    jumpOutcomes =
                        mutableListOf(
                            ConversationJumpOutcome.Window(
                                TimelinePageOutcome.Advanced(
                                    page(listOf(record(OLDER_ID, timelineAt = 100uL)), hasMoreBefore = true),
                                ),
                            ),
                        ),
                )
            withController(subscription) { controller ->
                settle()

                val availability = controller.loadMessageAvailability(TARGET_ID)

                assertEquals(MessageAvailability.RETRYABLE, availability)
                assertEquals(1, subscription.jumpCallCount)
                assertEquals(0, subscription.backwardsCallCount)
            }
        }

    /** A timed-out exact jump is retryable and never falls through to a false missing-target result. */
    @Test
    fun replyTargetTimeoutRemainsRetryable() =
        runBlocking {
            val subscription =
                ScriptedConversationTimelineSubscription(
                    snapshotPage = page(listOf(record(SEED_ID, timelineAt = 200uL)), hasMoreBefore = true),
                    jumpOutcomes =
                        mutableListOf(
                            ConversationJumpOutcome.Window(
                                outcome(ConversationWindowUnchangedReason.TIMED_OUT),
                            ),
                        ),
                )
            withController(subscription) { controller ->
                settle()

                val availability = controller.loadMessageAvailability(TARGET_ID)

                assertEquals(MessageAvailability.RETRYABLE, availability)
                assertEquals(0, subscription.backwardsCallCount)
            }
        }

    /** An unmodeled native jump failure keeps the loaded window and offers a retry instead of crashing. */
    @Test
    fun replyTargetJumpFailureRemainsRetryable() =
        runBlocking {
            val subscription =
                ScriptedConversationTimelineSubscription(
                    snapshotPage = page(listOf(record(SEED_ID, timelineAt = 200uL)), hasMoreBefore = true),
                    jumpFailure = IllegalStateException("scripted jump failure"),
                )
            withController(subscription) { controller ->
                settle()

                val availability = controller.loadMessageAvailability(TARGET_ID)

                assertEquals(MessageAvailability.RETRYABLE, availability)
                assertEquals(1, subscription.jumpCallCount)
                assertEquals(0, subscription.backwardsCallCount)
            }
        }

    /** Only an explicit missing-target answer is classified as missing on a real window. */
    @Test
    fun replyTargetExplicitMissingRemainsMissing() =
        runBlocking {
            val subscription =
                ScriptedConversationTimelineSubscription(
                    snapshotPage = page(listOf(record(SEED_ID, timelineAt = 200uL)), hasMoreBefore = true),
                    jumpOutcomes = mutableListOf(ConversationJumpOutcome.Missing),
                )
            withController(subscription) { controller ->
                settle()

                val availability = controller.loadMessageAvailability(TARGET_ID)

                assertEquals(MessageAvailability.MISSING, availability)
            }
        }

    /**
     * An automatic older page the engine answers with the rows already held stands the prefetch
     * down after one ask, without arming the failure banner, so the viewport parked on the oldest
     * row cannot re-issue it the moment it finishes (#2727).
     */
    @Test
    fun automaticOlderPageWithoutNewRowsStandsThePrefetchDown() =
        runBlocking {
            val subscription = subscriptionWith(sameWindow(), sameWindow())
            withController(subscription) { controller ->
                settle()

                val first = controller.loadOlderPageInternal(origin = ConversationPagingOrigin.AUTOMATIC)
                val second = controller.loadOlderPageInternal(origin = ConversationPagingOrigin.AUTOMATIC)

                assertEquals(ConversationPageLoad.NO_PROGRESS, first)
                assertEquals(ConversationPageLoad.NO_PROGRESS, second)
                assertTrue(controller.automaticOlderPagingBlocked)
                assertFalse("no older rows is an answer, not a failure to answer", controller.olderPageBlocked)
                assertFalse(controller.isLoadingOlder)
                assertEquals("a stood-down prefetch stops asking the engine", 1, subscription.backwardsCallCount)
            }
        }

    /** The reader's own ask from the header proceeds while the prefetch stands down, and rows release it. */
    @Test
    fun explicitOlderPageProceedsWhileThePrefetchStandsDownAndRowsReleaseIt() =
        runBlocking {
            val subscription = subscriptionWith(sameWindow(), olderPage())
            withController(subscription) { controller ->
                settle()
                controller.loadOlderPageInternal(origin = ConversationPagingOrigin.AUTOMATIC)
                assertTrue(controller.automaticOlderPagingBlocked)

                val load = controller.loadOlderPageInternal()

                assertEquals(ConversationPageLoad.ADVANCED, load)
                assertEquals(2, subscription.backwardsCallCount)
                assertFalse("rows that arrived release the block", controller.automaticOlderPagingBlocked)
            }
        }

    /** A deadline on an automatic older page keeps arming the visible retry row rather than the quiet guard. */
    @Test
    fun automaticOlderDeadlineStillArmsTheRetryRow() =
        runBlocking {
            val subscription = subscriptionWith(outcome(ConversationWindowUnchangedReason.TIMED_OUT))
            withController(subscription) { controller ->
                settle()

                val load = controller.loadOlderPageInternal(origin = ConversationPagingOrigin.AUTOMATIC)

                assertEquals(ConversationPageLoad.TIMED_OUT, load)
                assertTrue(controller.olderPageBlocked)
                assertFalse(controller.automaticOlderPagingBlocked)
            }
        }

    /** An authoritative live window is recovery for the older prefetch too. */
    @Test
    fun liveWindowReplacementReleasesTheOlderPrefetch() =
        runBlocking {
            val subscription = subscriptionWith(sameWindow())
            withController(subscription) { controller ->
                settle()
                controller.loadOlderPageInternal(origin = ConversationPagingOrigin.AUTOMATIC)
                assertTrue(controller.automaticOlderPagingBlocked)

                awaitConversationCondition { subscription.nextWindowCallCount >= 1 }
                subscription.emitWindow(page(listOf(record(OLDER_ID)), hasMoreBefore = true))
                awaitConversationCondition { subscription.nextWindowCallCount >= 2 }
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))

                awaitConversationCondition(timeoutMs = 15_000) { !controller.automaticOlderPagingBlocked }
                assertFalse(controller.automaticOlderPagingBlocked)
            }
        }

    /**
     * Every phase a history page can emit is part of the closed WNPerf vocabulary, so a diagnostics
     * session on a tester's device cannot be asked to log a name the schema does not define.
     */
    @Test
    fun historyPagePhasesAreInTheClosedVocabulary() {
        val phases =
            listOf(
                PerformancePhase.PAGE_ANCHOR,
                PerformancePhase.PAGE_WINDOW,
                PerformancePhase.PAGE_APPLY,
                PerformancePhase.PAGE_COMPLETE,
            )

        assertTrue(phases.all { it in PerformancePhase.entries })
        assertEquals(
            listOf("page_anchor", "page_window", "page_apply", "page_complete"),
            phases.map { it.wireName },
        )
        assertEquals("chat_history_page", PerformanceOperation.CHAT_HISTORY_PAGE.wireName)
    }

    /** Builds a subscription seeded with one row that still has older history behind it. */
    private fun subscriptionWith(vararg outcomes: TimelinePageOutcome) =
        ScriptedConversationTimelineSubscription(
            snapshotPage = page(listOf(record(SEED_ID, timelineAt = 200uL)), hasMoreBefore = true),
            backwardsOutcomes = outcomes.toMutableList(),
        )

    /** One older row arriving as a newly installed window. */
    private fun olderPage() = TimelinePageOutcome.Advanced(page(listOf(record(OLDER_ID)), hasMoreBefore = true))

    /** The window the handle already holds, answered again with older history still claimed. */
    private fun sameWindow(): TimelinePageOutcome {
        val heldRows = listOf(record(SEED_ID, timelineAt = 200uL))
        return TimelinePageOutcome.Advanced(page(heldRows, hasMoreBefore = true))
    }

    /** An unchanged outcome that keeps whatever window the handle already holds. */
    private fun outcome(reason: ConversationWindowUnchangedReason) = TimelinePageOutcome.Unchanged(reason, null)

    /** Builds an authoritative page with explicit pagination flags. */
    private fun page(
        messages: List<TimelineMessageRecordFfi>,
        hasMoreBefore: Boolean = false,
    ) = TimelinePageFfi(messages = messages, hasMoreBefore = hasMoreBefore, hasMoreAfter = false)

    /** A plain authoritative chat row, ordered by its own position in history. */
    private fun record(
        messageId: String,
        timelineAt: ULong = 100uL,
    ) = timelineRecord(messageId = messageId, timelineAt = timelineAt)

    /** Drains the main looper so controller startup and Compose state settle. */
    private fun settle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Owns a controller around one scripted subscription and tears it down afterwards. */
    private suspend fun withController(
        subscription: ScriptedConversationTimelineSubscription,
        block: suspend (ConversationController) -> Unit,
    ) {
        val scripted =
            ScriptedConversationLiveSubscriptions(
                timelineScripts = listOf(subscription),
                group = conversationTimelineTestGroup(),
            )
        val controller =
            ConversationController(
                appState = conversationTimelineTestAppState(scripted.subscriptions),
                initialGroup = conversationTimelineTestGroup(),
                initialMemberSnapshot = conversationTimelineMemberSnapshot(),
                groupRosterReader = { _, _ -> conversationTimelineGroupRoster() },
                startOnConstruction = true,
            )
        try {
            // Paging needs both the opening window and its active subscription.
            awaitConversationCondition {
                controller.timelineSubscription === subscription && controller.hasMoreBefore
            }
            block(controller)
        } finally {
            controller.onCleared()
            awaitOpenedTimelineSubscriptionsClosed(scripted)
        }
    }

    private companion object {
        val SEED_ID = "aa".repeat(32)
        val OLDER_ID = "bb".repeat(32)
        val TARGET_ID = "cc".repeat(32)
        val ANCHOR_THEN_PAGE = setOf("setVisibleAnchor", "paginateBackwards")
    }
}
