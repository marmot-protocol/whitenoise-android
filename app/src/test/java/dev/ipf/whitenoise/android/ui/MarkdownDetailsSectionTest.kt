package dev.ipf.whitenoise.android.ui

import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownDetailsSectionTest {
    private fun paragraph(vararg lines: String): MarkdownBlockFfi.Paragraph {
        val inlines = mutableListOf<MarkdownInlineFfi>()
        lines.forEachIndexed { index, line ->
            if (index > 0) inlines += MarkdownInlineFfi.SoftBreak
            inlines += MarkdownInlineFfi.Text(line)
        }
        return MarkdownBlockFfi.Paragraph(inlines)
    }

    private fun heading(text: String) = MarkdownBlockFfi.Heading(1u, listOf(MarkdownInlineFfi.Text(text)))

    @Test
    fun groupsCanonicalMultiBlockDetailsWithNestedContent() {
        // The issue's shape: <details> + own-line <summary>, a blank line (so
        // the content is separate blocks), non-paragraph content, then a lone
        // </details>. All of it must fold into one collapsible.
        val head = heading("Section")
        val body = paragraph("first line", "second line")
        val groups =
            groupMarkdownDetailsBlocks(
                listOf(
                    paragraph("<details>", "<summary>Logs</summary>"),
                    head,
                    body,
                    paragraph("</details>"),
                ),
            )
        assertEquals(1, groups.size)
        val details = groups.single() as MarkdownRenderGroup.Details
        assertEquals("Logs", details.summary)
        assertEquals(listOf(head, body), details.content)
    }

    @Test
    fun closeTagTrailingContentStaysInsideTheGroup() {
        val groups =
            groupMarkdownDetailsBlocks(
                listOf(
                    paragraph("<details>"),
                    paragraph("hidden line</details>"),
                ),
            )
        val details = groups.single() as MarkdownRenderGroup.Details
        assertNull(details.summary)
        assertEquals(listOf(paragraph("hidden line")), details.content)
    }

    @Test
    fun nestedDetailsUseTheirMatchingCloseTags() {
        val nestedOpener = paragraph("<details>", "<summary>Inner</summary>")
        val nestedBody = paragraph("nested body")
        val nestedClose = paragraph("</details>")
        val groups =
            groupMarkdownDetailsBlocks(
                listOf(
                    paragraph("<details>", "<summary>Outer</summary>"),
                    nestedOpener,
                    nestedBody,
                    nestedClose,
                    paragraph("</details>"),
                ),
            )

        val outer = groups.single() as MarkdownRenderGroup.Details
        assertEquals("Outer", outer.summary)
        val inner = groupMarkdownDetailsBlocks(outer.content).single() as MarkdownRenderGroup.Details
        assertEquals("Inner", inner.summary)
        assertEquals(listOf(nestedBody), inner.content)
    }

    @Test
    fun selfContainedNestedDetailsDoesNotCloseItsOuterGroup() {
        val nested = paragraph("<details>", "<summary>Inner</summary>", "nested body", "</details>")
        val outerBody = paragraph("outer body after nested details")
        val groups =
            groupMarkdownDetailsBlocks(
                listOf(
                    paragraph("<details>", "<summary>Outer</summary>"),
                    nested,
                    outerBody,
                    paragraph("</details>"),
                ),
            )

        val outer = groups.single() as MarkdownRenderGroup.Details
        assertEquals("Outer", outer.summary)
        assertEquals(listOf(nested, outerBody), outer.content)
    }

    @Test
    fun unterminatedMultiBlockDetailsRendersEveryBlockLiterally() {
        val blocks =
            listOf(
                paragraph("<details>", "<summary>Never closed</summary>"),
                heading("orphan"),
            )
        val groups = groupMarkdownDetailsBlocks(blocks)
        assertTrue(groups.all { it is MarkdownRenderGroup.Plain })
        assertEquals(blocks, groups.map { (it as MarkdownRenderGroup.Plain).block })
    }

    @Test
    fun plainBlocksArePassedThroughUngrouped() {
        val blocks = listOf(paragraph("just prose"), heading("x"))
        val groups = groupMarkdownDetailsBlocks(blocks)
        assertEquals(blocks, groups.map { (it as MarkdownRenderGroup.Plain).block })
    }

    @Test
    fun selfContainedSingleParagraphIsNotMultiBlockGrouped() {
        // Handled inline by the block view via markdownDetailsSection, so the
        // grouper must leave it a Plain block, not swallow it.
        val single = paragraph("<details>", "<summary>x</summary>", "body", "</details>")
        val groups = groupMarkdownDetailsBlocks(listOf(single))
        assertTrue(groups.single() is MarkdownRenderGroup.Plain)
    }

    @Test
    fun extractsOwnLineSummaryAndContent() {
        val section =
            markdownDetailsSection(
                listOf(
                    MarkdownInlineFfi.Text("<details>"),
                    MarkdownInlineFfi.SoftBreak,
                    MarkdownInlineFfi.Text("<summary>Build log</summary>"),
                    MarkdownInlineFfi.SoftBreak,
                    MarkdownInlineFfi.Text("line one"),
                    MarkdownInlineFfi.SoftBreak,
                    MarkdownInlineFfi.Text("line two"),
                    MarkdownInlineFfi.SoftBreak,
                    MarkdownInlineFfi.Text("</details>"),
                ),
            )

        assertEquals("Build log", section?.summary)
        assertEquals(
            listOf(
                MarkdownInlineFfi.Text("line one"),
                MarkdownInlineFfi.SoftBreak,
                MarkdownInlineFfi.Text("line two"),
            ),
            section?.content,
        )
    }

    @Test
    fun extractsSummarySharingTheOpeningLine() {
        val section =
            markdownDetailsSection(
                listOf(
                    MarkdownInlineFfi.Text("<details><summary>Same line</summary>"),
                    MarkdownInlineFfi.SoftBreak,
                    MarkdownInlineFfi.Text("body"),
                    MarkdownInlineFfi.SoftBreak,
                    MarkdownInlineFfi.Text("</details>"),
                ),
            )

        assertEquals("Same line", section?.summary)
        assertEquals(listOf<MarkdownInlineFfi>(MarkdownInlineFfi.Text("body")), section?.content)
    }

    @Test
    fun missingSummaryYieldsNullSummary() {
        val section =
            markdownDetailsSection(
                listOf(
                    MarkdownInlineFfi.Text("<details>"),
                    MarkdownInlineFfi.SoftBreak,
                    MarkdownInlineFfi.Text("no summary here"),
                    MarkdownInlineFfi.SoftBreak,
                    MarkdownInlineFfi.Text("</details>"),
                ),
            )

        assertNull(section?.summary)
        assertEquals(listOf<MarkdownInlineFfi>(MarkdownInlineFfi.Text("no summary here")), section?.content)
    }

    @Test
    fun styledContentSurvivesExtraction() {
        val strong = MarkdownInlineFfi.Strong(listOf(MarkdownInlineFfi.Text("bold")))
        val section =
            markdownDetailsSection(
                listOf(
                    MarkdownInlineFfi.Text("<details>"),
                    MarkdownInlineFfi.SoftBreak,
                    MarkdownInlineFfi.Text("some "),
                    strong,
                    MarkdownInlineFfi.SoftBreak,
                    MarkdownInlineFfi.Text("</details>"),
                ),
            )

        assertEquals(listOf(MarkdownInlineFfi.Text("some "), strong), section?.content)
    }

    @Test
    fun tagsMatchCaseInsensitively() {
        val section =
            markdownDetailsSection(
                listOf(
                    MarkdownInlineFfi.Text("<DETAILS>"),
                    MarkdownInlineFfi.SoftBreak,
                    MarkdownInlineFfi.Text("shouty"),
                    MarkdownInlineFfi.SoftBreak,
                    MarkdownInlineFfi.Text("</Details>"),
                ),
            )

        assertNull(section?.summary)
        assertEquals(listOf<MarkdownInlineFfi>(MarkdownInlineFfi.Text("shouty")), section?.content)
    }

    @Test
    fun nonDetailsTextPassesThrough() {
        assertNull(markdownDetailsSection(listOf(MarkdownInlineFfi.Text("just some prose"))))
        assertNull(
            markdownDetailsSection(
                listOf(
                    MarkdownInlineFfi.Text("prefix <details>"),
                    MarkdownInlineFfi.SoftBreak,
                    MarkdownInlineFfi.Text("</details>"),
                ),
            ),
        )
    }

    @Test
    fun unterminatedBlockPassesThrough() {
        assertNull(
            markdownDetailsSection(
                listOf(
                    MarkdownInlineFfi.Text("<details>"),
                    MarkdownInlineFfi.SoftBreak,
                    MarkdownInlineFfi.Text("<summary>open</summary>"),
                    MarkdownInlineFfi.SoftBreak,
                    MarkdownInlineFfi.Text("never closed"),
                ),
            ),
        )
        assertNull(markdownDetailsSection(listOf(MarkdownInlineFfi.Text("<details>"))))
    }

    @Test
    fun closingTagMustEndTheParagraph() {
        assertNull(
            markdownDetailsSection(
                listOf(
                    MarkdownInlineFfi.Text("<details>"),
                    MarkdownInlineFfi.SoftBreak,
                    MarkdownInlineFfi.Text("</details>"),
                    MarkdownInlineFfi.SoftBreak,
                    MarkdownInlineFfi.Text("trailing prose"),
                ),
            ),
        )
    }
}
