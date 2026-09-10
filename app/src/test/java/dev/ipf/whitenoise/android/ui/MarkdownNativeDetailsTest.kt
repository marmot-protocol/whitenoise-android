package dev.ipf.whitenoise.android.ui

import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import org.junit.Assert.assertEquals
import org.junit.Test

/** Guards text projections of MDK's typed disclosure containers. */
class MarkdownNativeDetailsTest {
    /** Preview text preserves summary and nested body without exposing structural tags. */
    @Test
    fun previewIncludesSummaryAndBodyWithinBudget() {
        assertEquals("Summary Body", markdownDocumentToPreviewText(document()))
        assertEquals("Summary", markdownDocumentToPreviewText(document(), maxLength = 7))
    }

    /** Speech positions use the same summary/body leaf paths as the native block renderer. */
    @Test
    fun speechKeepsDisclosureLeafPaths() {
        val projection = markdownDocumentToSpeakableProjection(document())
        assertEquals("Summary. Body.", projection.text)
        assertEquals(mapOf("b0/summary/n0/n0" to "Summary", "b0/d/b0/n0" to "Body"), projection.visibleLeaves)
    }

    /** A deeply nested typed disclosure is limited by the existing traversal budget. */
    @Test
    fun nestedDetailsRemainBounded() {
        var block: MarkdownBlockFfi = MarkdownBlockFfi.Paragraph(listOf(MarkdownInlineFfi.Text("Hidden")))
        repeat(MARKDOWN_MAX_BLOCK_DEPTH + 2) {
            block = MarkdownBlockFfi.Details(emptyList(), false, listOf(block), byteArrayOf())
        }
        val document = MarkdownDocumentFfi(listOf(block), false, byteArrayOf())
        assertEquals("", markdownDocumentToPreviewText(document))
        assertEquals("", markdownDocumentToSpeakableText(document))
    }

    /** Builds a typed disclosure without requiring a native parser in host tests. */
    private fun document() =
        MarkdownDocumentFfi(
            blocks =
                listOf(
                    MarkdownBlockFfi.Details(
                        summary = listOf(MarkdownInlineFfi.Strong(listOf(MarkdownInlineFfi.Text("Summary")))),
                        open = false,
                        body = listOf(MarkdownBlockFfi.Paragraph(listOf(MarkdownInlineFfi.Text("Body")))),
                        blankLinesBefore = byteArrayOf(0),
                    ),
                ),
            truncated = false,
            blankLinesBefore = byteArrayOf(0),
        )
}
