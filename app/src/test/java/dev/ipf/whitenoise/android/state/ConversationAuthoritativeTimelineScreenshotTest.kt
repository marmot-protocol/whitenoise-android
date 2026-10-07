package dev.ipf.whitenoise.android.state

import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.GroupRecoveryStatusFfi
import dev.ipf.marmotkit.GroupSystemEventFfi
import dev.ipf.marmotkit.GroupSystemEventProvenanceFfi
import dev.ipf.whitenoise.android.ui.conversation.CONVERSATION_BOTTOM_BAR_TAG
import dev.ipf.whitenoise.android.ui.conversation.CONVERSATION_TIMELINE_TAIL_GAP
import dev.ipf.whitenoise.android.ui.conversation.ConversationScreen
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubbleRowTestTag
import dev.ipf.whitenoise.android.ui.testing.PerformanceTestTags
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
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

    /** Automatic older-history timeouts keep the actual transcript visible, without red retry chrome. */
    @Test
    fun quietOlderTimeoutLight() = captureQuietOlderTimeout("light", dark = false)

    /** Pins quiet recovery against the dark conversation background. */
    @Test
    fun quietOlderTimeoutDark() = captureQuietOlderTimeout("dark", dark = true)

    /** The same recovery must not disturb reading order at RTL and 200% text. */
    @Test
    fun quietOlderTimeoutRtlLargeText() {
        captureQuietOlderTimeout("rtl_200", dark = false, fontScale = 2f, layoutDirection = LayoutDirection.Rtl)
    }

    /** Drives production prefetch into a timeout and captures the retained conversation. */
    private fun captureQuietOlderTimeout(
        suffix: String,
        dark: Boolean,
        fontScale: Float = 1f,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    ) {
        val fixture = screenshotFixture()
        val originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            awaitConversationCondition { fixture.controller.timeline.size == 3 }
            showConversation(fixture, dark, fontScale, layoutDirection)
            val row = composeRule.onNodeWithTag(messageBubbleRowTestTag(APP_MESSAGE_ID), useUnmergedTree = true)
            val topBefore = row.fetchSemanticsNode().boundsInRoot.top
            fixture.subscription.emitWindow(
                timelinePage(membershipRecord(), appRecord(), unconfirmedLocalRecord()).copy(hasMoreBefore = true),
            )
            awaitConversationCondition { fixture.subscription.nextWindowCallCount >= 2 }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            composeRule.waitUntil(5_000L) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
                fixture.controller.automaticOlderPagingBlocked
            }
            composeRule.runOnIdle {
                assertNull(fixture.controller.error)
                assertFalse(fixture.controller.isLoadingOlder)
                assertEquals(3, fixture.controller.timeline.size)
            }
            assertEquals(
                "quiet failure must not move the visible row",
                topBefore,
                row.fetchSemanticsNode().boundsInRoot.top,
                1f,
            )
            composeRule.onNodeWithText("Couldn't load more", substring = true).assertDoesNotExist()
            composeRule.onNodeWithText("Retry").assertDoesNotExist()
            composeRule.onRoot().captureRoboImage("src/test/snapshots/conversation_quiet_older_timeout_$suffix.png")
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
                backwardsOutcomes =
                    mutableListOf(TimelinePageOutcome.Unchanged(ConversationWindowUnchangedReason.TIMED_OUT, null)),
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
                groupRecoveryStatusReader = { _, groupIdHex ->
                    GroupRecoveryStatusFfi(
                        groupIdHex = groupIdHex,
                        automaticRecoveryFailed = false,
                        pendingReinvites = 0u,
                        failedReinvites = 0u,
                        rejoinInvitations = emptyList(),
                    )
                },
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
        return ScreenshotFixture(controller, scripted, chat, subscription)
    }

    /** Renders the conversation and waits for its production initial-anchor reveal. */
    private fun showConversation(
        fixture: ScreenshotFixture,
        dark: Boolean = false,
        fontScale: Float = 1f,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                WhiteNoiseTheme(darkTheme = dark, fontScale = fontScale) {
                    ConversationScreen(
                        appState = fixture.controller.appState,
                        chat = fixture.chat,
                        controller = fixture.controller,
                        onBack = {},
                    )
                }
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
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Wave hi").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Wave hi").assertIsDisplayed()
        appRow.assertIsDisplayed()
        val unconfirmedTop = unconfirmedRow.fetchSemanticsNode().boundsInRoot.top
        val systemTop = systemRow.fetchSemanticsNode().boundsInRoot.top
        val appTop = appRow.fetchSemanticsNode().boundsInRoot.top
        assertTrue("old unconfirmed row must not occupy the live head", unconfirmedTop < systemTop)
        assertTrue("membership row must render above the authorized app message", systemTop < appTop)
        val composerTop =
            composeRule
                .onNodeWithTag(CONVERSATION_BOTTOM_BAR_TAG)
                .fetchSemanticsNode()
                .boundsInRoot.top
        val tailBottom =
            composeRule
                .onNodeWithTag(messageBubbleRowTestTag(APP_MESSAGE_ID), useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot.bottom
        assertEquals(
            "the final message must have exactly one 8dp interval above the composer",
            with(composeRule.density) { CONVERSATION_TIMELINE_TAIL_GAP.toPx() },
            composerTop - tailBottom,
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
                    provenance = GroupSystemEventProvenanceFfi.AUTHENTICATED_GROUP_STATE,
                    actorDisplayName = null,
                    subjectDisplayName = null,
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
        val subscription: ScriptedConversationTimelineSubscription,
    )

    private companion object {
        const val SOURCE_EPOCH = 7uL
        val SYSTEM_MESSAGE_ID = "ff".repeat(32)
        val APP_MESSAGE_ID = "00".repeat(32)
        val UNCONFIRMED_MESSAGE_ID = "33".repeat(32)
    }
}
