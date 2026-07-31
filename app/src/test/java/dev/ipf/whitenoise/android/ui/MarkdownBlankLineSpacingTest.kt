package dev.ipf.whitenoise.android.ui

import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Authored blank runs must survive to the render layer (#1719). The parser
 * supplies `blankLinesBefore` per top-level block; these cover the mapping from
 * that array to extra vertical space, including the source-index mapping that a
 * `<details>` group's variable block span would otherwise break.
 */
class MarkdownBlankLineSpacingTest {
    private fun paragraph(text: String) = MarkdownBlockFfi.Paragraph(listOf(MarkdownInlineFfi.Text(text)))

    @Test
    fun oneBlankLineAddsNothingBeyondTheOrdinaryParagraphGap() {
        assertEquals(0, markdownExtraBlankLines(byteArrayOf(0, 1), 1))
    }

    @Test
    fun twoBlankLinesAddOneExtraGap() {
        assertEquals(1, markdownExtraBlankLines(byteArrayOf(0, 2), 1))
    }

    @Test
    fun severalBlankLinesScaleWithTheAuthoredCount() {
        assertEquals(3, markdownExtraBlankLines(byteArrayOf(0, 4), 1))
    }

    @Test
    fun hostileBlankRunIsBoundedByTheRenderCeiling() {
        assertEquals(
            MARKDOWN_MAX_EXTRA_BLANK_LINES,
            markdownExtraBlankLines(byteArrayOf(0, 120), 1),
        )
    }

    @Test
    fun missingOrShortMetadataDegradesToUniformSpacing() {
        // Legacy records and parse failures carry an empty array.
        assertEquals(0, markdownExtraBlankLines(ByteArray(0), 1))
        // Fewer entries than blocks must not throw.
        assertEquals(0, markdownExtraBlankLines(byteArrayOf(0), 5))
        assertEquals(0, markdownExtraBlankLines(byteArrayOf(0, 3), null))
    }

    @Test
    fun negativeByteFromAMisbehavingProducerCannotShrinkSpacing() {
        assertEquals(0, markdownExtraBlankLines(byteArrayOf(0, -5), 1))
    }

    @Test
    fun plainBlocksMapEachGroupToItsOwnSourceIndex() {
        val blocks = listOf(paragraph("first"), paragraph("second"), paragraph("third"))

        val grouped = groupMarkdownDetailsBlocksWithSource(blocks)

        assertEquals(listOf(0, 1, 2), grouped.sourceIndices)
        assertEquals(3, grouped.groups.size)
    }

    @Test
    fun detailsGroupAdvancesTheSourceIndexPastEveryBlockItSwallows() {
        val blocks =
            listOf(
                paragraph("before"),
                paragraph("<details><summary>more</summary>"),
                paragraph("hidden"),
                paragraph("</details>"),
                paragraph("after"),
            )

        val grouped = groupMarkdownDetailsBlocksWithSource(blocks)

        // The trailing paragraph is the 5th source block even though it is only
        // the 3rd render group — indexing blankLinesBefore by group position
        // would read the wrong entry.
        assertEquals(grouped.groups.size, grouped.sourceIndices.size)
        assertEquals(0, grouped.sourceIndices.first())
        assertEquals(blocks.lastIndex, grouped.sourceIndices.last())
    }

    @Test
    fun sourceIndicesStayConsistentWithTheUnindexedGrouping() {
        val blocks =
            listOf(
                paragraph("a"),
                paragraph("<details><summary>s</summary>"),
                paragraph("b"),
                paragraph("</details>"),
                paragraph("c"),
            )

        assertEquals(
            groupMarkdownDetailsBlocks(blocks).size,
            groupMarkdownDetailsBlocksWithSource(blocks).sourceIndices.size,
        )
    }
}
