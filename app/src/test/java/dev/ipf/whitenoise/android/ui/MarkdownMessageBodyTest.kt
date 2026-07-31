package dev.ipf.whitenoise.android.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import dev.ipf.marmotkit.MarkdownAlignmentFfi
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownCodeBlockKindFfi
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
                MarkdownMessageBody(
                    MarkdownDocumentFfi(
                        blocks = blocks,
                        truncated = false,
                        blankLinesBefore = ByteArray(0),
                    ),
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun codeAndMathBlocksStripUnsafeCharactersAndTrimParserTrailingNewline() {
        render(
            listOf(
                MarkdownBlockFfi.CodeBlock(
                    kind = MarkdownCodeBlockKindFfi.FENCED,
                    info = "kotlin",
                    content = "pay\u202Ecod.exe\nline 2\n",
                ),
                MarkdownBlockFfi.MathBlock("x\u2066 + y\n"),
            ),
        )

        composeRule.onAllNodesWithText("paycod.exe\nline 2").assertCountEquals(1)
        composeRule.onAllNodesWithText("pay\u202Ecod.exe\nline 2\n").assertCountEquals(0)
        composeRule.onAllNodesWithText("x + y").assertCountEquals(1)
        composeRule.onAllNodesWithText("x\u2066 + y\n").assertCountEquals(0)
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
                    blankLinesBefore = ByteArray(0),
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
                            MarkdownListItemFfi(
                                blocks = listOf(paragraph("item-$index")),
                                checked = null,
                                blankLinesBefore = ByteArray(0),
                            )
                        },
                ),
            ),
        )

        composeRule.onAllNodesWithText("item-0").assertCountEquals(1)
        composeRule.onAllNodesWithText("item-${MARKDOWN_MAX_CONTAINER_SIBLINGS - 1}").assertCountEquals(1)
        composeRule.onAllNodesWithText("item-$MARKDOWN_MAX_CONTAINER_SIBLINGS").assertCountEquals(0)
        composeRule.onAllNodesWithText("…").assertCountEquals(1)
    }

    private fun table(rows: List<List<MarkdownTableCellFfi>>) =
        MarkdownBlockFfi.Table(
            alignments = listOf(MarkdownAlignmentFfi.NONE),
            header = listOf(tableCell("header")),
            rows = rows,
        )

    @Test
    fun headerWithNoBodyRowsRendersNoRuleAtAll() {
        render(listOf(table(rows = emptyList())))

        // A rule under the header would separate nothing.
        composeRule.onAllNodesWithTag(MARKDOWN_TABLE_DIVIDER_TAG).assertCountEquals(0)
    }

    @Test
    fun aWideHeaderStillLeavesBudgetForBodyRowsSoTheRuleRemains() {
        render(
            listOf(
                MarkdownBlockFfi.Table(
                    alignments = List(MARKDOWN_MAX_TABLE_COLUMNS) { MarkdownAlignmentFfi.NONE },
                    header = List(MARKDOWN_MAX_TABLE_CELLS + 1) { tableCell("header-$it") },
                    rows = listOf(listOf(tableCell("alpha"))),
                ),
            ),
        )

        // markdownVisibleTable caps each row at MARKDOWN_MAX_TABLE_COLUMNS, so a
        // header can never consume the whole cell budget and starve the body.
        composeRule.onAllNodesWithTag(MARKDOWN_TABLE_DIVIDER_TAG).assertCountEquals(1)
    }

    @Test
    fun singleBodyRowKeepsOnlyTheHeaderRuleWithNoTrailingRule() {
        render(listOf(table(listOf(listOf(tableCell("alpha"))))))

        composeRule.onAllNodesWithTag(MARKDOWN_TABLE_DIVIDER_TAG).assertCountEquals(1)
    }

    @Test
    fun threeBodyRowsSeparateTheHeaderAndEachAdjacentPair() {
        render(
            listOf(
                table(
                    listOf(
                        listOf(tableCell("alpha")),
                        listOf(tableCell("beta")),
                        listOf(tableCell("gamma")),
                    ),
                ),
            ),
        )

        // One rule under the header plus one between each body-row pair.
        composeRule.onAllNodesWithTag(MARKDOWN_TABLE_DIVIDER_TAG).assertCountEquals(3)
    }

    @Test
    fun wrappedRowContentTakesOneRuleForTheWholeRowNotOnePerLine() {
        render(
            listOf(
                table(
                    listOf(
                        listOf(tableCell("wrapped\nsecond line\nthird line")),
                        listOf(tableCell("beta")),
                    ),
                ),
            ),
        )

        composeRule.onAllNodesWithTag(MARKDOWN_TABLE_DIVIDER_TAG).assertCountEquals(2)
    }

    @Test
    fun emptyCellsStillSeparateAdjacentRows() {
        render(
            listOf(
                table(
                    listOf(
                        listOf(tableCell("")),
                        listOf(tableCell("beta")),
                    ),
                ),
            ),
        )

        composeRule.onAllNodesWithTag(MARKDOWN_TABLE_DIVIDER_TAG).assertCountEquals(2)
    }

    @Test
    fun truncatedTableKeepsTheElisionMarkerWithoutAnOrphanRule() {
        render(
            listOf(
                MarkdownBlockFfi.Table(
                    alignments = listOf(MarkdownAlignmentFfi.NONE),
                    header = listOf(tableCell("header")),
                    rows = List(MARKDOWN_MAX_CONTAINER_SIBLINGS + 2) { listOf(tableCell("row-$it")) },
                ),
            ),
        )

        // Header rule plus one between each visible body-row pair, and none
        // before the elision marker, which is not itself a row.
        val visibleRows = MARKDOWN_MAX_TABLE_CELLS - 1
        composeRule.onAllNodesWithTag(MARKDOWN_TABLE_DIVIDER_TAG).assertCountEquals(visibleRows)
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
        // The header and body share one area budget, so the one-cell header
        // leaves room for 255 one-cell body rows.
        composeRule.onAllNodesWithText("row-${MARKDOWN_MAX_TABLE_CELLS - 2}").assertCountEquals(1)
        composeRule.onAllNodesWithText("row-${MARKDOWN_MAX_TABLE_CELLS - 1}").assertCountEquals(0)
        composeRule.onAllNodesWithText("…").assertCountEquals(1)
    }

    @Test
    fun messageBodyCapsWideTableRows() {
        render(
            listOf(
                MarkdownBlockFfi.Table(
                    alignments = List(MARKDOWN_MAX_TABLE_COLUMNS + 2) { MarkdownAlignmentFfi.NONE },
                    header = listOf(tableCell("header")),
                    rows =
                        listOf(
                            List(MARKDOWN_MAX_TABLE_COLUMNS + 2) { tableCell("column-$it") },
                        ),
                ),
            ),
        )

        composeRule.onAllNodesWithText("column-${MARKDOWN_MAX_TABLE_COLUMNS - 1}").assertCountEquals(1)
        composeRule.onAllNodesWithText("column-$MARKDOWN_MAX_TABLE_COLUMNS").assertCountEquals(0)
        composeRule.onAllNodesWithText("…").assertCountEquals(1)
    }
}
