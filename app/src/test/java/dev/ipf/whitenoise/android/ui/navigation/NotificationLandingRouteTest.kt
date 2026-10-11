package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import dev.ipf.whitenoise.android.notifications.NotificationScenario
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
import dev.ipf.whitenoise.android.state.ScriptedConversationLiveSubscriptions
import dev.ipf.whitenoise.android.state.ScriptedConversationTimelineSubscription
import dev.ipf.whitenoise.android.state.awaitConversationCondition
import dev.ipf.whitenoise.android.state.awaitOpenedTimelineSubscriptionsClosed
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.state.timelinePage
import dev.ipf.whitenoise.android.state.timelineRecord
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubbleRowTestTag
import dev.ipf.whitenoise.android.ui.conversation.toLandingTarget
import dev.ipf.whitenoise.android.ui.testing.PerformanceTestTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The whole conversation surface for a message-card tap: the production screen, controller, entry
 * snapshot and scroll owner, measured by the physical top edge of the notified bubble. The notified
 * message is never the oldest unread and never the newest, so landing on either cannot pass.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class NotificationLandingRouteTest : NotificationRouteTimelinePresentationFixture() {
    /** A short notified message in the middle of the backlog starts at the transcript's reading top. */
    @Test
    fun shortNotifiedMessageStartsAtTheReadingTop() {
        verifyLanding(LandingCase(bodyLines = 1))
    }

    /** A message several screens tall starts at its first line, where the old zero offset showed its end. */
    @Test
    fun viewportTallerNotifiedMessageStartsAtItsBeginning() {
        verifyLanding(LandingCase(bodyLines = TALL_BODY_LINES))
    }

    /** A keyboard-sized viewport changes the reading height, not where the beginning must sit. */
    @Test
    @Config(sdk = [36], qualifiers = "en-w360dp-h420dp-mdpi")
    fun reducedViewportStartsAtTheReadingTop() {
        verifyLanding(LandingCase(bodyLines = TALL_BODY_LINES))
    }

    /** Large text and right-to-left layout reflow the bubble, and the landing still follows its beginning. */
    @Test
    fun largeTextRtlStartsAtTheReadingTop() {
        val appearance = DirectNotificationAppearance(rtl = true, fontScale = 1.6f)
        verifyLanding(LandingCase(bodyLines = TALL_BODY_LINES / 2, appearance = appearance))
    }

    /** One case: the body size and appearance the notified message is rendered with. */
    private data class LandingCase(
        val bodyLines: Int,
        val appearance: DirectNotificationAppearance = DirectNotificationAppearance(),
    )

    /** Mounts the real screen for the card's message and asserts where the notified bubble's top edge ends up. */
    private fun verifyLanding(case: LandingCase) {
        val harness = DirectNotificationConversationHarness(composeRule)
        val timeline = ScriptedConversationTimelineSubscription(page(case.bodyLines))
        val scripted = ScriptedConversationLiveSubscriptions(listOf(timeline), conversationTimelineTestGroup())
        val row =
            NotificationScenario.chatListRow(
                groupIdHex = ConversationTimelineTestIds.GROUP_ID,
                unreadCount = UNREAD_COUNT,
                firstUnreadMessageIdHex = NotificationScenario.OLDEST_UNREAD_ID,
            )
        val fixture = harness.create(scripted.subscriptions, row)
        val mounted = mutableStateOf(true)
        val landing =
            NotificationScenario
                .target(
                    accountRef = ConversationTimelineTestIds.ACCOUNT_REF,
                    groupIdHex = ConversationTimelineTestIds.GROUP_ID,
                    messageIdHex = NotificationScenario.NOTIFIED_ID,
                ).toLandingTarget()
        try {
            awaitConversationCondition {
                fixture.controller.hasPublishedAuthoritativeTimeline && !fixture.controller.isLoading
            }
            harness.mount(
                fixture,
                mounted,
                notificationOpenRequestId = { REQUEST_ID },
                landingTarget = landing,
                appearance = case.appearance,
            )
            awaitCondition(failureMessage = { "the transcript never revealed" }) { transcriptRevealed() }
            composeRule.waitForIdle()
            assertNotifiedBubbleAtReadingTop()
        } finally {
            try {
                harness.dispose(fixture, mounted)
            } finally {
                awaitOpenedTimelineSubscriptionsClosed(scripted)
            }
        }
    }

    /** True once the screen has committed its initial position and revealed the transcript. */
    private fun transcriptRevealed(): Boolean =
        composeRule
            .onAllNodesWithTag(PerformanceTestTags.CONVERSATION_TRANSCRIPT_VISIBLE)
            .fetchSemanticsNodes()
            .isNotEmpty()

    /** The notified bubble starts at the reading top, and neither the oldest unread nor the newest row does. */
    private fun assertNotifiedBubbleAtReadingTop() {
        val transcriptTop =
            composeRule
                .onNodeWithTag(PerformanceTestTags.CONVERSATION_TRANSCRIPT_VISIBLE)
                .getUnclippedBoundsInRoot()
                .top.value
        val notifiedTop = bubbleTop(NotificationScenario.NOTIFIED_ID)
        assertEquals(
            "the notified bubble must start at the transcript's reading top",
            transcriptTop,
            notifiedTop,
            1f,
        )
        val oldestUnread = messageBubbleRowTestTag(NotificationScenario.OLDEST_UNREAD_ID)
        val composedOldestUnread =
            composeRule.onAllNodesWithTag(oldestUnread, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue(
            "the oldest unread starts above the reading top, if it is composed at all",
            composedOldestUnread.isEmpty() || bubbleTop(NotificationScenario.OLDEST_UNREAD_ID) < transcriptTop,
        )
    }

    /** The unclipped top edge of one message bubble row, so a row taller than the viewport is still measured. */
    private fun bubbleTop(messageId: String): Float =
        composeRule
            .onNodeWithTag(messageBubbleRowTestTag(messageId), useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
            .top.value

    /** Rows with the oldest unread, the notified message and the newest at three different places. */
    private fun page(bodyLines: Int) =
        timelinePage(
            *(0 until MESSAGE_COUNT)
                .map { index ->
                    val id = idAt(index)
                    val body =
                        if (id == NotificationScenario.NOTIFIED_ID) {
                            (1..bodyLines).joinToString("\n") { "notified line $it" }
                        } else {
                            "body $index"
                        }
                    timelineRecord(id, (index + 1).toULong(), plaintext = body)
                }.toTypedArray(),
        )

    /** The scenario's three named messages sit at fixed positions, everything else is filler. */
    private fun idAt(index: Int): String =
        when (index) {
            OLDEST_UNREAD_INDEX -> NotificationScenario.OLDEST_UNREAD_ID
            NOTIFIED_INDEX -> NotificationScenario.NOTIFIED_ID
            MESSAGE_COUNT - 1 -> NotificationScenario.NEWEST_ID
            else -> index.toString(16).padStart(2, '0').repeat(32)
        }

    private companion object {
        const val REQUEST_ID = 41L
        const val MESSAGE_COUNT = 30
        const val OLDEST_UNREAD_INDEX = 2
        const val NOTIFIED_INDEX = 6
        const val UNREAD_COUNT = 10
        const val TALL_BODY_LINES = 60
    }
}
