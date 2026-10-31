package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
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
 * The focused-message text preview keeps its excerpt line, warning and timestamp/status inside the bounds
 * it is given (issue #3532). A keyboard-shortened frame bounds the preview's height. A footer that cannot
 * join the last text line needs its own row, which must come out of the body's height rather than land
 * below a clamped parent, where the preview's clip hides it. An inline footer must not cost the body that
 * row, and the decorative quoted reply yields before the excerpt, warning or timestamp.
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

        assertInside(composeRule.onNodeWithTag("footer").fetchSemanticsNode(), label = "dropped footer")
    }

    /**
     * Around the 200 dp compact boundary at large font, with a reply and a warning that force the footer
     * onto its own row, the excerpt keeps whole lines and the warning keeps its full height.
     */
    @Test fun warningPreviewKeepsExcerptWarningAndFooterAroundTheCompactBoundary() {
        var height by mutableStateOf(400.dp)
        composeRule.setContent {
            // Each height is a fresh composition, as a newly opened preview is.
            key(height) { WarningPreview(height) }
        }
        composeRule.waitForIdle()
        val naturalWarningHeight = nodeHeight(WARNING)

        for (next in listOf(199.dp, 200.dp, 201.dp, 230.dp, 260.dp)) {
            height = next
            composeRule.waitForIdle()
            assertExcerptLinesWhole("at $next")
            assertEquals("warning height at $next", naturalWarningHeight, nodeHeight(WARNING))
            assertInside(textNode(WARNING), label = "warning at $next")
            assertInside(textNode("12:34"), label = "footer at $next")
        }
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

        assertExcerptLinesWhole("in a tiny frame")
        assertInside(textNode("12:34"), label = "inline footer")
    }

    /** A long incoming reply preview with a warning, bounded to [height] at 2× font. */
    @Composable
    private fun WarningPreview(height: Dp) {
        WhiteNoiseTheme(fontScale = 2f) {
            Box(Modifier.width(260.dp).height(height).testTag("bound")) {
                FocusedTextMessagePreview(
                    presentation = messageBubblePresentation(deleted = false, mine = false),
                    mine = false,
                    text = "A long target message. ".repeat(20),
                    document = null,
                    time = "12:34",
                    status = MessageStatus.Received,
                    showStatus = false,
                    reply = { Text("Quoted reply") },
                    warning = WARNING,
                )
            }
        }
    }

    /** The excerpt's last laid-out line fits its height, so no line is cut part-way. */
    private fun assertExcerptLinesWhole(label: String) {
        val results = mutableListOf<TextLayoutResult>()
        composeRule
            .onNodeWithTag("message-actions-excerpt")
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val layout = results.single()
        val lastLineBottom = layout.getLineBottom(layout.lineCount - 1)
        assertTrue(
            "excerpt cut $label: last line ends at $lastLineBottom of ${layout.size.height}",
            lastLineBottom <= layout.size.height + 0.5f,
        )
    }

    /** The unmerged node showing [text]. */
    private fun textNode(text: String): SemanticsNode {
        val node = composeRule.onNodeWithText(text, useUnmergedTree = true)
        return node.fetchSemanticsNode()
    }

    /** Unclipped height of the node showing [text]. */
    private fun nodeHeight(text: String): Int = textNode(text).size.height

    /** Asserts the node's unclipped bounds lie within the bound; clipped bounds would hide the overflow. */
    private fun assertInside(
        node: SemanticsNode,
        label: String,
    ) {
        val outer = composeRule.onNodeWithTag("bound").fetchSemanticsNode()
        val outerTop = outer.positionInRoot.y
        val outerBottom = outerTop + outer.size.height
        val top = node.positionInRoot.y
        val bottom = top + node.size.height
        assertTrue(
            "$label at $top..$bottom px must lie inside $outerTop..$outerBottom px",
            top >= outerTop && bottom <= outerBottom,
        )
    }

    private companion object {
        const val WARNING = "Edited after a signature check"
    }
}
