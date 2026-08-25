package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
            val appState = conversationTimelineTestAppState(fixtures.scriptedSubscriptions.subscriptions)
            val controller =
                ConversationController(
                    appState = appState,
                    initialGroup = conversationTimelineTestGroup(),
                    initialMemberSnapshot = conversationTimelineMemberSnapshot(),
                    groupRosterReader = { _, _ -> conversationTimelineGroupRoster() },
                    startOnConstruction = true,
                )

            awaitConversationCondition {
                fixtures.firstSubscription.snapshotCallCount == 1 &&
                    ConversationTimelineTestIds.MESSAGE_A in timelineMessageIds(controller)
            }
            assertEquals(1, fixtures.scriptedSubscriptions.timelineSubscriptionOpenCount)
            assertFalse(ConversationTimelineTestIds.MESSAGE_B in timelineMessageIds(controller))

            awaitConversationCondition { fixtures.firstSubscription.nextUpdateCallCount == 1 }
            assertTimelineSubscriptionSnapshotBeforeFirstNextUpdate(fixtures.firstSubscription)

            controller.retryLoadFailure()

            awaitConversationCondition {
                fixtures.scriptedSubscriptions.timelineSubscriptionOpenCount == 2 &&
                    ConversationTimelineTestIds.MESSAGE_B in timelineMessageIds(controller)
            }
            awaitConversationCondition { fixtures.replacementSubscription.nextUpdateCallCount >= 1 }
            assertTimelineSubscriptionSnapshotBeforeFirstNextUpdate(fixtures.replacementSubscription)
        }
}
