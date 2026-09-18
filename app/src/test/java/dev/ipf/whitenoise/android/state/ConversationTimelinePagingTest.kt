package dev.ipf.whitenoise.android.state

import android.os.Looper
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelinePageFfi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

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

    /** Builds a subscription seeded with one row that still has older history behind it. */
    private fun subscriptionWith(vararg outcomes: TimelinePageOutcome) =
        ScriptedConversationTimelineSubscription(
            snapshotPage = page(listOf(record(SEED_ID, timelineAt = 200uL)), hasMoreBefore = true),
            backwardsOutcomes = outcomes.toMutableList(),
        )

    /** One older row arriving as a newly installed window. */
    private fun olderPage() = TimelinePageOutcome.Advanced(page(listOf(record(OLDER_ID)), hasMoreBefore = true))

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
            // Apply the opening window here rather than waiting on subscription startup: under the
            // full suite that race leaves hasMoreBefore false and every page reports no progress.
            settle()
            controller.applyTimelinePage(
                page(listOf(record(SEED_ID, timelineAt = 200uL)), hasMoreBefore = true),
                replaceWindow = true,
                updatePagination = true,
            )
            check(controller.hasMoreBefore) { "the seeded window must still have older history" }
            block(controller)
        } finally {
            controller.onCleared()
            awaitOpenedTimelineSubscriptionsClosed(scripted)
        }
    }

    private companion object {
        val SEED_ID = "aa".repeat(32)
        val OLDER_ID = "bb".repeat(32)
        val ANCHOR_THEN_PAGE = setOf("setVisibleAnchor", "paginateBackwards")
    }
}
