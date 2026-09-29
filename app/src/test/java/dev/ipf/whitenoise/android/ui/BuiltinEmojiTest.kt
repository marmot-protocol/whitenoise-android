package dev.ipf.whitenoise.android.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import dev.ipf.marmotkit.MarkdownInlineFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltinEmojiTest {
    @Test
    fun closingFenceBoundaries() {
        val fencedBodies =
            listOf(
                "```\n:wn:\n``` trailing text\n:marmot:\n```",
                "````\n:wn:\n```\n:marmot:\n````",
                "~~~~\n:wn:\n~~~\n:marmot:\n~~~~",
                "~~~\n:wn:\n~~~ trailing text\n:marmot:\n~~~~~ \t",
                "```\n:wn:\n~~~\n:marmot:\n`````\t",
            )
        for (body in fencedBodies) {
            val source = "$body\n:wn:"
            val rendered = BuiltinEmoji.annotate(AnnotatedString(source))
            assertEquals(source, rendered.text)
            assertEquals(
                body,
                listOf(source.length - 4),
                rendered.getStringAnnotations(0, rendered.length).map { it.start },
            )
        }
    }

    @Test
    fun exactShortcodesKeepTextStylesLinksAndOffsets() {
        val source =
            buildAnnotatedString {
                withLink(LinkAnnotation.Url("https://example.com/:wn:")) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(":marmot::wn::wn: :WN: :unknown:")
                    }
                }
            }
        val rendered = BuiltinEmoji.annotate(source)
        assertEquals(source.text, rendered.text)
        assertEquals(source.spanStyles, rendered.spanStyles)
        assertEquals(source.getLinkAnnotations(0, source.length), rendered.getLinkAnnotations(0, rendered.length))
        assertEquals(listOf(0 to 8, 8 to 12, 12 to 16), rendered.getStringAnnotations(0, rendered.length).map { it.start to it.end })
        assertEquals(rendered, BuiltinEmoji.annotate(rendered))
    }

    @Test
    fun markdownCodeRemainsLiteralEvenWithoutCodeStyle() {
        val rendered =
            markdownInlinesToAnnotatedString(
                listOf(MarkdownInlineFfi.Text(":wn:"), MarkdownInlineFfi.Code(":marmot:")),
                SpanStyle(),
                SpanStyle(),
            )
        assertEquals(":wn::marmot:", rendered.text)
        assertEquals(listOf(0 to 4), rendered.getStringAnnotations(0, rendered.length).map { it.start to it.end })
    }

    @Test
    fun plaintextCodeSpansAndFencesStayLiteral() {
        val source = ":wn: `:wn:`\n```\n:marmot:\n```\n:wn:\n~~~\n:wn:\n~~~"
        val rendered = BuiltinEmoji.annotate(AnnotatedString(source))
        assertEquals(source, rendered.text)
        assertEquals(listOf(0, source.indexOf(":wn:\n~~~")), rendered.getStringAnnotations(0, rendered.length).map { it.start })
        assertTrue(BuiltinEmoji.annotate(AnnotatedString("```\n:wn:")).getStringAnnotations(0, 8).isEmpty())
    }
}
