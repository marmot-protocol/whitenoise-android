package dev.ipf.whitenoise.android.ui

import androidx.compose.ui.text.SpanStyle
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownTimestampStyleFfi
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTimestampCompatibilityTest {
    /** New native nodes retain the literal text across bubbles, previews, link labels and speech. */
    @Test
    fun `all timestamp styles remain readable in every text projection`() {
        val styles =
            listOf(
                MarkdownTimestampStyleFfi.SHORT_TIME to 't',
                MarkdownTimestampStyleFfi.LONG_TIME to 'T',
                MarkdownTimestampStyleFfi.SHORT_DATE to 'd',
                MarkdownTimestampStyleFfi.LONG_DATE to 'D',
                MarkdownTimestampStyleFfi.SHORT_DATE_TIME to 'f',
                MarkdownTimestampStyleFfi.LONG_DATE_TIME to 'F',
                MarkdownTimestampStyleFfi.COMPACT_DATE_TIME to 's',
                MarkdownTimestampStyleFfi.COMPACT_DATE_TIME_SECONDS to 'S',
                MarkdownTimestampStyleFfi.RELATIVE to 'R',
            )
        for ((style, token) in styles) {
            val timestamp = MarkdownInlineFfi.Timestamp(-42, style)
            val literal = "<t:-42:$token>"
            assertProjectionsMatch(timestamp, literal)
        }
    }

    /** Signed seconds remain lossless without multiplying into an overflowing millisecond value. */
    @Test
    fun `extreme timestamps remain visible and previews obey the text limit`() {
        for (seconds in listOf(Long.MIN_VALUE, Long.MAX_VALUE)) {
            val timestamp = MarkdownInlineFfi.Timestamp(seconds, MarkdownTimestampStyleFfi.RELATIVE)
            val literal = "<t:$seconds:R>"
            assertProjectionsMatch(timestamp, literal)
            assertEquals(literal.take(12), markdownDocumentToPreviewText(document(timestamp), maxLength = 12))
        }
    }

    /** Compares typed input with the literal input supplied by the previously pinned parser. */
    private fun assertProjectionsMatch(
        timestamp: MarkdownInlineFfi.Timestamp,
        literal: String,
    ) {
        val typed = document(timestamp)
        val previous = document(MarkdownInlineFfi.Text(literal))
        assertEquals(literal, markdownInlinesToAnnotatedString(listOf(timestamp), SpanStyle(), SpanStyle()).text)
        assertEquals(literal, markdownInlinePlainText(listOf(timestamp)))
        assertEquals(literal, markdownDocumentToPreviewText(typed))
        assertEquals(literal, markdownDocumentToPreviewAnnotatedString(typed, SpanStyle()).text)
        assertEquals(markdownDocumentToSpeakableText(previous), markdownDocumentToSpeakableText(typed))
    }

    /** Wraps a single native inline without requiring a runtime or network connection. */
    private fun document(inline: MarkdownInlineFfi) =
        MarkdownDocumentFfi(
            blocks = listOf(MarkdownBlockFfi.Paragraph(listOf(inline))),
            truncated = false,
            blankLinesBefore = byteArrayOf(),
        )
}
