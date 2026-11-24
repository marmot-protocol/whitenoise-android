package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.chats.ChatListJumpToTopButton
import dev.ipf.whitenoise.android.ui.conversation.ConversationJumpToMentionButton
import dev.ipf.whitenoise.android.ui.conversation.ConversationJumpToNewestButton
import dev.ipf.whitenoise.android.ui.conversation.SEARCH_TARGET_RETRY_CHIP_TAG
import dev.ipf.whitenoise.android.ui.conversation.SearchTargetRetryChip
import dev.ipf.whitenoise.android.ui.conversation.TtsResumeFollowButton
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The shared floating control (#4065): a 48 dp touch target around a 34 dp disc, and every floating control
 * of the transcript and the chat list built on it — or, for the pills, with the same 48 dp minimum.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class FloatingControlDiscTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Hosts [content] in the app theme. */
    private fun show(content: @Composable () -> Unit) {
        composeRule.setContent { WhiteNoiseTheme { content() } }
    }

    /**
     * The disc keeps the 42 dp slot the jump controls always had in their stack, so the visible 34 dp disc does
     * not move, while its 48 dp touch target reaches past the slot and takes the tap, even outside the slot.
     */
    @Test
    fun discKeepsItsSlotWhileA48DpTargetOverhangsIt() {
        var clicks = 0
        show {
            FloatingControlDisc(
                icon = painterResource(R.drawable.ic_jump_to_edge),
                contentDescription = "Jump",
                onClick = { clicks++ },
                modifier = Modifier.testTag("slot"),
            )
        }
        val slot = composeRule.onNodeWithTag("slot")
        slot.assertWidthIsEqualTo(FloatingControlSlot)
        slot.assertHeightIsEqualTo(FloatingControlSlot)
        val target = composeRule.onNodeWithContentDescription("Jump")
        target.assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp).performClick()
        // A tap in the target's corner lands outside the 42 dp slot and still reaches the control.
        target.performTouchInput { click(Offset(1f, 1f)) }
        composeRule
            .onNodeWithTag(FLOATING_CONTROL_DISC_TAG, useUnmergedTree = true)
            .assertWidthIsEqualTo(FloatingControlDiscSize)
            .assertHeightIsEqualTo(FloatingControlDiscSize)
        composeRule.runOnIdle { assertEquals(2, clicks) }
    }

    /** The mention pill keeps its 34 dp height in the stack while its touch target is 48 dp tall. */
    @Test
    fun mentionPillKeepsItsHeightWhileTheTargetOverhangs() {
        show { Box(Modifier.testTag("slot")) { ConversationJumpToMentionButton(count = 2) {} } }
        composeRule.onNodeWithTag("slot").assertHeightIsEqualTo(FloatingControlPillHeight)
        composeRule
            .onNodeWithContentDescription("mention", substring = true, ignoreCase = true)
            .assertHeightIsAtLeast(48.dp)
    }

    /** Jump to newest takes the shared 48 dp target, badge included. */
    @Test
    fun jumpToNewestHasA48DpTarget() {
        show { ConversationJumpToNewestButton(unreadIncomingCount = 3, onClick = {}) }
        composeRule.onNodeWithContentDescription("Jump to newest, 3 unread messages").assertMinimumTouchTarget()
    }

    /** The chat list's jump-to-top control takes the shared 48 dp target. */
    @Test
    fun chatListJumpToTopHasA48DpTarget() {
        show { ChatListJumpToTopButton(onClick = {}) }
        composeRule.onNodeWithContentDescription("Scroll to top").assertMinimumTouchTarget()
    }

    /** The read-aloud resume-follow control takes the shared 48 dp target. */
    @Test
    fun ttsResumeFollowHasA48DpTarget() {
        show { TtsResumeFollowButton(onClick = {}) }
        composeRule.onNodeWithContentDescription("Resume following", substring = true).assertMinimumTouchTarget()
    }

    /** The 34 dp mention pill sits inside a 48 dp target, which a tap reaches even above the pill. */
    @Test
    fun mentionPillHasA48DpTarget() {
        var clicks = 0
        show { ConversationJumpToMentionButton(count = 2) { clicks++ } }
        val target = composeRule.onNodeWithContentDescription("mention", substring = true, ignoreCase = true)
        target.assertMinimumTouchTarget().performClick()
        // Above the 34 dp pill, inside the 48 dp target.
        target.performTouchInput { click(Offset(centerX, 1f)) }
        composeRule.runOnIdle { assertEquals(2, clicks) }
    }

    /** The search retry pill is 34 dp inside a 48 dp target, which a tap reaches even below the pill. */
    @Test
    fun retryChipHasA48DpTarget() {
        var retries = 0
        show { SearchTargetRetryChip(onRetry = { retries++ }) }
        val target = composeRule.onNodeWithTag(SEARCH_TARGET_RETRY_CHIP_TAG)
        target.assertMinimumTouchTarget().performClick()
        // Below the 34 dp pill, inside the 48 dp target.
        target.performTouchInput { click(Offset(centerX, height - 1f)) }
        composeRule.runOnIdle { assertEquals(2, retries) }
    }

    /** Asserts this node is at least 48 dp in both directions. */
    private fun SemanticsNodeInteraction.assertMinimumTouchTarget(): SemanticsNodeInteraction {
        assertWidthIsAtLeast(48.dp)
        return assertHeightIsAtLeast(48.dp)
    }
}
