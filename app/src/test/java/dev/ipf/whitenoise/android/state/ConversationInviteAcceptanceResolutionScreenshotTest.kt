package dev.ipf.whitenoise.android.state

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.conversation.ConversationScreen
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Production conversation coverage for the stale-invite authority-retry surface (#1248). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ConversationInviteAcceptanceResolutionScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private var renderedSurfaceColor = Color.Unspecified

    /** The rendered Retry re-reads membership without replaying the retired Join action. */
    @Test
    fun failedAuthorityReadOffersARealRetryThatNeverAcceptsAgain() {
        val rosterAvailable = AtomicBoolean(false)
        val acceptCalls = AtomicInteger(0)
        val group = pendingInviteGroup()
        val scripted = ScriptedConversationLiveSubscriptions(timelineScripts = emptyList(), group = group)
        val appState = conversationTimelineTestAppState(scripted.subscriptions)
        val controller =
            ConversationController(
                appState = appState,
                initialGroup = group,
                initialMemberSnapshot = conversationTimelineMemberSnapshot(),
                initialChatListRow = pendingInviteRow(),
                inviteAcceptor = { _, _ ->
                    acceptCalls.incrementAndGet()
                    throw MarmotKitException.GroupInviteNotPending()
                },
                groupRosterReader = { _, _ ->
                    check(rosterAvailable.get()) { "authority unavailable" }
                    conversationTimelineGroupRoster()
                },
            )
        try {
            runBlocking { assertFalse(controller.acceptInvite(notify = false)) }
            controller.markAuthoritativeTimelinePublishedForTest()
            showConversation(appState, controller, group)

            val error = context.getString(R.string.couldnt_load_conversation)
            val retry = context.getString(R.string.retry)
            composeRule.onNodeWithText(error).assertIsDisplayed()
            val retryNode =
                composeRule
                    .onNodeWithText(retry)
                    .assertIsDisplayed()
                    .assertHasClickAction()
            val minimumTouchTargetPx = with(composeRule.density) { 48.dp.toPx() }
            assertTrue(
                "Retry must retain at least a 48 dp touch target",
                retryNode.fetchSemanticsNode().touchBoundsInRoot.height >= minimumTouchTargetPx,
            )
            assertRetryTextContrast(retry)
            composeRule
                .onRoot()
                .captureRoboImage("src/test/snapshots/conversation_invite_authority_retry_light.png")

            rosterAvailable.set(true)
            composeRule.onNodeWithText(retry).performClick()
            awaitConversationCondition { !controller.inviteAcceptanceResolutionPending }
            composeRule.waitForIdle()

            assertEquals(1, acceptCalls.get())
            composeRule.onNodeWithText(error).assertDoesNotExist()
            composeRule.onNodeWithText(retry).assertDoesNotExist()
            composeRule.onNode(hasSetTextAction()).assertIsDisplayed()
        } finally {
            controller.onCleared()
        }
    }

    /** Mounts the real conversation route so the bottom-bar gate owns the recovery state. */
    private fun showConversation(
        appState: WhiteNoiseAppState,
        controller: ConversationController,
        group: dev.ipf.marmotkit.AppGroupRecordFfi,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                renderedSurfaceColor = MaterialTheme.colorScheme.surface
                ConversationScreen(
                    appState = appState,
                    chat =
                        ChatListItem(
                            group = group,
                            latest = null,
                            otherMemberAccount = null,
                            memberCount = 1,
                            memberSnapshot = conversationTimelineMemberSnapshot(),
                            projection = pendingInviteRow(),
                        ),
                    controller = controller,
                    onBack = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    /** Requires the actual rendered Retry text to meet normal-text AA on this production surface. */
    private fun assertRetryTextContrast(retry: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule
            .onNodeWithText(retry, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layouts) }
        val foreground =
            layouts
                .single()
                .layoutInput.style.color
        val contrast = contrastRatio(foreground.argbLong(), renderedSurfaceColor.argbLong())
        assertTrue(
            "Rendered Retry contrast $contrast must be at least $WCAG_AA_NORMAL_TEXT_CONTRAST",
            contrast >= WCAG_AA_NORMAL_TEXT_CONTRAST,
        )
    }

    /** Converts an opaque Compose color to the unsigned ARGB contract used by contrastRatio. */
    private fun Color.argbLong(): Long {
        check(this != Color.Unspecified) { "Rendered contrast colors must be specified" }
        return toArgb().toUInt().toLong()
    }

    /** Pending invitation record whose Welcome id identifies the retired Join generation. */
    private fun pendingInviteGroup() =
        conversationTimelineTestGroup().copy(
            pendingConfirmation = true,
            welcomerAccountIdHex = ConversationTimelineTestIds.SENDER_ID,
            viaWelcomeMessageIdHex = WELCOME_ID,
        )

    /** Chat-list projection matching the invitation record at route entry. */
    private fun pendingInviteRow() =
        notificationChatListRow().copy(
            pendingConfirmation = true,
            lastMessage = null,
            unreadCount = 0uL,
            hasUnread = false,
            firstUnreadMessageIdHex = null,
        )

    private companion object {
        const val WELCOME_ID = "welcome-generation"
    }
}
