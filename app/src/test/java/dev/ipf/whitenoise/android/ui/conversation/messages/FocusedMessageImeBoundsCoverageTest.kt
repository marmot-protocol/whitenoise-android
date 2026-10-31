package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The focused-message preview keeps its timestamp/status inside the bounds it is given (issue #3532).
 * A keyboard-shortened frame bounds the preview's height. When the footer cannot join the last text
 * line it needs its own row, and that row must come out of the body's height rather than being placed
 * below a clamped parent, where the preview's clip hides it.
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

        assertInside(footer = "footer", bound = "bound")
    }

    /** Just above the 200 dp compact boundary, the reply and five-line branch keep the warning and footer in view. */
    @Test fun previewFooterStaysVisibleJustAboveTheCompactBoundary() {
        var height by mutableStateOf(201.dp)
        composeRule.setContent {
            WhiteNoiseTheme(fontScale = 2f) {
                Box(Modifier.width(260.dp).height(height).testTag("bound")) {
                    // A warning forces the footer onto its own row below the body.
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

        for (next in listOf(201.dp, 230.dp, 260.dp)) {
            height = next
            composeRule.waitForIdle()
            assertInside(footer = "12:34", bound = "bound", byText = true, label = "at $next")
            assertInside(footer = WARNING, bound = "bound", byText = true, label = "warning at $next")
        }
    }

    /** Asserts the footer's unclipped bounds lie within the bounding box; clipped bounds would hide the overflow. */
    private fun assertInside(
        footer: String,
        bound: String,
        byText: Boolean = false,
        label: String = "",
    ) {
        val outer = composeRule.onNodeWithTag(bound).fetchSemanticsNode()
        val node =
            if (byText) {
                composeRule.onNodeWithText(footer, useUnmergedTree = true)
            } else {
                composeRule.onNodeWithTag(footer)
            }.fetchSemanticsNode()
        val outerTop = outer.positionInRoot.y
        val outerBottom = outerTop + outer.size.height
        val top = node.positionInRoot.y
        val bottom = top + node.size.height
        assertTrue(
            "footer $label at $top..$bottom px must lie inside $outerTop..$outerBottom px",
            top >= outerTop && bottom <= outerBottom,
        )
    }

    private companion object {
        const val WARNING = "Edited after a signature check"
    }
}
