package dev.ipf.whitenoise.android.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import dev.ipf.marmotkit.MarkdownAlignmentFfi
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownListItemFfi
import dev.ipf.marmotkit.MarkdownListKindFfi
import dev.ipf.marmotkit.MarkdownTableCellFfi
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MarkdownMessageBodyTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun paragraph(text: String) = MarkdownBlockFfi.Paragraph(listOf(MarkdownInlineFfi.Text(text)))

    private fun tableCell(text: String) = MarkdownTableCellFfi(listOf(MarkdownInlineFfi.Text(text)))

    private fun render(blocks: List<MarkdownBlockFfi>) {
        composeRule.setContent {
            WhiteNoiseTheme {
                MarkdownMessageBody(MarkdownDocumentFfi(blocks = blocks, truncated = false))
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun messageBodyCapsTopLevelRenderedBlocks() {
        render(List(MARKDOWN_MAX_CONTAINER_SIBLINGS + 2) { paragraph("block-$it") })

        composeRule.onAllNodesWithText("block-0").assertCountEquals(1)
        composeRule.onAllNodesWithText("block-${MARKDOWN_MAX_CONTAINER_SIBLINGS - 1}").assertCountEquals(1)
        composeRule.onAllNodesWithText("block-$MARKDOWN_MAX_CONTAINER_SIBLINGS").assertCountEquals(0)
        composeRule.onAllNodesWithText("…").assertCountEquals(1)
    }

    @Test
    fun messageBodyCapsBlockQuoteChildren() {
        render(
            listOf(
                MarkdownBlockFfi.BlockQuote(
                    List(MARKDOWN_MAX_CONTAINER_SIBLINGS + 2) { paragraph("quote-$it") },
                ),
            ),
        )

        composeRule.onAllNodesWithText("quote-0").assertCountEquals(1)
        composeRule.onAllNodesWithText("quote-${MARKDOWN_MAX_CONTAINER_SIBLINGS - 1}").assertCountEquals(1)
        composeRule.onAllNodesWithText("quote-$MARKDOWN_MAX_CONTAINER_SIBLINGS").assertCountEquals(0)
        composeRule.onAllNodesWithText("…").assertCountEquals(1)
    }

    @Test
    fun messageBodyCapsListItems() {
        render(
            listOf(
                MarkdownBlockFfi.ListBlock(
                    kind = MarkdownListKindFfi.Bullet(marker = "-"),
                    tight = true,
                    items =
                        List(MARKDOWN_MAX_CONTAINER_SIBLINGS + 2) { index ->
                            MarkdownListItemFfi(blocks = listOf(paragraph("item-$index")), checked = null)
                        },
                ),
            ),
        )

        composeRule.onAllNodesWithText("item-0").assertCountEquals(1)
        composeRule.onAllNodesWithText("item-${MARKDOWN_MAX_CONTAINER_SIBLINGS - 1}").assertCountEquals(1)
        composeRule.onAllNodesWithText("item-$MARKDOWN_MAX_CONTAINER_SIBLINGS").assertCountEquals(0)
        composeRule.onAllNodesWithText("…").assertCountEquals(1)
    }

    @Test
    fun messageBodyCapsTableRows() {
        render(
            listOf(
                MarkdownBlockFfi.Table(
                    alignments = listOf(MarkdownAlignmentFfi.NONE),
                    header = listOf(tableCell("header")),
                    rows = List(MARKDOWN_MAX_CONTAINER_SIBLINGS + 2) { listOf(tableCell("row-$it")) },
                ),
            ),
        )

        composeRule.onAllNodesWithText("row-0").assertCountEquals(1)
        composeRule.onAllNodesWithText("row-${MARKDOWN_MAX_CONTAINER_SIBLINGS - 1}").assertCountEquals(1)
        composeRule.onAllNodesWithText("row-$MARKDOWN_MAX_CONTAINER_SIBLINGS").assertCountEquals(0)
        composeRule.onAllNodesWithText("…").assertCountEquals(1)
    }
}
