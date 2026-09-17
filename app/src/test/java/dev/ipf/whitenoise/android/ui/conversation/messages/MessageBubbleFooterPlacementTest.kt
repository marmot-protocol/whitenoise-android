@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val ROOT_TAG = "footer.placement.root"
private const val FIRST_BLOCK_TAG = "footer.placement.first"
private const val LAST_BLOCK_TAG = "footer.placement.last"
private const val FOOTER_TAG = "footer.placement.footer"
private const val LAST_LINE_WIDTH_PX = 100
private const val BLOCK_LOCAL_BASELINE_PX = 14

/**
 * Where the inline footer lands when a bubble body renders as more than one
 * block, a paragraph followed by a link.
 *
 * The footer rides the body's last line. It used to take that line's baseline
 * from the last text block's own TextLayoutResult, whose coordinates are local
 * to that block, while being placed in the whole body's coordinate space. With
 * one block the two spaces coincide, so only a multi-block body exposed the
 * gap and the footer surfaced a block too high, in the middle of the bubble.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class MessageBubbleFooterPlacementTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Keeps the footer beside the final block, never beside an earlier one. */
    @Test
    fun multiBlockBodyKeepsTheFooterOnItsLastLine() {
        composeRule.setContent { MultiBlockBubble() }
        composeRule.waitForIdle()

        val firstBlock = composeRule.onNodeWithTag(FIRST_BLOCK_TAG).fetchSemanticsNode().boundsInRoot
        val lastBlock = composeRule.onNodeWithTag(LAST_BLOCK_TAG).fetchSemanticsNode().boundsInRoot
        val footer = composeRule.onNodeWithTag(FOOTER_TAG).fetchSemanticsNode().boundsInRoot

        assertTrue(
            "the body must actually render as two stacked blocks",
            lastBlock.top >= firstBlock.bottom - 1f,
        )
        assertTrue(
            "the footer must sit beside the last block, not an earlier one",
            footer.top >= firstBlock.bottom - 1f,
        )
        assertTrue(
            "the footer must ride the last block's line rather than hang below the body",
            footer.top < lastBlock.bottom,
        )
        composeRule.onNodeWithTag(ROOT_TAG).captureRoboImage(
            "src/test/snapshots/message_footer_multi_block_placement.png",
        )
    }

    /** A paragraph and a link stacked as separate blocks, with the footer beside them. */
    @Composable
    private fun MultiBlockBubble() {
        WhiteNoiseTheme(darkTheme = false) {
            Surface(color = MaterialTheme.colorScheme.surface) {
                BubbleFooterLayout(
                    modifier = Modifier.testTag(ROOT_TAG).width(220.dp).padding(8.dp),
                    // A narrow final line leaves room for the footer, which is the only
                    // case where its vertical placement is decided by a baseline.
                    lastLineWidth = LAST_LINE_WIDTH_PX,
                    // The baseline a last text block reports for its own final line is
                    // local to that block, so it lands inside the first block once the
                    // body stacks two of them. Feeding that stale value is exactly the
                    // input that used to strand the footer mid-bubble.
                    lastLineBaseline = BLOCK_LOCAL_BASELINE_PX,
                    footer = {
                        Text(
                            "12:45",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.testTag(FOOTER_TAG),
                        )
                    },
                ) {
                    Column {
                        Text(
                            "another small PR for yet another failed run:",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag(FIRST_BLOCK_TAG),
                        )
                        Text(
                            "https://example.invalid/pull/1900",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag(LAST_BLOCK_TAG),
                        )
                    }
                }
            }
        }
    }
}
