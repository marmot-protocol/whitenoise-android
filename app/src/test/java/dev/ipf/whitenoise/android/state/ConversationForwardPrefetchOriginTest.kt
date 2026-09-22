package dev.ipf.whitenoise.android.state

import android.os.Looper
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.whitenoise.android.ui.conversation.shouldPrefetchNewer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Opportunistic forward prefetch — what a successful send triggers by landing the viewport on the
 * newest edge — recovers silently, while deliberate newer-page navigation keeps its retry (#2764).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ConversationForwardPrefetchOriginTest {
    /** A thrown automatic forward page leaves the reader nothing to see and nothing to retry. */
    @Test
    fun automaticForwardFailureIsNotShownToTheReader() =
        runBlocking {
            val subscription = subscriptionWith(forwardsOutcomes = mutableListOf(deadline()))
            withController(subscription) { controller ->
                val load = controller.loadNewerPageInternal(ConversationPagingOrigin.AUTOMATIC)

                assertEquals(ConversationPageLoad.TIMED_OUT, load)
                assertNull("an opportunistic page must not report a user-visible failure", controller.pageError)
                assertFalse(controller.isLoadingOlder)
            }
        }

    /** A window that stays not-ready is also silent, and its retries stay bounded. */
    @Test
    fun repeatedAutomaticFailuresStopAfterTheRecoveryBudget() =
        runBlocking {
            val stuck = MutableList<TimelinePageOutcome>(FORWARD_SCRIPT_SIZE) { notReady() }
            val subscription = subscriptionWith(forwardsOutcomes = stuck)
            withController(subscription) { controller ->
                repeat(CONVERSATION_AUTOMATIC_NEWER_PAGE_ATTEMPTS + 2) {
                    controller.loadNewerPageInternal(ConversationPagingOrigin.AUTOMATIC)
                }

                assertNull(controller.pageError)
                assertTrue(controller.automaticNewerPagingBlocked)
                assertEquals(
                    "a blocked prefetch must stop asking the engine",
                    CONVERSATION_AUTOMATIC_NEWER_PAGE_ATTEMPTS * CONVERSATION_PAGE_NOT_READY_ATTEMPTS,
                    subscription.forwardsCallCount,
                )
            }
        }

    /** The same failure, asked for deliberately, stays visible and retryable. */
    @Test
    fun explicitForwardFailureKeepsItsRetryAffordance() =
        runBlocking {
            val subscription = subscriptionWith(forwardsOutcomes = mutableListOf(deadline()))
            withController(subscription) { controller ->
                val load = controller.loadNewerPageInternal(ConversationPagingOrigin.EXPLICIT)

                assertEquals(ConversationPageLoad.TIMED_OUT, load)
                assertNotNull("deliberate navigation keeps its bottom-edge retry", controller.pageError)
                assertTrue(controller.pageError?.retryable == true)
            }
        }

    /** A blocked prefetch never stands in the way of the reader's own retry. */
    @Test
    fun explicitRetryProceedsWhileAutomaticPrefetchIsBlocked() =
        runBlocking {
            val stuck = MutableList<TimelinePageOutcome>(FORWARD_SCRIPT_SIZE) { notReady() }
            val subscription = subscriptionWith(forwardsOutcomes = stuck)
            withController(subscription) { controller ->
                repeat(CONVERSATION_AUTOMATIC_NEWER_PAGE_ATTEMPTS) {
                    controller.loadNewerPageInternal(ConversationPagingOrigin.AUTOMATIC)
                }
                assertTrue(controller.automaticNewerPagingBlocked)
                val callsBefore = subscription.forwardsCallCount

                controller.loadNewerPageInternal(ConversationPagingOrigin.EXPLICIT)

                assertTrue("an explicit page proceeds immediately", subscription.forwardsCallCount > callsBefore)
                assertNotNull(controller.pageError)
            }
        }

    /** A page that advances releases the block and clears only the newer-page failure. */
    @Test
    fun recoveryReleasesTheBlockAndClearsOnlyTheMatchingFailure() =
        runBlocking {
            val subscription =
                subscriptionWith(
                    forwardsOutcomes = mutableListOf(deadline(), TimelinePageOutcome.Advanced(newerPage())),
                )
            withController(subscription) { controller ->
                controller.loadNewerPageInternal(ConversationPagingOrigin.EXPLICIT)
                assertNotNull(controller.pageError)

                val load = controller.loadNewerPageInternal(ConversationPagingOrigin.AUTOMATIC)

                assertEquals(ConversationPageLoad.ADVANCED, load)
                assertNull(controller.pageError)
                assertFalse(controller.automaticNewerPagingBlocked)
            }
        }

    /** An older-page failure the reader can still retry survives a forward recovery. */
    @Test
    fun forwardRecoveryLeavesAnOlderPageFailureAlone() =
        runBlocking {
            val subscription =
                subscriptionWith(
                    backwardsOutcomes = mutableListOf(deadline()),
                    forwardsOutcomes = mutableListOf(TimelinePageOutcome.Advanced(newerPage())),
                )
            withController(subscription) { controller ->
                controller.loadOlderPageInternal()
                assertTrue(controller.olderPageBlocked)

                controller.loadNewerPageInternal(ConversationPagingOrigin.AUTOMATIC)

                assertTrue("an older-page retry row is still the reader's to act on", controller.olderPageBlocked)
            }
        }

    /** An authoritative live window is recovery too, so the reader never has to act. */
    @Test
    fun liveWindowReplacementReleasesTheBlock() =
        runBlocking {
            val stuck = MutableList<TimelinePageOutcome>(FORWARD_SCRIPT_SIZE) { notReady() }
            val subscription = subscriptionWith(forwardsOutcomes = stuck)
            withController(subscription) { controller ->
                repeat(CONVERSATION_AUTOMATIC_NEWER_PAGE_ATTEMPTS) {
                    controller.loadNewerPageInternal(ConversationPagingOrigin.AUTOMATIC)
                }
                assertTrue(controller.automaticNewerPagingBlocked)

                awaitConversationCondition { subscription.nextWindowCallCount >= 1 }
                subscription.emitWindow(newerPage())
                awaitConversationCondition { subscription.nextWindowCallCount >= 2 }
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))

                awaitConversationCondition(timeoutMs = 15_000) { !controller.automaticNewerPagingBlocked }
                assertFalse(controller.automaticNewerPagingBlocked)
            }
        }

    /** The viewport rule stands the prefetch down while it is blocked, and resumes once it clears. */
    @Test
    fun prefetchRuleStopsAtTheEdgeWhileBlocked() {
        assertTrue(prefetchNewer(blocked = false))
        assertFalse(prefetchNewer(blocked = true))
        assertFalse("a page already in flight is never re-issued", prefetchNewer(isLoadingOlder = true))
        assertFalse("nothing newer to fetch", prefetchNewer(hasMoreAfter = false))
        assertFalse("an unanchored timeline never prefetches", prefetchNewer(anchored = false))
        assertFalse("a viewport away from the edge waits", prefetchNewer(newestVisibleIndex = 99))
    }

    /** Evaluates the forward-prefetch rule at the newest edge with one varied input. */
    private fun prefetchNewer(
        anchored: Boolean = true,
        hasMoreAfter: Boolean = true,
        isLoadingOlder: Boolean = false,
        blocked: Boolean = false,
        newestVisibleIndex: Int = 0,
    ) = shouldPrefetchNewer(
        anchored = anchored,
        hasMoreAfter = hasMoreAfter,
        isLoadingOlder = isLoadingOlder,
        newerPrefetchBlocked = blocked,
        newestVisibleIndex = newestVisibleIndex,
        newestEdgeIndex = 0,
    )

    /** A subscription whose window still has newer history ahead of it. */
    private fun subscriptionWith(
        backwardsOutcomes: MutableList<TimelinePageOutcome> = mutableListOf(),
        forwardsOutcomes: MutableList<TimelinePageOutcome> = mutableListOf(),
    ) = ScriptedConversationTimelineSubscription(
        snapshotPage = page(listOf(record(SEED_ID, timelineAt = 200uL)), hasMoreAfter = true),
        backwardsOutcomes = backwardsOutcomes,
        forwardsOutcomes = forwardsOutcomes,
    )

    /** A window the engine never answered before its deadline. */
    private fun deadline() = TimelinePageOutcome.Unchanged(ConversationWindowUnchangedReason.TIMED_OUT, null)

    /** A window the engine is still repairing. */
    private fun notReady() = TimelinePageOutcome.Unchanged(ConversationWindowUnchangedReason.NOT_READY, null)

    /** One newer row arriving as a newly installed window. */
    private fun newerPage() = page(listOf(record(NEWER_ID, timelineAt = 300uL)), hasMoreAfter = true)

    /** Builds an authoritative page with explicit pagination flags. */
    private fun page(
        messages: List<TimelineMessageRecordFfi>,
        hasMoreAfter: Boolean = false,
    ) = TimelinePageFfi(messages = messages, hasMoreBefore = true, hasMoreAfter = hasMoreAfter)

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
            awaitConversationCondition {
                controller.timelineSubscription === subscription && controller.hasMoreAfterTimeline
            }
            settle()
            block(controller)
        } finally {
            controller.onCleared()
            awaitOpenedTimelineSubscriptionsClosed(scripted)
        }
    }

    private companion object {
        val SEED_ID = "aa".repeat(32)
        val NEWER_ID = "cc".repeat(32)

        /** Enough scripted not-ready answers to outlast every attempt in the recovery budget. */
        const val FORWARD_SCRIPT_SIZE = 64
    }
}
