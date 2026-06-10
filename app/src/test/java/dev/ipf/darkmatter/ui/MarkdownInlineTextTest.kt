package dev.ipf.darkmatter.ui

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import dev.ipf.marmotkit.MarkdownAutolinkKindFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownListKindFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownInlineTextTest {
    private val codeStyle = SpanStyle(fontFamily = FontFamily.Monospace)
    private val linkStyle = SpanStyle(textDecoration = TextDecoration.Underline)

    private fun build(inlines: List<MarkdownInlineFfi>) = markdownInlinesToAnnotatedString(inlines, codeStyle, linkStyle)

    @Test
    fun plainTextAndBreaksConcatenate() {
        val annotated =
            build(
                listOf(
                    MarkdownInlineFfi.Text("first"),
                    MarkdownInlineFfi.SoftBreak,
                    MarkdownInlineFfi.Text("second"),
                    MarkdownInlineFfi.HardBreak,
                    MarkdownInlineFfi.Text("third"),
                ),
            )
        assertEquals("first\nsecond\nthird", annotated.text)
        assertTrue(annotated.spanStyles.isEmpty())
    }

    @Test
    fun strongAppliesBoldOverItsRange() {
        val annotated =
            build(
                listOf(
                    MarkdownInlineFfi.Text("a "),
                    MarkdownInlineFfi.Strong(listOf(MarkdownInlineFfi.Text("bold"))),
                ),
            )
        assertEquals("a bold", annotated.text)
        val range = annotated.spanStyles.single()
        assertEquals(FontWeight.Bold, range.item.fontWeight)
        assertEquals(2, range.start)
        assertEquals(6, range.end)
    }

    @Test
    fun emphasisNestedInsideStrongStacksBothStyles() {
        val annotated =
            build(
                listOf(
                    MarkdownInlineFfi.Strong(
                        listOf(
                            MarkdownInlineFfi.Text("very "),
                            MarkdownInlineFfi.Emph(listOf(MarkdownInlineFfi.Text("nested"))),
                        ),
                    ),
                ),
            )
        assertEquals("very nested", annotated.text)
        val bold = annotated.spanStyles.first { it.item.fontWeight == FontWeight.Bold }
        assertEquals(0, bold.start)
        assertEquals(11, bold.end)
        val italic = annotated.spanStyles.first { it.item.fontStyle == FontStyle.Italic }
        assertEquals(5, italic.start)
        assertEquals(11, italic.end)
    }

    @Test
    fun strikethroughAppliesLineThrough() {
        val annotated = build(listOf(MarkdownInlineFfi.Strikethrough(listOf(MarkdownInlineFfi.Text("gone")))))
        assertEquals("gone", annotated.text)
        assertEquals(
            TextDecoration.LineThrough,
            annotated.spanStyles
                .single()
                .item.textDecoration,
        )
    }

    @Test
    fun inlineCodeUsesTheProvidedCodeStyle() {
        val annotated =
            build(
                listOf(
                    MarkdownInlineFfi.Text("run "),
                    MarkdownInlineFfi.Code("ls -la"),
                ),
            )
        assertEquals("run ls -la", annotated.text)
        val range = annotated.spanStyles.single()
        assertEquals(FontFamily.Monospace, range.item.fontFamily)
        assertEquals(4, range.start)
        assertEquals(10, range.end)
    }

    @Test
    fun httpLinkCarriesUrlAnnotationAndLinkStyle() {
        val annotated =
            build(
                listOf(
                    MarkdownInlineFfi.Link(
                        dest = "https://example.com/page",
                        title = null,
                        children = listOf(MarkdownInlineFfi.Text("example")),
                    ),
                ),
            )
        assertEquals("example", annotated.text)
        val link = annotated.getLinkAnnotations(0, annotated.length).single()
        assertEquals("https://example.com/page", (link.item as LinkAnnotation.Url).url)
        assertEquals(0, link.start)
        assertEquals(7, link.end)
    }

    @Test
    fun nonHttpLinkRendersTextWithoutAnnotation() {
        val annotated =
            build(
                listOf(
                    MarkdownInlineFfi.Link(
                        dest = "javascript:alert(1)",
                        title = null,
                        children = listOf(MarkdownInlineFfi.Text("sneaky")),
                    ),
                ),
            )
        assertEquals("sneaky", annotated.text)
        assertTrue(annotated.getLinkAnnotations(0, annotated.length).isEmpty())
    }

    @Test
    fun uriAutolinkIsTappableButEmailAutolinkIsNot() {
        val annotated =
            build(
                listOf(
                    MarkdownInlineFfi.Autolink("https://example.com", MarkdownAutolinkKindFfi.URI),
                    MarkdownInlineFfi.Text(" "),
                    MarkdownInlineFfi.Autolink("user@example.com", MarkdownAutolinkKindFfi.EMAIL),
                ),
            )
        assertEquals("https://example.com user@example.com", annotated.text)
        val link = annotated.getLinkAnnotations(0, annotated.length).single()
        assertEquals("https://example.com", (link.item as LinkAnnotation.Url).url)
        assertEquals(0, link.start)
        assertEquals(19, link.end)
    }

    @Test
    fun onlyHttpAndHttpsSchemesAreOpenable() {
        assertTrue(isOpenableMarkdownLink("https://example.com"))
        assertTrue(isOpenableMarkdownLink("HTTP://EXAMPLE.COM"))
        assertTrue(!isOpenableMarkdownLink("javascript:alert(1)"))
        assertTrue(!isOpenableMarkdownLink("file:///etc/passwd"))
        assertTrue(!isOpenableMarkdownLink("nostr:npub1abc"))
        assertTrue(!isOpenableMarkdownLink("httpsx://example.com"))
        assertTrue(!isOpenableMarkdownLink(""))
    }

    @Test
    fun listMarkersFormatBulletAndOrderedKinds() {
        val bullet = MarkdownListKindFfi.Bullet(marker = "-")
        assertEquals("•", markdownListMarker(bullet, index = 0))
        assertEquals("•", markdownListMarker(bullet, index = 4))
        val ordered = MarkdownListKindFfi.Ordered(start = 3u, delimiter = ".")
        assertEquals("3.", markdownListMarker(ordered, index = 0))
        assertEquals("5.", markdownListMarker(ordered, index = 2))
    }
}
