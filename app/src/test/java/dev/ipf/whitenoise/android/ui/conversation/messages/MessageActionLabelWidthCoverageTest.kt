package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.state.MessageStatus
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
 * Invariant gate for #3587: the focused-message action panel sizes its columns from whole one-line labels.
 *
 * A primary label stays on one line whenever the full width the stack offers can hold it, a short
 * neighbour never squeezes it into wrapping, and the panel wraps its cells instead of reserving a fixed
 * width. Measured through the production entry point with real typography and text scale.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class MessageActionLabelWidthCoverageTest {
    @get:Rule val composeRule = createComposeRule()

    /** At normal scale Keep on screen and every other label sit on one line in two label-sized columns. */
    @Test
    fun normalScaleKeepsEveryLabelOnOneLineInTwoColumns() {
        render(fontScale = 1f)

        assertEquals("Keep on screen pairs with Edit", cell("Keep on screen").top, cell("Edit").top, 0.5f)
        assertTrue("Keep on screen gets the wider column", cell("Keep on screen").width > cell("Edit").width)
        MAXIMUM_LABELS.forEach { assertEquals("$it wrapped", 1, labelLines(it)) }
    }

    /** The panel hugs its two columns, with no strip wider than the widest cells left unused. */
    @Test
    fun thePanelWrapsItsColumnsWithoutAFillerStrip() {
        render(fontScale = 1f)

        val cells = MAXIMUM_LABELS.map(::cell)
        val stack = composeRule.onNodeWithTag(MESSAGE_ACTION_MENU_TEST_TAG).fetchSemanticsNode().boundsInRoot
        val gridWidth = cells.maxOf { it.right } - cells.minOf { it.left }
        val stackContentWidth = stack.width - with(composeRule.density) { 32.dp.toPx() }
        assertTrue(
            "the grid $gridWidth must stay narrower than the stack $stackContentWidth",
            gridWidth < stackContentWidth,
        )
    }

    /** A larger text scale first uses spare width: every label still fits one line at 130%. */
    @Test
    fun largerTextUsesSpareWidthBeforeWrapping() {
        render(fontScale = 1.3f)

        MAXIMUM_LABELS.forEach { assertEquals("$it wrapped at 130%", 1, labelLines(it)) }
    }

    /**
     * At 200% the panel is one column. A label may wrap only when its cell already spans the full width the
     * stack offers, and it then stays whole rather than ellipsized.
     */
    @Test
    fun hugeTextWrapsOnlyAtTheFullWidthOfOneColumn() {
        render(fontScale = 2f)

        val stack = composeRule.onNodeWithTag(MESSAGE_ACTION_MENU_TEST_TAG).fetchSemanticsNode().boundsInRoot
        val stackContentWidth = stack.width - with(composeRule.density) { 32.dp.toPx() }
        val columnLeft = cell("Keep on screen").left
        MAXIMUM_LABELS.forEach { label ->
            composeRule.onNodeWithText(label, substring = false).performScrollTo()
            assertEquals("$label stays in the one column", columnLeft, cell(label).left, 0.5f)
            val layout = labelLayout(label)
            assertTrue("$label must not be cut short", layout.fitsItsSlot())
            if (layout.lineCount > 1) {
                assertTrue(
                    "$label wrapped in a ${cell(label).width}px cell of $stackContentWidth px available",
                    cell(label).width >= stackContentWidth - with(composeRule.density) { 24.dp.toPx() },
                )
            }
        }
    }

    /** Right-to-left mirrors the label-sized columns and keeps their one-line labels. */
    @Test
    fun rightToLeftMirrorsTheLabelSizedColumns() {
        render(fontScale = 1f, layoutDirection = LayoutDirection.Rtl)

        assertTrue("the first action sits on the right", cell("Keep on screen").left > cell("Edit").left)
        MAXIMUM_LABELS.forEach { assertEquals("$it wrapped in RTL", 1, labelLines(it)) }
    }

    /** The merged action cell carrying [label], in root coordinates. */
    private fun cell(label: String): Rect = composeRule.onNodeWithText(label, substring = false).boundsInRoot()

    /** This node's bounds in root coordinates. */
    private fun SemanticsNodeInteraction.boundsInRoot(): Rect = fetchSemanticsNode().boundsInRoot

    /** How many lines [label] occupies. */
    private fun labelLines(label: String): Int = labelLayout(label).lineCount

    /** The primary label's own text layout inside its merged action cell. */
    private fun labelLayout(label: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule
            .onNodeWithText(label, substring = false)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.first { it.layoutInput.text.text == label }
    }

    /** Composes the maximum received-text action set, Keep on screen first, through the production menu. */
    private fun render(
        fontScale: Float,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(fontScale = fontScale) {
                CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                    MessageActionMenu(
                        expanded = true,
                        anchorBoundsInWindow = null,
                        anchorWindowYPx = 8f,
                        canReply = true,
                        canReact = false,
                        canDelete = true,
                        canEdit = true,
                        canForward = true,
                        canSelect = true,
                        canCopyText = true,
                        canSpeak = true,
                        canSpeakCodeLiterally = false,
                        canSelectText = true,
                        canKeepOnScreen = true,
                        canShowLess = false,
                        canShare = true,
                        canSave = true,
                        quickReactionEmojis = emptyList(),
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
                        onSpeakCodeLiterally = {},
                        onKeepOnScreen = {},
                        onShowLess = {},
                        onShare = {},
                        onSave = {},
                        onInfo = {},
                        onDelete = {},
                        previewDescription = "A real presentation-only message preview",
                        preview = {
                            FocusedTextMessagePreview(
                                presentation = messageBubblePresentation(deleted = false, mine = false),
                                mine = false,
                                text = "A real presentation-only message preview",
                                document = null,
                                time = "12:34",
                                status = MessageStatus.Received,
                                showStatus = false,
                            )
                        },
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private companion object {
        val MAXIMUM_LABELS = listOf("Keep on screen", "Edit", "Select text", "Reply", "Delete")
    }
}

/**
 * Every line fits the width the label was offered and nothing is cut vertically.
 *
 * `hasVisualOverflow` is not usable here: the semantics layout rebuilds the paragraph at the full slot
 * width while the node keeps its narrower one-line width, so a label shorter than its slot reads as
 * overflowing by the difference.
 */
internal fun TextLayoutResult.fitsItsSlot(): Boolean =
    !didOverflowHeight &&
        (0 until lineCount).all { line ->
            getLineRight(line) - getLineLeft(line) <= layoutInput.constraints.maxWidth + 0.5f
        }
