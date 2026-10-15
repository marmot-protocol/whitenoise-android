package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
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

    /** Eight configured reactions and the full picker all keep visible 48dp targets at the compact width (#3279). */
    @Test
    fun eightQuickReactionsAreDisplayedAtCompactWidth() {
        assertAllQuickReactionsVisible(fontScale = 1f, layoutDirection = LayoutDirection.Ltr, emojis = EIGHT_CHOICES)
    }

    /** Eight configured reactions stay inside the popup when mirrored. */
    @Test
    fun eightQuickReactionsAreDisplayedAtCompactWidthInRtl() {
        assertAllQuickReactionsVisible(fontScale = 1f, layoutDirection = LayoutDirection.Rtl, emojis = EIGHT_CHOICES)
    }

    /** Eight configured reactions survive a larger font scale. */
    @Test
    fun eightQuickReactionsAreDisplayedAtCompactWidthWithLargeFont() {
        assertAllQuickReactionsVisible(fontScale = 1.3f, layoutDirection = LayoutDirection.Ltr, emojis = EIGHT_CHOICES)
    }

    /** The rail spans the action stack's full width instead of hugging its reactions as a narrow capsule (#3279). */
    @Test
    fun reactionRailSpansTheStackWidth() {
        renderReactionMenu(fontScale = 1f, layoutDirection = LayoutDirection.Ltr, quickReactionEmojis = SIX_CHOICES)

        // The stack's tag sits inside its horizontal padding, so its bounds are the width the rail may use.
        val menu = composeRule.onNodeWithTag(MESSAGE_ACTION_MENU_TEST_TAG).fetchSemanticsNode().boundsInRoot
        val rail = composeRule.onNodeWithTag(FOCUSED_REACTION_RAIL_TEST_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue("the rail fills the stack width", rail.width >= menu.width - TOLERANCE_PX)
    }

    /**
     * Both rows of eight reactions and the picker divide the full rail width into equal targets: the first target
     * of each row starts at the rail's inner edge and the last ends at the other, with only compact gaps between.
     */
    @Test
    fun everyRowOfEightReactionsFillsTheRailWidth() {
        renderReactionMenu(fontScale = 1f, layoutDirection = LayoutDirection.Ltr, quickReactionEmojis = EIGHT_CHOICES)

        val rail = composeRule.onNodeWithTag(FOCUSED_REACTION_RAIL_TEST_TAG).fetchSemanticsNode().boundsInRoot
        val rows =
            targetBounds(EIGHT_CHOICES)
                .groupBy { it.top }
                .toSortedMap()
                .values
                .toList()
        assertEquals("eight reactions and the picker wrap into two rows at 360 dp", 2, rows.size)
        rows.forEach { row ->
            assertTrue("a row starts at the rail edge", row.minOf { it.left } - rail.left <= RAIL_EDGE_PX)
            assertTrue("a row ends at the rail edge", rail.right - row.maxOf { it.right } <= RAIL_EDGE_PX)
            val gaps = row.sortedBy { it.left }.zipWithNext { a, b -> b.left - a.right }
            assertTrue("gaps stay compact", gaps.all { it <= COMPACT_GAP_PX })
        }
    }

    /** A larger app text size grows every target and lets fewer fit on a row, so the reactions wrap sooner. */
    @Test
    fun largerAppTextSizeGrowsTargetsAndWrapsSooner() {
        renderReactionMenu(fontScale = 1.5f, layoutDirection = LayoutDirection.Ltr, quickReactionEmojis = EIGHT_CHOICES)

        val targets = targetBounds(EIGHT_CHOICES)
        assertTrue("targets grow with the text size", targets.all { it.height >= LARGE_TEXT_TARGET_PX })
        assertTrue("fewer targets fit on a row", targets.groupBy { it.top }.size >= 3)
    }

    /**
     * Text-scaled targets grow only into height the frame can spare: a short, keyboard-shortened frame keeps the
     * 100% rail so the preview and actions stay usable, while a full-height frame gets the larger targets.
     */
    @Test
    fun railGrowthIsLimitedToTheFramesSpareHeight() {
        val shortFrame = FocusedReactionSizing.fitting(56.dp, itemCount = 7, railWidth = 328.dp, maxRailHeight = 108.dp)
        val tallFrame = FocusedReactionSizing.fitting(56.dp, itemCount = 7, railWidth = 328.dp, maxRailHeight = 408.dp)

        assertEquals(FocusedReactionSizing.forEmoji(28.dp), shortFrame)
        assertEquals(108.dp, shortFrame.railHeight(itemCount = 7, availableWidth = 328.dp))
        assertEquals(FocusedReactionSizing.forEmoji(56.dp), tallFrame)
        assertEquals(76.dp, tallFrame.target)
    }

    /** The rail stays on one row while everything fits and splits evenly only once it cannot. */
    @Test
    fun reactionRailBalancesItsRowsOnlyWhenTheWidthCannotHoldThemAll() {
        assertEquals(7, focusedReactionItemsPerRow(itemCount = 7, availableWidth = 392.dp))
        assertEquals(4, focusedReactionItemsPerRow(itemCount = 7, availableWidth = 320.dp))
        assertEquals(5, focusedReactionItemsPerRow(itemCount = 9, availableWidth = 320.dp))
        assertEquals(9, focusedReactionItemsPerRow(itemCount = 9, availableWidth = 528.dp))
        assertEquals(3, focusedReactionItemsPerRow(itemCount = 9, availableWidth = 320.dp, targetSize = 62.dp))
        assertEquals(1, focusedReactionItemsPerRow(itemCount = 1, availableWidth = 320.dp))
        assertEquals(1, focusedReactionItemsPerRow(itemCount = 3, availableWidth = 0.dp))
    }

    /** Renders the given distinct choices and asserts each one, and the full picker, is inside the popup. */
    private fun assertAllQuickReactionsVisible(
        fontScale: Float,
        layoutDirection: LayoutDirection,
        emojis: List<String> = SIX_CHOICES,
    ) {
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

    /** Root bounds of each reaction target and the full-picker target. */
    private fun targetBounds(emojis: List<String>): List<Rect> =
        emojis.map { emoji ->
            composeRule.onNodeWithTag("$MESSAGE_ACTION_REACTION_TEST_TAG:$emoji").fetchSemanticsNode().boundsInRoot
        } + composeRule.onNodeWithContentDescription("Open emoji picker").fetchSemanticsNode().boundsInRoot

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

        // The rail's 4 dp inset plus rounding at mdpi.
        const val RAIL_EDGE_PX = 5f
        const val COMPACT_GAP_PX = 4.5f

        // At 150% text the 28 dp emoji grows by 14 dp, and every target with it.
        const val LARGE_TEXT_TARGET_PX = 61f
        val SIX_CHOICES = listOf("❤️", "👍", "👎", "😂", "😮", "😢")
        val EIGHT_CHOICES = SIX_CHOICES + listOf("🚀", "✅")
    }
}
