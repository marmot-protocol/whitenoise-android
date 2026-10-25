package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class MessageActionMenuLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun literalCodeMenuIsAccessibleWithLargeRtlText() {
        val callbacks = mutableListOf<String>()
        renderMenu(fontScale = 1.3f, layoutDirection = LayoutDirection.Rtl, callbacks = callbacks, literalCode = true)
        composeRule.onNodeWithText("Read code literally").performScrollTo().assertIsDisplayed()
        composeRule
            .onNodeWithTag(MESSAGE_ACTION_MENU_TEST_TAG)
            .captureRoboImage("src/test/snapshots/message_action_menu_literal_code_rtl_large_font.png")
        composeRule.onNodeWithText("Read code literally").performClick()
        assertEquals(listOf("literalCode"), callbacks)
    }

    @Test
    fun showLessDispatchesFromTheAccessibleMenu() {
        val callbacks = mutableListOf<String>()
        renderMenu(fontScale = 1.3f, layoutDirection = LayoutDirection.Rtl, callbacks = callbacks, showLess = true)
        composeRule.onNodeWithText("Show less").performScrollTo().assertIsDisplayed()
        composeRule
            .onNodeWithTag(MESSAGE_ACTION_MENU_TEST_TAG)
            .captureRoboImage("src/test/snapshots/message_action_menu_show_less_rtl.png")
        composeRule.onNodeWithText("Show less").performClick()
        assertEquals(listOf("showLess"), callbacks)
    }

    /** Maximum menu uses prototype single column with delete last. */
    @Test
    fun maximumMenuUsesTwoBalancedColumnsWithDeleteLast() {
        renderMenu(fontScale = 1f)

        val keepOnScreen = bounds("Keep on screen")
        val edit = bounds("Edit")
        val selectText = bounds("Select text")
        val reply = bounds("Reply")
        val delete = bounds("Delete")
        val info = bounds("Info")

        assertEquals("the first two actions share a row", keepOnScreen.top, edit.top, 0.5f)
        assertTrue("and sit in different columns", edit.left > keepOnScreen.left)
        assertEquals("columns are balanced", keepOnScreen.width, edit.width, 0.5f)
        assertTrue("later actions come below the first row", selectText.top > keepOnScreen.top)
        assertEquals("the left column stays aligned", keepOnScreen.left, selectText.left, 0.5f)
        assertEquals("and the row below pairs in order", selectText.top, reply.top, 0.5f)
        assertEquals("delete ends the grid beside the last action", info.top, delete.top, 0.5f)
        assertTrue("with delete on the right", delete.left > info.left)
    }

    /** A tap on the stack's empty padding dismisses the actions like the scrim does. */
    @Test
    fun tapOutsideEveryActionTargetDismisses() {
        var dismissals = 0
        renderMenu(fontScale = 1f, onDismiss = { dismissals++ })

        // The 8 dp gap between the preview and the menu is the column's own surface: a tap there
        // touches neither a reaction nor a menu item, so it must dismiss.
        val column = composeRule.onNodeWithTag(MESSAGE_ACTION_MENU_TEST_TAG).fetchSemanticsNode().boundsInRoot
        val preview = composeRule.onNodeWithTag("message-actions-preview").fetchSemanticsNode().boundsInRoot
        val gapY = preview.bottom - column.top + with(composeRule.density) { 4.dp.toPx() }
        composeRule.onNodeWithTag(MESSAGE_ACTION_MENU_TEST_TAG).performTouchInput { click(Offset(1f, gapY)) }

        assertEquals(1, dismissals)
    }

    /** The preview dismisses when tapped, and still runs none of the menu's actions. */
    @Test
    fun tapOnThePreviewDismisses() {
        var dismissals = 0
        val callbacks = mutableListOf<String>()
        renderMenu(fontScale = 1f, callbacks = callbacks, onDismiss = { dismissals++ })

        composeRule.onNodeWithTag("message-actions-preview").performTouchInput { click() }

        assertEquals(1, dismissals)
        assertTrue(callbacks.isEmpty())
    }

    @Test
    fun shareActionHasDeterministicMenuPresentation() {
        renderMenu(fontScale = 1f)

        composeRule
            .onNodeWithTag(MESSAGE_ACTION_MENU_TEST_TAG)
            .captureRoboImage("src/test/snapshots/message_action_menu_share_light.png")
    }

    /** Large font falls back to one readable column. */
    @Test
    fun largeFontFallsBackToOneReadableColumn() {
        renderMenu(fontScale = 2f, literalCode = true)

        assertTrue(bounds("Select text").top > bounds("Edit").top)
        // At this font the column scrolls, so a row further down the list is composed but
        // unmeasured until it is scrolled to. Reachability is the claim worth pinning.
        composeRule.onNodeWithText("Select", substring = false).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Delete", substring = false).performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule
            .onNodeWithText("Read code literally")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        assertTrue(
            "The popup must render the requested 200% typography",
            layouts.all { it.layoutInput.style.fontSize.value >= 32f },
        )
        assertTrue(
            "literal-code label must fit: " +
                layouts.joinToString { "size=${it.size}, lines=${it.lineCount}, height=${it.multiParagraph.height}" },
            layouts.none { it.hasVisualOverflow },
        )
    }

    /** Rtl keeps single column command order. */
    @Test
    fun rtlKeepsSingleColumnCommandOrder() {
        renderMenu(fontScale = 1f, layoutDirection = LayoutDirection.Rtl)

        assertTrue(bounds("Reply").top > bounds("Edit").top)
        assertEquals(bounds("Reply").right, bounds("Edit").right, 0.5f)
    }

    /** Every maximum variant action invokes its original callback. */
    @Test
    fun everyMaximumVariantActionInvokesItsOriginalCallback() {
        val callbacks = mutableListOf<String>()
        renderMenu(fontScale = 1f, callbacks = callbacks)

        listOf(
            "Edit",
            "Select text",
            "Reply",
            "Forward",
            "Keep on screen",
            "Share",
            "Save",
            "Copy",
            "Read Aloud",
            "Select",
            "Info",
            "Delete",
        ).forEach { composeRule.onNodeWithText(it, substring = false).performScrollTo().performClick() }

        assertEquals(
            listOf(
                "edit",
                "selectText",
                "reply",
                "forward",
                "keepOnScreen",
                "share",
                "save",
                "copy",
                "speak",
                "select",
                "info",
                "delete",
            ),
            callbacks,
        )
    }

    @Test
    fun emojiPickerKeepsMinimumTouchTarget() {
        renderMenu(fontScale = 1f, canReact = true)

        val bounds =
            composeRule
                .onNodeWithContentDescription("Open emoji picker")
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue(bounds.width >= 48f)
        assertTrue(bounds.height >= 48f)
    }

    /**
     * A sparse rail keeps its buttons compact: they grow to share the row (#3279) but never past twice their
     * 48 dp height, and the pair stays centered instead of spreading to the rail's far edges.
     */
    @Test
    fun quickReactionButtonsStayCompactWhenOnlyOneIsAvailable() {
        renderMenu(fontScale = 1f, canReact = true)

        val bounds =
            composeRule
                .onNodeWithTag("$MESSAGE_ACTION_REACTION_TEST_TAG:👍")
                .fetchSemanticsNode()
                .boundsInRoot
        val picker = composeRule.onNodeWithContentDescription("Open emoji picker").fetchSemanticsNode().boundsInRoot

        assertEquals(96f, bounds.width, 0.5f)
        assertEquals(48f, bounds.height, 0.5f)
        assertEquals("the pair is a compact centered cluster", 4f, picker.left - bounds.right, 0.5f)
    }

    /** A full six-slot configuration keeps every reaction, and the full picker, inside the popup. */
    @Test
    fun emojiPickerStaysVisibleWithMaximumQuickReactions() {
        assertMaximumQuickReactionsFitThePopup(fontScale = 1f, layoutDirection = LayoutDirection.Ltr)
    }

    /** Mirroring the popup must not push the sixth configured choice past its edge either. */
    @Test
    fun maximumQuickReactionsStayInsideThePopupInRtl() {
        assertMaximumQuickReactionsFitThePopup(fontScale = 1f, layoutDirection = LayoutDirection.Rtl)
    }

    /** A supported larger font scale may grow the popup but may not hide a configured choice. */
    @Test
    fun maximumQuickReactionsStayInsideThePopupAtLargeFontScale() {
        assertMaximumQuickReactionsFitThePopup(fontScale = 1.3f, layoutDirection = LayoutDirection.Ltr)
    }

    /**
     * Renders the six configured choices at the compact width and asserts each one — and the
     * full-picker action — is displayed within the popup with its accessible touch target intact.
     */
    private fun assertMaximumQuickReactionsFitThePopup(
        fontScale: Float,
        layoutDirection: LayoutDirection,
    ) {
        val emojis = listOf("❤️", "👍", "👎", "😂", "😮", "😢")
        renderMenu(
            fontScale = fontScale,
            layoutDirection = layoutDirection,
            canReact = true,
            quickReactionEmojis = emojis,
        )

        val menu =
            composeRule
                .onNodeWithTag(MESSAGE_ACTION_MENU_TEST_TAG)
                .fetchSemanticsNode()
                .boundsInRoot
        emojis.forEach { emoji ->
            val reaction =
                composeRule
                    .onNodeWithTag("$MESSAGE_ACTION_REACTION_TEST_TAG:$emoji")
                    .assertIsDisplayed()
                    .fetchSemanticsNode()
                    .boundsInRoot
            assertTrue("$emoji starts outside the popup", reaction.left >= menu.left - BOUNDS_TOLERANCE_PX)
            assertTrue("$emoji ends outside the popup", reaction.right <= menu.right + BOUNDS_TOLERANCE_PX)
            assertTrue("$emoji lost its touch target", reaction.width >= TOUCH_TARGET_PX)
            assertTrue("$emoji lost its touch target", reaction.height >= TOUCH_TARGET_PX)
        }
        val picker =
            composeRule
                .onNodeWithContentDescription("Open emoji picker")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot

        assertTrue(picker.left >= menu.left - BOUNDS_TOLERANCE_PX)
        assertTrue(picker.right <= menu.right + BOUNDS_TOLERANCE_PX)
        assertTrue(picker.width >= TOUCH_TARGET_PX)
        assertTrue(picker.height >= TOUCH_TARGET_PX)
    }

    /** Composes the menu under test with the given fixture. */
    private fun renderMenu(
        fontScale: Float,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        callbacks: MutableList<String> = mutableListOf(),
        canReact: Boolean = false,
        literalCode: Boolean = false,
        showLess: Boolean = false,
        onDismiss: () -> Unit = {},
        quickReactionEmojis: List<String> = if (canReact) listOf("👍") else emptyList(),
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(fontScale = fontScale) {
                CompositionLocalProvider(
                    LocalLayoutDirection provides layoutDirection,
                ) {
                    MessageActionMenu(
                        expanded = true,
                        anchorBoundsInWindow = null,
                        anchorWindowYPx = 8f,
                        canReply = true,
                        canReact = canReact,
                        canDelete = true,
                        canEdit = true,
                        canForward = true,
                        canSelect = true,
                        canCopyText = true,
                        canSpeak = true,
                        canSpeakCodeLiterally = literalCode,
                        canSelectText = true,
                        canKeepOnScreen = true,
                        canShowLess = showLess,
                        canShare = true,
                        canSave = true,
                        quickReactionEmojis = quickReactionEmojis,
                        onDismissRequest = onDismiss,
                        onReact = {},
                        onOpenEmojiPicker = {},
                        onReply = { callbacks += "reply" },
                        onEdit = { callbacks += "edit" },
                        onForward = { callbacks += "forward" },
                        onSelect = { callbacks += "select" },
                        onSelectText = { callbacks += "selectText" },
                        onCopyText = { callbacks += "copy" },
                        onSpeak = { callbacks += "speak" },
                        onSpeakCodeLiterally = { callbacks += "literalCode" },
                        onKeepOnScreen = { callbacks += "keepOnScreen" },
                        onShowLess = { callbacks += "showLess" },
                        onShare = { callbacks += "share" },
                        onSave = { callbacks += "save" },
                        onInfo = { callbacks += "info" },
                        onDelete = { callbacks += "delete" },
                        previewDescription = "A real presentation-only message preview",
                        preview = {
                            FocusedTextMessagePreview(
                                presentation = messageBubblePresentation(deleted = false, mine = false),
                                mine = false,
                                text = "A real presentation-only message preview",
                                document = null,
                                time = "12:34",
                                status = dev.ipf.whitenoise.android.state.MessageStatus.Received,
                                showStatus = false,
                            )
                        },
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun bounds(label: String) =
        composeRule
            .onNodeWithText(label, substring = false)
            .fetchSemanticsNode()
            .boundsInRoot

    private companion object {
        const val TOUCH_TARGET_PX = 48f
        const val BOUNDS_TOLERANCE_PX = 0.5f
    }
}
