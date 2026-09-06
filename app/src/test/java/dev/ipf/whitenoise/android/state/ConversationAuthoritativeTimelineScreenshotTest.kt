package dev.ipf.whitenoise.android.state

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.GroupSystemEventFfi
import dev.ipf.whitenoise.android.ui.conversation.CONVERSATION_TIMELINE_TAIL_GAP
import dev.ipf.whitenoise.android.ui.conversation.ConversationScreen
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubbleRowTestTag
import dev.ipf.whitenoise.android.ui.testing.PerformanceTestTags
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone

/** Compose-level proof that authoritative and unresolved-local row order reaches visible rows. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ConversationAuthoritativeTimelineScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Keeps the projected order and one tail interval above the composer. */
    @Test
    fun oldUnconfirmedRowRendersBeforeTheAuthoritativePairWithATightTailGap() {
        val fixture = screenshotFixture()
        val originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            awaitConversationCondition { fixture.controller.timeline.size == 3 }
            showConversation(fixture)
            assertRowsAndCapture()
        } finally {
            TimeZone.setDefault(originalTimeZone)
            fixture.controller.onCleared()
            awaitOpenedTimelineSubscriptionsClosed(fixture.scripted)
        }
    }

    /** Creates the window subscription and realistic entry projection needed by the reveal gate. */
    private fun screenshotFixture(): ScreenshotFixture {
        val subscription =
            ScriptedConversationTimelineSubscription(
                // MDK returns unresolved local rows in its trailing optimistic
                // bucket. Android must merge this old row chronologically while
                // retaining the authoritative membership/application pair.
                timelinePage(membershipRecord(), appRecord(), unconfirmedLocalRecord()),
            )
        val scripted =
            ScriptedConversationLiveSubscriptions(
                timelineScripts = listOf(subscription),
                group = conversationTimelineTestGroup(),
            )
        val entryProjection =
            notificationChatListRow().copy(
                lastMessage = null,
                unreadCount = 0uL,
                hasUnread = false,
                firstUnreadMessageIdHex = null,
                lastReadMessageIdHex = APP_MESSAGE_ID,
                lastReadTimelineAt = 100uL,
            )
        val controller =
            ConversationController(
                appState = conversationTimelineTestAppState(scripted.subscriptions),
                initialGroup = conversationTimelineTestGroup(),
                initialMemberSnapshot = conversationTimelineMemberSnapshot(),
                initialChatListRow = entryProjection,
                groupRosterReader = { _, _ -> conversationTimelineGroupRoster() },
                startOnConstruction = true,
            )
        val chat =
            ChatListItem(
                group = conversationTimelineTestGroup(),
                latest = null,
                otherMemberAccount = null,
                memberCount = 1,
                memberSnapshot = conversationTimelineMemberSnapshot(),
                projection = entryProjection,
            )
        return ScreenshotFixture(controller, scripted, chat)
    }

    /** Renders the conversation and waits for its production initial-anchor reveal. */
    private fun showConversation(fixture: ScreenshotFixture) {
        composeRule.setContent {
            WhiteNoiseTheme {
                ConversationScreen(
                    appState = fixture.controller.appState,
                    chat = fixture.chat,
                    controller = fixture.controller,
                    onBack = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule
                .onAllNodesWithTag(PerformanceTestTags.CONVERSATION_TRANSCRIPT_VISIBLE)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    /** Confirms the local row and authoritative pair are visible in display order. */
    private fun assertRowsAndCapture() {
        val unconfirmedRow = composeRule.onNodeWithText("old unconfirmed send")
        val systemRow = composeRule.onNodeWithText("You added", substring = true)
        val appRow = composeRule.onNodeWithText("body-$APP_MESSAGE_ID")
        appRow.performScrollTo()
        composeRule.waitForIdle()
        unconfirmedRow.assertIsDisplayed()
        systemRow.assertIsDisplayed()
        appRow.assertIsDisplayed()
        val unconfirmedTop = unconfirmedRow.fetchSemanticsNode().boundsInRoot.top
        val systemTop = systemRow.fetchSemanticsNode().boundsInRoot.top
        val appTop = appRow.fetchSemanticsNode().boundsInRoot.top
        assertTrue("old unconfirmed row must not occupy the live head", unconfirmedTop < systemTop)
        assertTrue("membership row must render above the authorized app message", systemTop < appTop)
        val transcriptBottom =
            composeRule
                .onNodeWithTag(PerformanceTestTags.CONVERSATION_TRANSCRIPT_VISIBLE)
                .fetchSemanticsNode()
                .boundsInRoot.bottom
        val tailBottom =
            composeRule
                .onNodeWithTag(messageBubbleRowTestTag(APP_MESSAGE_ID), useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot.bottom
        assertEquals(
            "the final message must have exactly one 8dp interval above the composer",
            with(composeRule.density) { CONVERSATION_TIMELINE_TAIL_GAP.toPx() },
            transcriptBottom - tailBottom,
            1f,
        )
        composeRule
            .onRoot()
            .captureRoboImage("src/test/snapshots/conversation_authoritative_timeline_order_light.png")
    }

    /** Builds the later-timestamp membership event that anchors the authoritative pair. */
    private fun membershipRecord() =
        timelineRecord(
            messageId = SYSTEM_MESSAGE_ID,
            timelineAt = 200uL,
            plaintext = "member added",
        ).copy(
            sourceMessageIdHex = null,
            direction = "system",
            kind = 1210uL,
            sourceEpoch = SOURCE_EPOCH,
            groupSystem =
                GroupSystemEventFfi(
                    systemType = "member_added",
                    text = "member added",
                    actorAccountIdHex = ConversationTimelineTestIds.ACCOUNT_ID,
                    subjectAccountIdHex = ConversationTimelineTestIds.SENDER_ID,
                    name = null,
                    oldName = null,
                    oldRetentionSeconds = null,
                    newRetentionSeconds = null,
                ),
        )

    /** Builds the earlier-timestamp app message that MDK ranks after membership. */
    private fun appRecord() =
        timelineRecord(
            messageId = APP_MESSAGE_ID,
            timelineAt = 100uL,
        ).copy(sourceEpoch = SOURCE_EPOCH)

    /** Builds a failed send inside the authoritative timestamp inversion range. */
    private fun unconfirmedLocalRecord() =
        timelineRecord(
            messageId = UNCONFIRMED_MESSAGE_ID,
            timelineAt = 150uL,
            plaintext = "old unconfirmed send",
        ).copy(
            sourceMessageIdHex = null,
            direction = "sent",
            sender = ConversationTimelineTestIds.ACCOUNT_ID,
            invalidationStatus = "local_publish_failed",
        )

    /** Values shared by the render and cleanup phases of one screenshot assertion. */
    private data class ScreenshotFixture(
        val controller: ConversationController,
        val scripted: ScriptedConversationLiveSubscriptions,
        val chat: ChatListItem,
    )

    private companion object {
        const val SOURCE_EPOCH = 7uL
        val SYSTEM_MESSAGE_ID = "ff".repeat(32)
        val APP_MESSAGE_ID = "00".repeat(32)
        val UNCONFIRMED_MESSAGE_ID = "33".repeat(32)
    }
}
