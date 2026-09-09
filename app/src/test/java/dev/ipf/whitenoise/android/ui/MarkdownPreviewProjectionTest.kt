package dev.ipf.whitenoise.android.ui

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins range slicing against the styled text contract, independently of the shared AST walk. */
class MarkdownPreviewProjectionTest {
    @Test
    fun clippedEmptyStylesMatchComposeHalfOpenBoundaries() {
        val style = SpanStyle(fontWeight = FontWeight.Bold)
        val annotated =
            buildAnnotatedString {
                withStyle(style) { }
                append("a")
                withStyle(style) { }
                append("b")
                withStyle(style) { }
            }
        val projection =
            buildMarkdownPreviewText(captureStyles = true) {
                withStyle(MarkdownPreviewStyle.Bold) { }
                append("a")
                withStyle(MarkdownPreviewStyle.Bold) { }
                append("b")
                withStyle(MarkdownPreviewStyle.Bold) { }
            }
        for (start in 0..2) {
            for (end in start..2) {
                val expected = annotated.subSequence(start, end)
                val actual = projection.subSequence(start, end)
                assertEquals(expected.text, actual.text)
                assertEquals(
                    "Styles for [$start, $end)",
                    expected.spanStyles.map { MarkdownPreviewRange(MarkdownPreviewStyle.Bold, it.start, it.end) },
                    actual.ranges,
                )
            }
        }
    }
}
