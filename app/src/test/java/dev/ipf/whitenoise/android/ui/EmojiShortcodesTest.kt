package dev.ipf.whitenoise.android.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.whitenoise.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiShortcodesTest {
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
            val rendered = EmojiShortcodes.annotate(AnnotatedString(source))
            assertEquals(source, rendered.text)
            assertEquals(
                body,
                listOf(source.length - 4),
                rendered.getStringAnnotations(0, rendered.length).map { it.start },
            )
        }
    }

    @Test
    fun everyShortcodeKeepsTextStylesLinksAndOffsets() {
        val source =
            buildAnnotatedString {
                withLink(LinkAnnotation.Url("https://example.com/:wn:")) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(":marmot::wn::wn: :WN: :unknown: 12:30 :a b:")
                    }
                }
            }
        val rendered = EmojiShortcodes.annotate(source)
        assertEquals(source.text, rendered.text)
        assertEquals(source.spanStyles, rendered.spanStyles)
        assertEquals(source.getLinkAnnotations(0, source.length), rendered.getLinkAnnotations(0, rendered.length))
        val shortcodeRanges = rendered.getStringAnnotations(0, rendered.length).map { it.start to it.end }
        // Undefined codes are marked too: they have no inline content, so they draw as text.
        assertEquals(listOf(0 to 8, 8 to 12, 12 to 16, 17 to 21, 22 to 31), shortcodeRanges)
        assertEquals(rendered, EmojiShortcodes.annotate(rendered))
    }

    @Test
    fun shortcodeAlphabetAndLength() {
        assertTrue(EmojiShortcodes.isShortcode(":party-parrot_2:"))
        assertTrue(EmojiShortcodes.isShortcode(":${"a".repeat(64)}:"))
        assertFalse(EmojiShortcodes.isShortcode(":${"a".repeat(65)}:"))
        assertFalse(EmojiShortcodes.isShortcode("::"))
        assertFalse(EmojiShortcodes.isShortcode(":a b:"))
        assertFalse(EmojiShortcodes.isShortcode(":café:"))
        assertFalse(EmojiShortcodes.isShortcode("party"))
    }

    @Test
    fun userFileBeatsReceivedArtworkWhichBeatsBuiltIn() {
        val received = EmojiArt.Bundled(R.drawable.ic_image)
        assertEquals(
            EmojiArt.Bundled(R.drawable.builtin_emoji_wn),
            EmojiShortcodes.art(":wn:", CustomEmojiSet.Empty, emptyMap()),
        )
        assertSame(received, EmojiShortcodes.art(":wn:", CustomEmojiSet.Empty, mapOf(":wn:" to received)))
        assertNull(EmojiShortcodes.art(":party:", CustomEmojiSet.Empty, mapOf(":wn:" to received)))
        assertNull(EmojiShortcodes.art(":WN:", CustomEmojiSet.Empty, emptyMap()))
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
        val rendered = EmojiShortcodes.annotate(AnnotatedString(source))
        assertEquals(source, rendered.text)
        val shortcodeStarts = rendered.getStringAnnotations(0, rendered.length).map { it.start }
        assertEquals(listOf(0, source.indexOf(":wn:\n~~~")), shortcodeStarts)
        assertTrue(EmojiShortcodes.annotate(AnnotatedString("```\n:wn:")).getStringAnnotations(0, 8).isEmpty())
    }
}
