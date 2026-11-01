package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The focused-message text preview fits the height it is given from measured content (issue #3532).
 * Within any frame that can hold one excerpt line, the warning and the timestamp/status footer, the
 * excerpt shows whole lines, the warning keeps its full height and the footer stays fully visible. The
 * sender label yields first, then the quoted reply, which is only ever shown whole. A footer that
 * cannot join the last text line takes its own row out of the body's height rather than landing below
 * a clamped parent, where the preview's clip hides it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class FocusedMessageImeBoundsCoverageTest {
    @get:Rule val composeRule = createComposeRule()

    /** A full-height body under a bounded height leaves a dropped footer inside the layout. */
    @Test fun droppedFooterStaysInsideABoundedLayout() {
        composeRule.setContent {
            WhiteNoiseTheme {
                Box(Modifier.width(200.dp).height(100.dp).testTag("bound")) {
                    BubbleFooterLayout(
                        footer = { Text("12:34 footer too wide to fit inline", Modifier.testTag("footer")) },
                        allowInline = false,
                    ) {
                        Box(Modifier.width(200.dp).fillMaxHeight())
                    }
                }
            }
        }

        assertFullyInside(placed(composeRule.onAllNodesWithTag("footer")), label = "dropped footer")
    }

    /**
     * A group message with a sender label, a three-line quoted reply and a warning, at 2× font, keeps whole
     * excerpt lines, its full warning and its footer at every height down to the minimum content. The
     * quote is shown whole or not at all, and is shown while it fits above the minimum content.
     */
    @Test fun groupPreviewKeepsExcerptWarningAndFooterAcrossFrameHeights() {
        var height by mutableStateOf(400.dp)
        composeRule.setContent {
            // Each height is a fresh composition, as a newly opened preview is.
            key(height) { GroupPreview(height) }
        }
        composeRule.waitForIdle()
        val naturalWarningHeight = textNode(WARNING).size.height
        assertTrue("the quote shows in a tall frame", isShown(QUOTE_LINES.first()))
        assertTrue("the sender shows in a tall frame", isShown(SENDER))

        for (next in listOf(320.dp, 260.dp, 230.dp, 201.dp, 199.dp, 180.dp)) {
            height = next
            composeRule.waitForIdle()
            assertPreviewFits("at $next", naturalWarningHeight)
        }
        assertFalse("the quote yields in a short frame", isShown(QUOTE_LINES.first()))
    }

    /** An outgoing message keeps its delivery status visible beside the timestamp in a short frame. */
    @Test fun outgoingStatusStaysVisibleInAShortFrame() {
        var height by mutableStateOf(201.dp)
        composeRule.setContent {
            key(height) {
                WhiteNoiseTheme(fontScale = 2f) {
                    Box(Modifier.width(260.dp).height(height).testTag("bound")) {
                        FocusedTextMessagePreview(
                            presentation = messageBubblePresentation(deleted = false, mine = true),
                            mine = true,
                            text = LONG_TEXT,
                            document = null,
                            time = "12:34",
                            status = MessageStatus.Sent,
                            showStatus = true,
                        )
                    }
                }
            }
        }

        for (next in listOf(201.dp, 120.dp)) {
            height = next
            composeRule.waitForIdle()
            assertExcerptFits("outgoing at $next")
            assertFullyInside(placed(composeRule.onAllNodesWithContentDescription("Sent")), "status at $next")
            assertFullyInside(textNode("12:34"), "footer at $next")
        }
    }

    /**
     * Resizing an open preview, as the keyboard does, re-fits it in place with the same guarantees as a
     * freshly opened one, including whether the quote is shown.
     */
    @Test fun openPreviewRefitsWhenItsFrameShrinksAndGrows() {
        var height by mutableStateOf(400.dp)
        composeRule.setContent { GroupPreview(height) }
        composeRule.waitForIdle()
        val naturalWarningHeight = textNode(WARNING).size.height

        for ((next, quoteShown) in listOf(180.dp to false, 260.dp to null, 400.dp to true, 201.dp to null)) {
            height = next
            composeRule.waitForIdle()
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
            assertPreviewFits("after resizing to $next", naturalWarningHeight)
            quoteShown?.let { assertEquals("quote shown after resizing to $next", it, isShown(QUOTE_LINES.first())) }
        }
    }

    /** With no height bound at all, the preview keeps its sender, quote and full five-line excerpt. */
    @Test fun unboundedPreviewKeepsSenderQuoteAndExcerpt() {
        composeRule.setContent {
            WhiteNoiseTheme {
                Box(Modifier.width(260.dp).wrapContentHeight(align = Alignment.Top, unbounded = true)) {
                    GroupPreviewContent()
                }
            }
        }

        assertTrue("sender shown", isShown(SENDER))
        assertTrue("quote shown", isShown(QUOTE_LINES.first()))
        assertEquals(5, excerptLayout().lineCount)
    }

    /** A one-line message whose footer fits inline keeps its line whole in a keyboard-tiny frame. */
    @Test fun inlineFooterKeepsTheExcerptLineWholeInATinyFrame() {
        composeRule.setContent {
            WhiteNoiseTheme {
                Box(Modifier.width(260.dp).height(48.dp).testTag("bound")) {
                    FocusedTextMessagePreview(
                        presentation = messageBubblePresentation(deleted = false, mine = true),
                        mine = true,
                        text = "ok",
                        document = null,
                        time = "12:34",
                        status = MessageStatus.Sent,
                        showStatus = true,
                    )
                }
            }
        }

        assertExcerptFits("in a tiny frame")
        assertFullyInside(textNode("12:34"), label = "inline footer")
    }

    /** The group preview bounded to [height] at 2× font. */
    @Composable
    private fun GroupPreview(height: Dp) {
        WhiteNoiseTheme(fontScale = 2f) {
            Box(Modifier.width(260.dp).height(height).testTag("bound")) { GroupPreviewContent() }
        }
    }

    /** A long incoming group message with a sender label, a three-line quoted reply and a warning. */
    @Composable
    private fun GroupPreviewContent() {
        FocusedTextMessagePreview(
            presentation = messageBubblePresentation(deleted = false, mine = false),
            mine = false,
            text = LONG_TEXT,
            document = null,
            time = "12:34",
            status = MessageStatus.Received,
            showStatus = false,
            senderName = SENDER,
            reply = { Column { QUOTE_LINES.forEach { Text(it) } } },
            warning = WARNING,
        )
    }

    /** Every placed part of the group preview fits: excerpt, full warning, footer, and any quote or sender. */
    private fun assertPreviewFits(
        label: String,
        naturalWarningHeight: Int,
    ) {
        assertExcerptFits(label)
        assertEquals("warning height $label", naturalWarningHeight, textNode(WARNING).size.height)
        assertFullyInside(textNode(WARNING), "warning $label")
        assertFullyInside(textNode("12:34"), "footer $label")
        QUOTE_LINES.filter(::isShown).forEach { assertFullyInside(textNode(it), "quote line $label") }
        if (isShown(SENDER)) assertFullyInside(textNode(SENDER), "sender $label")
    }

    /** The excerpt is fully visible and its last laid-out line fits its height, so no line is cut part-way. */
    private fun assertExcerptFits(label: String) {
        val layout = excerptLayout()
        val lastLineBottom = layout.getLineBottom(layout.lineCount - 1)
        assertTrue(
            "excerpt cut $label: last line ends at $lastLineBottom of ${layout.size.height}",
            lastLineBottom <= layout.size.height + 0.5f,
        )
        assertFullyInside(placed(composeRule.onAllNodesWithTag("message-actions-excerpt")), "excerpt $label")
    }

    /** The text layout of the placed excerpt. */
    private fun excerptLayout(): TextLayoutResult {
        val node = placed(composeRule.onAllNodesWithTag("message-actions-excerpt"))
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    /** Whether a placed node shows [text]. The preview also composes unplaced measuring copies. */
    private fun isShown(text: String): Boolean =
        composeRule
            .onAllNodesWithText(text, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .any { it.layoutInfo.isPlaced }

    /** The single placed node showing [text]. */
    private fun textNode(text: String): SemanticsNode {
        val nodes = composeRule.onAllNodesWithText(text, useUnmergedTree = true)
        return placed(nodes)
    }

    /** The single placed node among [nodes]. */
    private fun placed(nodes: SemanticsNodeInteractionCollection): SemanticsNode {
        val shown = nodes.fetchSemanticsNodes().filter { it.layoutInfo.isPlaced }
        assertEquals("exactly one placed node", 1, shown.size)
        return shown.single()
    }

    /**
     * Asserts the node has a visible height, lies inside the bound, and is not clipped by any ancestor:
     * its clipped bounds equal its unclipped extent.
     */
    private fun assertFullyInside(
        node: SemanticsNode,
        label: String,
    ) {
        val outer = composeRule.onNodeWithTag("bound").fetchSemanticsNode()
        val outerTop = outer.positionInRoot.y
        val outerBottom = outerTop + outer.size.height
        val top = node.positionInRoot.y
        val bottom = top + node.size.height
        assertTrue("$label has no height", node.size.height > 0)
        assertTrue(
            "$label at $top..$bottom px must lie inside $outerTop..$outerBottom px",
            top >= outerTop && bottom <= outerBottom,
        )
        assertEquals("$label is clipped", node.size.height.toFloat(), node.boundsInRoot.height, 0.5f)
    }

    private companion object {
        const val WARNING = "Edited after a signature check"
        const val SENDER = "Coral Fox"
        val LONG_TEXT = "A long target message. ".repeat(20)
        val QUOTE_LINES = listOf("Quote", "Reply one", "Reply two")
    }
}
