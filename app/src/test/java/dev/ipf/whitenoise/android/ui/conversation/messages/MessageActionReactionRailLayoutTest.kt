package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
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
 * The quick-reaction rail owes every configured choice a visible, tappable target at the supported
 * compact width. A sixth slot used to sit past a horizontally scrolling viewport that announced
 * nothing, so these assertions pin visibility rather than mere composition.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class MessageActionReactionRailLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Every configured reaction is on screen at the compact width, not behind a horizontal scroll. */
    @Test
    fun everyQuickReactionIsDisplayedAtCompactWidth() {
        assertAllQuickReactionsVisible(fontScale = 1f, layoutDirection = LayoutDirection.Ltr)
    }

    /** Mirroring the rail must not push a configured reaction out of the popup either. */
    @Test
    fun everyQuickReactionIsDisplayedAtCompactWidthInRtl() {
        assertAllQuickReactionsVisible(fontScale = 1f, layoutDirection = LayoutDirection.Rtl)
    }

    /** A larger font scale may grow the rows but may not drop a configured reaction. */
    @Test
    fun everyQuickReactionIsDisplayedAtCompactWidthWithLargeFont() {
        assertAllQuickReactionsVisible(fontScale = 1.3f, layoutDirection = LayoutDirection.Ltr)
    }

    /** The rail stays on one row while everything fits and splits evenly only once it cannot. */
    @Test
    fun reactionRailBalancesItsRowsOnlyWhenTheWidthCannotHoldThemAll() {
        assertEquals(7, focusedReactionItemsPerRow(itemCount = 7, availableWidth = 392.dp))
        assertEquals(4, focusedReactionItemsPerRow(itemCount = 7, availableWidth = 320.dp))
        assertEquals(1, focusedReactionItemsPerRow(itemCount = 1, availableWidth = 320.dp))
        assertEquals(1, focusedReactionItemsPerRow(itemCount = 3, availableWidth = 0.dp))
    }

    /** Renders six distinct choices and asserts each one, and the full picker, is inside the popup. */
    private fun assertAllQuickReactionsVisible(
        fontScale: Float,
        layoutDirection: LayoutDirection,
    ) {
        val emojis = listOf("❤️", "👍", "👎", "😂", "😮", "😢")
        renderReactionMenu(fontScale, layoutDirection, emojis)

        val menu =
            composeRule
                .onNodeWithTag(MESSAGE_ACTION_MENU_TEST_TAG)
                .fetchSemanticsNode()
                .boundsInRoot
        emojis.forEach { emoji ->
            val target =
                composeRule
                    .onNodeWithTag("$MESSAGE_ACTION_REACTION_TEST_TAG:$emoji")
                    .assertIsDisplayed()
                    .fetchSemanticsNode()
                    .boundsInRoot
            assertTrue("$emoji starts left of the popup", target.left >= menu.left - TOLERANCE_PX)
            assertTrue("$emoji ends right of the popup", target.right <= menu.right + TOLERANCE_PX)
            assertTrue("$emoji lost its touch target", target.width >= TOUCH_TARGET_PX)
            assertTrue("$emoji lost its touch target", target.height >= TOUCH_TARGET_PX)
        }
        val picker =
            composeRule
                .onNodeWithContentDescription("Open emoji picker")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue(picker.left >= menu.left - TOLERANCE_PX)
        assertTrue(picker.right <= menu.right + TOLERANCE_PX)
        assertTrue(picker.width >= TOUCH_TARGET_PX)
        assertTrue(picker.height >= TOUCH_TARGET_PX)
    }

    /** Composes a reaction-capable action menu with the given quick reactions. */
    private fun renderReactionMenu(
        fontScale: Float,
        layoutDirection: LayoutDirection,
        quickReactionEmojis: List<String>,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(fontScale = fontScale) {
                CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                    MessageActionMenu(
                        expanded = true,
                        anchorBoundsInWindow = null,
                        anchorWindowYPx = 8f,
                        canReply = true,
                        canReact = true,
                        canDelete = true,
                        canEdit = true,
                        canForward = true,
                        canSelect = true,
                        canCopyText = true,
                        canSpeak = true,
                        canSelectText = true,
                        canSave = true,
                        quickReactionEmojis = quickReactionEmojis,
                        onDismissRequest = {},
                        onReact = {},
                        onOpenEmojiPicker = {},
                        onReply = {},
                        onEdit = {},
                        onForward = {},
                        onSelect = {},
                        onSelectText = {},
                        onCopyText = {},
                        onSpeak = {},
                        onSave = {},
                        onInfo = {},
                        onDelete = {},
                        previewDescription = "A lifted message",
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private companion object {
        const val TOUCH_TARGET_PX = 48f
        const val TOLERANCE_PX = 0.5f
    }
}
