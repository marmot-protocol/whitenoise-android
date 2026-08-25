package dev.ipf.whitenoise.android.state

import android.os.Looper
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.marmotkit.TimelineSubscriptionUpdateFfi
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
 * Regression for #2233: the production subscription retry loop must apply each
 * replacement [ConversationTimelineSubscriptionHandle] snapshot even when the bounded
 * window was retained from the prior attempt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ConversationTimelineReconnectIntegrationTest {
    @Test
    fun replacementSubscriptionSnapshotReconcilesGapMessageWithoutLaterDelta() =
        runBlocking {
            val fixtures = conversationTimelineReconnectFixtures()
            val controller = conversationController(fixtures.scriptedSubscriptions.subscriptions)
            try {
                awaitConversationCondition {
                    fixtures.firstSubscription.snapshotCallCount == 1 &&
                        ConversationTimelineTestIds.MESSAGE_A in timelineMessageIds(controller)
                }
                assertEquals(1, fixtures.scriptedSubscriptions.timelineSubscriptionOpenCount)
                assertFalse(ConversationTimelineTestIds.MESSAGE_B in timelineMessageIds(controller))

                awaitConversationCondition { fixtures.firstSubscription.nextUpdateCallCount == 1 }
                assertTimelineSubscriptionSnapshotBeforeFirstNextUpdate(fixtures.firstSubscription)

                fixtures.firstSubscription.endUpdates()
                awaitConversationCondition { fixtures.firstSubscription.closeCallCount == 1 }
                controller.retryLoadFailure()

                awaitConversationCondition {
                    fixtures.scriptedSubscriptions.timelineSubscriptionOpenCount == 2 &&
                        ConversationTimelineTestIds.MESSAGE_B in timelineMessageIds(controller)
                }
                awaitConversationCondition { fixtures.replacementSubscription.nextUpdateCallCount >= 1 }
                assertTimelineSubscriptionSnapshotBeforeFirstNextUpdate(fixtures.replacementSubscription)
            } finally {
                controller.onCleared()
                awaitOpenedTimelineSubscriptionsClosed(fixtures.scriptedSubscriptions)
            }
        }

    @Test
    fun replacementSnapshotResetsPaginationModeForLaterAuthoritativeRefresh() =
        runBlocking {
            val oldMessage = timelineRecord(MESSAGE_OLD, 0uL)
            val messageA = timelineRecord(ConversationTimelineTestIds.MESSAGE_A, 1uL)
            val messageB = timelineRecord(ConversationTimelineTestIds.MESSAGE_B, 2uL)
            val firstSubscription =
                ScriptedConversationTimelineSubscription(
                    snapshotPage = timelinePageWithFlags(listOf(messageA), hasMoreBefore = true),
                    backwardsPage = timelinePageWithFlags(listOf(oldMessage, messageA), hasMoreAfter = true),
                )
            val replacementSubscription =
                ScriptedConversationTimelineSubscription(
                    snapshotPage = timelinePageWithFlags(listOf(messageA, messageB), hasMoreBefore = true),
                )
            val scriptedSubscriptions =
                ScriptedConversationLiveSubscriptions(
                    timelineScripts = listOf(firstSubscription, replacementSubscription),
                    group = conversationTimelineTestGroup(),
                )
            val controller = conversationController(scriptedSubscriptions.subscriptions)
            try {
                awaitConversationCondition { controller.hasMoreBefore }
                assertTrue(controller.loadOlderTimelinePage())
                assertEquals(
                    listOf(MESSAGE_OLD, ConversationTimelineTestIds.MESSAGE_A),
                    timelineMessageIds(controller),
                )

                awaitConversationCondition { firstSubscription.nextUpdateCallCount == 1 }
                firstSubscription.endUpdates()
                awaitConversationCondition { firstSubscription.closeCallCount == 1 }
                controller.retryLoadFailure()
                awaitConversationCondition {
                    scriptedSubscriptions.timelineSubscriptionOpenCount == 2 &&
                        ConversationTimelineTestIds.MESSAGE_B in timelineMessageIds(controller)
                }

                replacementSubscription.emitUpdate(
                    TimelineSubscriptionUpdateFfi.Page(
                        timelinePageWithFlags(listOf(messageB), hasMoreBefore = false),
                    ),
                )
                awaitConversationCondition { replacementSubscription.nextUpdateCallCount >= 2 }
                awaitConversationCondition {
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
                    !controller.hasMoreBefore
                }
                assertEquals(
                    listOf(ConversationTimelineTestIds.MESSAGE_B),
                    timelineMessageIds(controller),
                )
                assertFalse(controller.hasMoreBefore)
            } finally {
                controller.onCleared()
                awaitOpenedTimelineSubscriptionsClosed(scriptedSubscriptions)
            }
        }

    private fun timelinePageWithFlags(
        messages: List<dev.ipf.marmotkit.TimelineMessageRecordFfi>,
        hasMoreBefore: Boolean = false,
        hasMoreAfter: Boolean = false,
    ) = TimelinePageFfi(
        messages = messages,
        hasMoreBefore = hasMoreBefore,
        hasMoreAfter = hasMoreAfter,
    )

    private fun conversationController(subscriptions: ConversationLiveSubscriptions) =
        ConversationController(
            appState = conversationTimelineTestAppState(subscriptions),
            initialGroup = conversationTimelineTestGroup(),
            initialMemberSnapshot = conversationTimelineMemberSnapshot(),
            groupRosterReader = { _, _ -> conversationTimelineGroupRoster() },
            startOnConstruction = true,
        )

    private companion object {
        val MESSAGE_OLD = "d0".repeat(32)
    }
}
