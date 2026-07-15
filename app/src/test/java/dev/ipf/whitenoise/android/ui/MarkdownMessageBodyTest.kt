package dev.ipf.whitenoise.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.ipf.marmotkit.MarkdownAlignmentFfi
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownCodeBlockKindFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownListItemFfi
import dev.ipf.marmotkit.MarkdownListKindFfi
import dev.ipf.marmotkit.MarkdownTableCellFfi
import dev.ipf.whitenoise.android.ui.conversation.messages.rememberMovableBubbleBody
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
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

    private data class DetailsFixture(
        val blocks: List<MarkdownBlockFfi>,
        val source: String,
    )

    private fun details(
        summary: String,
        body: List<MarkdownBlockFfi>,
        bodySource: String,
        open: Boolean = false,
        tagAttributes: String = if (open) " open" else "",
    ): DetailsFixture =
        DetailsFixture(
            blocks =
                listOf(
                    MarkdownBlockFfi.Paragraph(
                        listOf(
                            MarkdownInlineFfi.Text("<details$tagAttributes>"),
                            MarkdownInlineFfi.SoftBreak,
                            MarkdownInlineFfi.Text("<summary>$summary</summary>"),
                        ),
                    ),
                ) + body +
                    MarkdownBlockFfi.Paragraph(listOf(MarkdownInlineFfi.Text("</details>"))),
            source =
                "<details$tagAttributes>\n" +
                    "<summary>$summary</summary>\n\n" +
                    "$bodySource\n\n" +
                    "</details>",
        )

    private fun stateDescription(value: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)

    private fun buttonRole() = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    private fun render(
        blocks: List<MarkdownBlockFfi>,
        source: String? = null,
        truncated: Boolean = false,
        onDisclosureStateChange: (() -> Unit)? = null,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                MarkdownMessageBody(
                    document = MarkdownDocumentFfi(blocks = blocks, truncated = truncated),
                    source = source,
                    onDisclosureStateChange = onDisclosureStateChange,
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun detailsAreCollapsedByDefaultAndExpandFromAccessibleSummaryButton() {
        val details = details(summary = "Spoiler", body = listOf(paragraph("secret")), bodySource = "secret")
        render(details.blocks, details.source)

        val summary = composeRule.onNodeWithText("Spoiler")
        summary.assertExists().assert(buttonRole()).assert(stateDescription("collapsed"))
        composeRule.onNodeWithText("secret").assertDoesNotExist()
        composeRule.onAllNodesWithText("<details>").assertCountEquals(0)
        composeRule.onAllNodesWithText("</details>").assertCountEquals(0)

        summary.performClick()

        summary.assert(stateDescription("expanded"))
        composeRule.onNodeWithText("secret").assertExists()
    }

    @Test
    fun detailsSummaryFlattensInlineMarkdownToPlainButtonText() {
        val blocks =
            listOf(
                MarkdownBlockFfi.Paragraph(
                    listOf(
                        MarkdownInlineFfi.Text("<details>"),
                        MarkdownInlineFfi.SoftBreak,
                        MarkdownInlineFfi.Text("<summary>"),
                        MarkdownInlineFfi.Strong(listOf(MarkdownInlineFfi.Text("bold"))),
                        MarkdownInlineFfi.Text(" and "),
                        MarkdownInlineFfi.Emph(listOf(MarkdownInlineFfi.Text("italic"))),
                        MarkdownInlineFfi.Text(" with "),
                        MarkdownInlineFfi.Link(
                            dest = "https://example.com",
                            title = null,
                            children = listOf(MarkdownInlineFfi.Code("linked")),
                        ),
                        MarkdownInlineFfi.Text("</summary>"),
                    ),
                ),
                paragraph("secret"),
                MarkdownBlockFfi.Paragraph(listOf(MarkdownInlineFfi.Text("</details>"))),
            )
        val source =
            "<details>\n<summary>**bold** and *italic* with [`linked`](https://example.com)</summary>\n\nsecret\n\n</details>"

        render(blocks, source)

        composeRule.onNodeWithText("bold and italic with linked").assert(buttonRole()).assert(stateDescription("collapsed"))
        composeRule.onNodeWithText("secret").assertDoesNotExist()
    }

    @Test
    fun detailsOpenAttributeStartsExpandedAndRendersNestedMarkdownBlocks() {
        val details =
            details(
                summary = "Build log",
                open = true,
                body =
                    listOf(
                        MarkdownBlockFfi.Heading(2u, listOf(MarkdownInlineFfi.Text("Result"))),
                        MarkdownBlockFfi.CodeBlock(
                            kind = MarkdownCodeBlockKindFfi.FENCED,
                            info = "text",
                            content = "all checks passed\n",
                        ),
                    ),
                bodySource = "## Result\n\n```text\nall checks passed\n```",
            )
        render(details.blocks, details.source)

        composeRule.onNodeWithText("Build log").assert(buttonRole()).assert(stateDescription("expanded"))
        composeRule.onNodeWithText("Result").assertExists()
        composeRule.onNodeWithText("all checks passed").assertExists()
    }

    @Test
    fun openTextInsideAnotherAttributeDoesNotExpandDetails() {
        val details =
            details(
                summary = "Metadata",
                body = listOf(paragraph("hidden")),
                bodySource = "hidden",
                tagAttributes = " title=\" open \"",
            )
        render(details.blocks, details.source)

        composeRule.onNodeWithText("Metadata").assert(stateDescription("collapsed"))
        composeRule.onNodeWithText("hidden").assertDoesNotExist()
    }

    @Test
    fun malformedDetailsAttributesRemainLiteralText() {
        val details =
            details(
                summary = "Malformed attribute",
                body = listOf(paragraph("visible")),
                bodySource = "visible",
                tagAttributes = " title=",
            )
        render(details.blocks, details.source)

        composeRule.onNodeWithText("<details title=>", substring = true).assertExists()
        composeRule.onNodeWithText("visible").assertExists()
    }

    @Test
    fun truncatedDocumentLeavesDetailsLiteral() {
        val details = details(summary = "Truncated", body = listOf(paragraph("visible")), bodySource = "visible")
        render(details.blocks, details.source, truncated = true)

        composeRule.onNodeWithText("<details>", substring = true).assertExists()
        composeRule.onNodeWithText("visible").assertExists()
    }

    @Test
    fun nestedDetailsRetainIndependentCollapsedState() {
        val inner = details(summary = "Inner", body = listOf(paragraph("deep secret")), bodySource = "deep secret")
        val outer =
            details(
                summary = "Outer",
                body = listOf(paragraph("intro")) + inner.blocks,
                bodySource = "intro\n\n${inner.source}",
            )
        render(outer.blocks, outer.source)

        composeRule.onNodeWithText("Inner").assertDoesNotExist()
        composeRule.onNodeWithText("Outer").performClick()
        composeRule.onNodeWithText("intro").assertExists()
        composeRule.onNodeWithText("Inner").assert(stateDescription("collapsed")).performClick()
        composeRule.onNodeWithText("deep secret").assertExists()
    }

    @Test
    fun escapedDetailsRemainLiteralText() {
        val escaped = details(summary = "Escaped", body = listOf(paragraph("secret")), bodySource = "secret")
        render(escaped.blocks, escaped.source.replaceFirst("<details>", "\\<details>"))

        composeRule.onNodeWithText("<details>", substring = true).assertExists()
        composeRule.onNodeWithText("Escaped").assertDoesNotExist()
        composeRule.onNodeWithText("secret").assertExists()
    }

    @Test
    fun entityEncodedDetailsRemainLiteralText() {
        val encoded = details(summary = "Entity", body = listOf(paragraph("visible")), bodySource = "visible")
        render(
            encoded.blocks,
            encoded.source
                .replace("<details>", "&LT;details&GT;")
                .replace("<summary>Entity</summary>", "&LT;summary&GT;Entity&LT;/summary&GT;")
                .replace("</details>", "&LT;/details&GT;"),
        )

        composeRule.onNodeWithText("<details>", substring = true).assertExists()
        composeRule.onNodeWithText("Entity").assertDoesNotExist()
        composeRule.onNodeWithText("visible").assertExists()
    }

    @Test
    fun malformedDetailsLikeTagRemainsLiteralText() {
        val malformed = details(summary = "Malformed", body = listOf(paragraph("visible")), bodySource = "visible")
        val blocks =
            malformed.blocks.toMutableList().also {
                it[0] =
                    MarkdownBlockFfi.Paragraph(
                        listOf(
                            MarkdownInlineFfi.Text("<details-open>"),
                            MarkdownInlineFfi.SoftBreak,
                            MarkdownInlineFfi.Text("<summary>Malformed</summary>"),
                        ),
                    )
            }
        render(blocks, malformed.source.replaceFirst("<details>", "<details-open>"))

        composeRule.onNodeWithText("<details-open>", substring = true).assertExists()
        composeRule.onNodeWithText("Malformed").assertDoesNotExist()
        composeRule.onNodeWithText("visible").assertExists()
    }

    @Test
    fun malformedNestedDetailsDoesNotPrematurelyCloseOuterSection() {
        val malformedInner = details(summary = "", body = listOf(paragraph("inside")), bodySource = "inside")
        val outer =
            details(
                summary = "Outer",
                body = malformedInner.blocks + paragraph("after nested"),
                bodySource = "${malformedInner.source}\n\nafter nested",
            )
        render(outer.blocks, outer.source)

        composeRule.onNodeWithText("after nested").assertDoesNotExist()
        composeRule.onNodeWithText("Outer").performClick()
        composeRule.onNodeWithText("after nested").assertExists()
    }

    @Test
    fun detailsToggleNotifiesContainerToRecalculateOverflow() {
        val details = details(summary = "Log", body = listOf(paragraph("body")), bodySource = "body")
        var stateChanges = 0
        render(details.blocks, details.source) { stateChanges += 1 }

        composeRule.onNodeWithText("Log").performClick()

        composeRule.runOnIdle { assertEquals(1, stateChanges) }
    }

    @Test
    fun detailsStateSurvivesMoveBetweenFooterLayouts() {
        val details = details(summary = "Moving log", body = listOf(paragraph("body")), bodySource = "body")
        composeRule.setContent {
            WhiteNoiseTheme {
                var collapsedLayout by remember { mutableStateOf(false) }
                val body =
                    rememberMovableBubbleBody {
                        MarkdownMessageBody(
                            document = MarkdownDocumentFfi(details.blocks, truncated = false),
                            source = details.source,
                            onDisclosureStateChange = { collapsedLayout = !collapsedLayout },
                        )
                    }
                if (collapsedLayout) Box { body() } else Column { body() }
            }
        }

        composeRule.onNodeWithText("Moving log").performClick()

        composeRule.onNodeWithText("Moving log").assert(stateDescription("expanded"))
        composeRule.onNodeWithText("body").assertExists()
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
