@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.state.MessageStatus
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

    /** A wrapped edited label cannot rise into earlier body lines even with a short final line. */
    @Test
    fun wrappedMetadataRemainsBelowTheWholeMultiBlockBody() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                BubbleFooterLayout(
                    modifier = Modifier.width(130.dp).testTag(ROOT_TAG),
                    lastLineWidth = 8,
                    lastLineBaseline = 100,
                    footer = {
                        MessageInlineFooter(
                            timeText = "12:45",
                            color = Color.Black,
                            showStatus = true,
                            status = MessageStatus.Sent,
                            editedLabel = "A long translated edited label",
                            onEditedClick = {},
                        )
                    },
                ) {
                    Column {
                        Text("Several earlier lines of message text fill this bubble.")
                        Text("Hi", modifier = Modifier.testTag(LAST_BLOCK_TAG))
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val last = composeRule.onNodeWithTag(LAST_BLOCK_TAG).fetchSemanticsNode().boundsInRoot
        val edited =
            composeRule
                .onNodeWithText("A long translated edited label", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val time = composeRule.onNodeWithText("12:45", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("wrapped metadata must be below the body", edited.top >= last.bottom)
        assertTrue("the timestamp must follow the wrapped label", time.top >= edited.bottom)
    }

    /** Read More and recovery content stay before the terminal warning/time region. */
    @Test
    fun collapsedBodyKeepsWarningAndTimeAfterReadMore() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                BubbleCollapsibleFooterLayout(
                    modifier = Modifier.width(180.dp),
                    maxBodyHeight = 48.dp,
                    readMore = { Text("Read More") },
                    allowInline = false,
                    footer = {
                        MessageBubbleMetadataFooter(warning = "Delivery not confirmed", color = Color.Black) {
                            Text("12:45", style = MaterialTheme.typography.labelSmall)
                        }
                    },
                ) {
                    Box(Modifier.height(200.dp)) { Text("A long body") }
                }
            }
        }
        composeRule.waitForIdle()
        val readMore = composeRule.onNodeWithText("Read More").fetchSemanticsNode().boundsInRoot
        val warning = composeRule.onNodeWithText("Delivery not confirmed").fetchSemanticsNode().boundsInRoot
        val time = composeRule.onNodeWithText("12:45").fetchSemanticsNode().boundsInRoot
        assertTrue("warning must follow Read More", warning.top >= readMore.bottom)
        assertTrue("warning cannot extend below time", warning.bottom <= time.bottom + 1f)
    }

    /** An explicitly left-aligned final line exposes physical-right/logical-end confusion in RTL. */
    @Test
    fun leftAlignedLatinInRtlReservesItsActualLogicalExtent() {
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                WhiteNoiseTheme(darkTheme = false) {
                    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
                    BubbleFooterLayout(
                        modifier = Modifier.width(220.dp),
                        lastLineWidth = textLayout?.bubbleFooterLastLineWidth(LayoutDirection.Rtl),
                        lastLineBaseline = textLayout?.let { it.getLineBaseline(it.lineCount - 1).toInt() },
                        footer = { Text("12:45", style = MaterialTheme.typography.labelSmall) },
                    ) {
                        Text(
                            "Several wrapped lines of left-aligned Latin text.\nHi",
                            modifier = Modifier.width(220.dp).testTag(LAST_BLOCK_TAG),
                            textAlign = TextAlign.Left,
                            onTextLayout = { textLayout = it },
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val text = composeRule.onNodeWithTag(LAST_BLOCK_TAG).fetchSemanticsNode().boundsInRoot
        val time = composeRule.onNodeWithText("12:45").fetchSemanticsNode().boundsInRoot
        println("RTL explicitly left-aligned footer: text=$text, time=$time")
        assertTrue("the footer must follow a final line occupying its physical left edge", time.top >= text.bottom)
    }

    /** Default inline placement must recognize the wrapper's true first and terminal baselines. */
    @Test
    fun multilineWarningCannotRiseIntoBodyWithDefaultInlinePolicy() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                BubbleFooterLayout(
                    modifier = Modifier.width(220.dp),
                    lastLineWidth = 8,
                    footer = {
                        Box(Modifier.width(120.dp)) {
                            MessageBubbleMetadataFooter(warning = "Delivery not confirmed", color = Color.Black) {
                                Text("12:45", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    },
                ) {
                    Column {
                        Text("Earlier paragraph lines use the full bubble width before the final short word.")
                        Text("Hi", modifier = Modifier.testTag(LAST_BLOCK_TAG))
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val body = composeRule.onNodeWithTag(LAST_BLOCK_TAG).fetchSemanticsNode().boundsInRoot
        val warning = composeRule.onNodeWithText("Delivery not confirmed").fetchSemanticsNode().boundsInRoot
        val time = composeRule.onNodeWithText("12:45").fetchSemanticsNode().boundsInRoot
        assertTrue("the warning must actually wrap", warning.height > time.height)
        assertTrue("a multiline footer must remain below the whole body", warning.top >= body.bottom)
        assertTrue(
            "timestamp remains on the terminal warning line",
            kotlin.math.abs(warning.bottom - time.bottom) <= 1f,
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
