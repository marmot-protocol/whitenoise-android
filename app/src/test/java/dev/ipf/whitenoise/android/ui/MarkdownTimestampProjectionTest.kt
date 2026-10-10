package dev.ipf.whitenoise.android.ui

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownLinkDestinationKindFfi
import dev.ipf.marmotkit.MarkdownTimestampStyleFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTimestampProjectionTest {
    private val timestamp = MarkdownInlineFfi.Timestamp(-1, MarkdownTimestampStyleFfi.SHORT_DATE_TIME)

    @Test
    fun nestedTimestampKeepsIndividualRangeAndEmphasis() {
        val rendered =
            markdownInlinesToAnnotatedString(
                listOf(
                    MarkdownInlineFfi.Text("before "),
                    MarkdownInlineFfi.Emph(listOf(timestamp)),
                    MarkdownInlineFfi.Text(" after"),
                    timestamp,
                ),
                SpanStyle(),
                SpanStyle(),
            )
        val ranges = rendered.getStringAnnotations(TIMESTAMP_TAG, 0, rendered.length)
        assertEquals(2, ranges.size)
        assertEquals(7, ranges.first().start)
        assertEquals("<t:-1:f>", ranges.first().item)
        assertTrue(
            rendered.spanStyles.any {
                it.item.fontStyle == FontStyle.Italic &&
                    it.start == ranges.first().start &&
                    it.end == ranges.first().end
            },
        )
        assertTrue(ranges.all { rendered.text[it.start] == '◷' && rendered.text[it.start + 1] == ' ' })
        assertFalse(rendered.text.contains("<t:"))
    }

    @Test
    fun surroundingLinkKeepsNavigationButDoesNotCoverTimestampClock() {
        val rendered =
            markdownInlinesToAnnotatedString(
                listOf(
                    MarkdownInlineFfi.Link(
                        "https://example.com",
                        null,
                        listOf(MarkdownInlineFfi.Text("at "), timestamp, MarkdownInlineFfi.Text(" here")),
                        MarkdownLinkDestinationKindFfi.WEB,
                    ),
                ),
                SpanStyle(),
                SpanStyle(),
            )
        val timestampRange = rendered.getStringAnnotations(TIMESTAMP_TAG, 0, rendered.length).single()
        assertTrue(rendered.getLinkAnnotations(timestampRange.start, timestampRange.start + 1).isEmpty())
        assertTrue(rendered.getLinkAnnotations(timestampRange.start + 2, timestampRange.end).isNotEmpty())
        assertTrue(rendered.getLinkAnnotations(0, 2).isNotEmpty())
        assertTrue(rendered.getLinkAnnotations(rendered.length - 4, rendered.length).isNotEmpty())
    }

    @Test
    fun literalCodeDoesNotAcquireTimestampDisclosure() {
        val rendered =
            markdownInlinesToAnnotatedString(listOf(MarkdownInlineFfi.Code("<t:-1:R>")), SpanStyle(), SpanStyle())
        assertEquals("<t:-1:R>", rendered.text)
        assertTrue(rendered.getStringAnnotations(TIMESTAMP_TAG, 0, rendered.length).isEmpty())
    }

    @Test
    fun clippedPreviewCarriesOnlyItsVisibleTokenRangeAndNoLink() {
        val document =
            MarkdownDocumentFfi(
                blocks = listOf(MarkdownBlockFfi.Paragraph(listOf(timestamp))),
                truncated = false,
                blankLinesBefore = ByteArray(0),
            )
        val rendered = markdownDocumentToPreviewAnnotatedString(document, SpanStyle(), maxLength = 5)
        assertEquals(5, rendered.length)
        val range = rendered.getStringAnnotations(TIMESTAMP_TAG, 0, rendered.length).single()
        assertEquals(0, range.start)
        assertEquals(5, range.end)
        assertTrue(rendered.getLinkAnnotations(0, rendered.length).isEmpty())
        assertFalse(rendered.text.contains("<t:"))
    }
}
