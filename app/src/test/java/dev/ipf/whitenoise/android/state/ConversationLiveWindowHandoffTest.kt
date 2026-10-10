package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.GroupRosterFfi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Locally projected replacements cannot wait behind independent roster enrichment. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ConversationLiveWindowHandoffTest {
    /** A stopped/retained controller consumes a live window even while its roster read is held. */
    @Test
    fun liveWindowPublishesWhileTheOpeningRosterIsStillPending() =
        runBlocking {
            val rosterStarted = CompletableDeferred<Unit>()
            val rosterReply = CompletableDeferred<GroupRosterFfi>()
            val messageA = timelineRecord(ConversationTimelineTestIds.MESSAGE_A, 1uL)
            val messageB = timelineRecord(ConversationTimelineTestIds.MESSAGE_B, 2uL)
            val timeline = ScriptedConversationTimelineSubscription(timelinePage(messageA))
            val scripts =
                ScriptedConversationLiveSubscriptions(
                    timelineScripts = listOf(timeline),
                    group = conversationTimelineTestGroup(),
                )
            val controller =
                ConversationController(
                    appState = conversationTimelineTestAppState(scripts.subscriptions),
                    initialGroup = conversationTimelineTestGroup(),
                    initialMemberSnapshot = conversationTimelineMemberSnapshot(),
                    groupRosterReader = { _, _ ->
                        rosterStarted.complete(Unit)
                        rosterReply.await()
                    },
                    startOnConstruction = true,
                )
            try {
                awaitConversationCondition {
                    rosterStarted.isCompleted &&
                        ConversationTimelineTestIds.MESSAGE_A in timelineMessageIds(controller)
                }
                timeline.emitWindow(timelinePage(messageA, messageB))

                awaitConversationCondition {
                    ConversationTimelineTestIds.MESSAGE_B in timelineMessageIds(controller)
                }
                assertFalse("the live replacement must not release the roster read", rosterReply.isCompleted)
                assertEquals(
                    listOf(ConversationTimelineTestIds.MESSAGE_A, ConversationTimelineTestIds.MESSAGE_B),
                    timelineMessageIds(controller),
                )
                assertEquals(
                    "no foreground reopen or account catch-up is needed",
                    1,
                    scripts.timelineSubscriptionOpenCount,
                )
            } finally {
                rosterReply.complete(conversationTimelineGroupRoster())
                controller.onCleared()
                awaitOpenedTimelineSubscriptionsClosed(scripts)
            }
        }

    /** Early live consumption does not bypass an unverified notification transcript's roster gate. */
    @Test
    fun pendingRosterStillGatesDisclosureWhileLiveWindowsAreConsumed() =
        runBlocking {
            val rosterReply = CompletableDeferred<GroupRosterFfi>()
            val messageA = timelineRecord(ConversationTimelineTestIds.MESSAGE_A, 1uL)
            val messageB = timelineRecord(ConversationTimelineTestIds.MESSAGE_B, 2uL)
            val timeline = ScriptedConversationTimelineSubscription(timelinePage(messageA))
            val scripts =
                ScriptedConversationLiveSubscriptions(
                    timelineScripts = listOf(timeline),
                    group = conversationTimelineTestGroup(),
                )
            val controller =
                ConversationController(
                    appState = conversationTimelineTestAppState(scripts.subscriptions),
                    initialGroup = conversationTimelineTestGroup(),
                    initialChatListRow = notificationChatListRow(),
                    groupRosterReader = { _, _ -> rosterReply.await() },
                    startOnConstruction = true,
                )
            try {
                awaitConversationCondition { timeline.nextWindowCallCount > 0 }
                timeline.emitWindow(timelinePage(messageA, messageB))
                awaitConversationCondition {
                    ConversationTimelineTestIds.MESSAGE_B in timelineMessageIds(controller)
                }
                assertFalse(controller.membersVerified)
                assertFalse(
                    "consumption is not permission to render a transcript",
                    controller.hasKnownTranscriptPresentation,
                )

                rosterReply.complete(conversationTimelineGroupRoster())
                awaitConversationCondition { controller.membersVerified }
            } finally {
                rosterReply.complete(conversationTimelineGroupRoster())
                controller.onCleared()
                awaitOpenedTimelineSubscriptionsClosed(scripts)
            }
        }
}
