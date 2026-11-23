package dev.ipf.whitenoise.android.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.appModuleSource
import dev.ipf.whitenoise.android.functionBody
import dev.ipf.whitenoise.android.ui.conversation.ConversationJumpToMentionButton
import dev.ipf.whitenoise.android.ui.conversation.ConversationMentionCoverage
import dev.ipf.whitenoise.android.ui.conversation.ConversationNavigationControl
import dev.ipf.whitenoise.android.ui.conversation.rememberConversationMentionJumpSeed
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #4030: a conversation whose entry row counts unread mentions shows a tappable jump-to-mention control before any
 * timeline row is loaded, and a chat or account without unread mentions shows none, even when the same composition
 * moves to it from one that has them. The composed checks drive the production seed, control and button with no
 * loaded rows; the source checks pin how `ConversationScreen` wires them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ConversationMentionJumpFirstFrameCoverageTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun knownUnreadMentionShowsAndAcceptsATapBeforeAnyRowLoads() {
        var taps = 0
        composeRule.setContent { MentionControl(sessionIdentity = "alice/busy", row = row(mentions = 2uL)) { taps++ } }
        composeRule.waitForIdle()

        val button = composeRule.onNodeWithContentDescription(mentionLabel(2), useUnmergedTree = true)
        button.assertIsDisplayed()
        button.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
        button.performClick()
        assertEquals(1, taps)
    }

    @Test
    fun chatWithoutUnreadMentionsShowsNoControl() {
        composeRule.setContent { MentionControl(sessionIdentity = "alice/quiet", row = row(mentions = 0uL)) {} }
        composeRule.waitForIdle()

        mentionNodes().assertCountEquals(0)
    }

    @Test
    fun movingToAChatOrAccountWithoutMentionsDropsTheSeededControl() {
        var open by mutableStateOf("alice/busy" to row(mentions = 3uL))
        composeRule.setContent { MentionControl(sessionIdentity = open.first, row = open.second) {} }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(mentionLabel(3), useUnmergedTree = true).assertIsDisplayed()

        open = "bob/busy" to row(mentions = 0uL)
        composeRule.waitForIdle()
        // A seed carried over from the previous session would keep its count of 3 here.
        mentionNodes().assertCountEquals(0)
    }

    @Test
    fun conversationScreenSeedsTheControlFromTheEntryRow() {
        val screen = appModuleSource("src/main/java/dev/ipf/whitenoise/android/ui/conversation/ConversationScreen.kt")
        val body = screen.functionBody("ConversationScreen")

        assertTrue(
            "the seed is keyed on the entry session and built from the captured entry row",
            "rememberConversationMentionJumpSeed(entryUnreadSessionIdentity, entryRow)" in body,
        )
        assertTrue(
            "the shown count comes from the seed, not from loaded rows alone",
            "mentionSeed.count(unreadMentionMessageIds, mentionCoverage)" in body,
        )
        assertTrue(
            "the control's visibility follows that count",
            "val showMentionJump = !selectionMode && unreadMentionCount > 0" in body,
        )
        assertTrue(
            "a tap before entry positioning waits for it instead of being dropped",
            "awaitAnchored = { snapshotFlow { initialTimelineAnchored }.first { it } }" in body,
        )
    }

    /** The screen's mention control with no loaded rows: pending coverage and no timeline targets. */
    @Composable
    @Suppress("FunctionNaming")
    private fun MentionControl(
        sessionIdentity: Any,
        row: ChatListRowFfi,
        onClick: () -> Unit,
    ) {
        WhiteNoiseTheme {
            val seed = rememberConversationMentionJumpSeed(sessionIdentity, row)
            val count = seed.count(emptyList(), ConversationMentionCoverage.PENDING)
            Column {
                ConversationNavigationControl(visible = count > 0, motionEnabled = false) {
                    ConversationJumpToMentionButton(count, onClick)
                }
            }
        }
    }

    private fun mentionLabel(count: Int): String =
        context.getString(R.string.conversation_jump_to_mention) + ", " +
            context.resources.getQuantityString(R.plurals.unread_mentions_count, count, count)

    private fun mentionNodes() =
        composeRule.onAllNodesWithContentDescription(
            context.getString(R.string.conversation_jump_to_mention),
            substring = true,
            useUnmergedTree = true,
        )

    /** A busy chat: dozens of unread rows after the read anchor, [mentions] of them mentioning this account. */
    private fun row(mentions: ULong) =
        ChatListRowFfi(
            selfMembership = SelfMembershipFfi.MEMBER,
            unreadMentionCount = mentions,
            unreadMention = mentions > 0uL,
            groupIdHex = "01".repeat(32),
            archived = false,
            pendingConfirmation = false,
            title = "Busy group",
            groupName = "Busy group",
            avatarUrl = null,
            avatar = null,
            lastMessage = null,
            unreadCount = 60uL,
            hasUnread = true,
            firstUnreadMessageIdHex = "f1".repeat(32),
            lastReadMessageIdHex = "e1".repeat(32),
            lastReadTimelineAt = 1uL,
            conversationCreatedAt = 1uL,
            activitySortAt = 2uL,
            updatedAt = 2uL,
            leaveRequestPending = false,
            leaveRequestedAtMs = null,
            leaveBlocked = false,
            manuallyMarkedUnread = false,
            conversationKind = ChatConversationKindFfi.GROUP,
            muted = false,
            mutedUntilMs = null,
            pinned = false,
            pinnedPosition = null,
            lifecycleState = GroupLifecycleStateFfi.STABLE,
            disbanding = false,
            disbandRequest = null,
        )
}
