package dev.ipf.whitenoise.android.ui.conversation.messages

import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerSendMarkdownParagraphTest {
    /** One plain paragraph split by soft breaks still qualifies as a single plain send paragraph. */
    @Test fun ordinaryParagraphWithLineBreaksHasOneRenderedTextLeaf() {
        assertTrue(
            isSinglePlainSendParagraph(
                document(
                    listOf(
                        MarkdownInlineFfi.Text("First"),
                        MarkdownInlineFfi.SoftBreak,
                        MarkdownInlineFfi.Text("Second"),
                    ),
                ),
            ),
        )
    }

    /** Inline formatting or a second paragraph disqualifies the document from send motion. */
    @Test fun formattedAndMultipleParagraphsKeepDirectPresentation() {
        val strong = MarkdownInlineFfi.Strong(listOf(MarkdownInlineFfi.Text("Bold")))
        assertFalse(isSinglePlainSendParagraph(document(listOf(strong))))
        val paragraph = MarkdownBlockFfi.Paragraph(listOf(MarkdownInlineFfi.Text("Text")))
        assertFalse(
            isSinglePlainSendParagraph(
                MarkdownDocumentFfi(
                    blocks = listOf(paragraph, paragraph),
                    truncated = false,
                    blankLinesBefore = ByteArray(0),
                ),
            ),
        )
    }

    /** Truncation or leading blank lines disqualify an otherwise plain paragraph from send motion. */
    @Test fun truncatedAndLeadingBlankLineDocumentsKeepDirectPresentation() {
        val plain = document(listOf(MarkdownInlineFfi.Text("Text")))
        assertFalse(isSinglePlainSendParagraph(plain.copy(truncated = true)))
        assertFalse(isSinglePlainSendParagraph(plain.copy(blankLinesBefore = byteArrayOf(1))))
    }

    /** A complete one-paragraph document holding [inlines]. */
    private fun document(inlines: List<MarkdownInlineFfi>): MarkdownDocumentFfi {
        val paragraph = MarkdownBlockFfi.Paragraph(inlines)
        return MarkdownDocumentFfi(blocks = listOf(paragraph), truncated = false, blankLinesBefore = ByteArray(0))
    }
}
