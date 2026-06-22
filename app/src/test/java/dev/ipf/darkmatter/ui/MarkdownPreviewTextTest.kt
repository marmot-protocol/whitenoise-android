package dev.ipf.darkmatter.ui

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import dev.ipf.marmotkit.MarkdownAlignmentFfi
import dev.ipf.marmotkit.MarkdownAutolinkKindFfi
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownCodeBlockKindFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownListItemFfi
import dev.ipf.marmotkit.MarkdownListKindFfi
import dev.ipf.marmotkit.MarkdownNostrEntityFfi
import dev.ipf.marmotkit.MarkdownNostrHrpFfi
import dev.ipf.marmotkit.MarkdownTableCellFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chat-list preview flattener: one inline run per document, blocks joined
 * by single spaces, links inert. Mirrors MarkdownInlineTextTest's conventions
 * for the bubble-side mapping.
 */
class MarkdownPreviewTextTest {
    private val codeStyle = SpanStyle(fontFamily = FontFamily.Monospace)

    private fun build(
        blocks: List<MarkdownBlockFfi>,
        maxLength: Int = 200,
    ) = markdownDocumentToPreviewAnnotatedString(MarkdownDocumentFfi(blocks), codeStyle, maxLength)

    private fun paragraph(text: String) = MarkdownBlockFfi.Paragraph(listOf(MarkdownInlineFfi.Text(text)))

    @Test
    fun emptyDocumentFlattensToEmptyString() {
        val annotated = build(emptyList())
        assertEquals("", annotated.text)
    }

    @Test
    fun deeplyNestedEmptyInlinesDoNotOverflowTheStack() {
        // #156 (inline arm): nested emphasis with no leaf text never spends the
        // length budget, so the inline depth cap is the only bound. Far past the
        // cap; must return, not StackOverflowError.
        var inline: MarkdownInlineFfi = MarkdownInlineFfi.Text("x")
        repeat(10_000) { inline = MarkdownInlineFfi.Emph(listOf(inline)) }
        val annotated = build(listOf(MarkdownBlockFfi.Paragraph(listOf(inline))))
        // Capped before reaching the innermost text; the point is it returns.
        assertEquals("", annotated.text)
    }

    @Test
    fun deeplyNestedEmptyQuotesDoNotOverflowTheStack() {
        // #156: a peer-crafted message of thousands of nested, empty block
        // quotes never spends the length budget, so the structural depth cap
        // is the only thing bounding the recursion. Far deeper than the cap;
        // must return, not StackOverflowError.
        var block: MarkdownBlockFfi = paragraph("deep")
        repeat(10_000) { block = MarkdownBlockFfi.BlockQuote(listOf(block)) }
        val annotated = build(listOf(block))
        // Capped before reaching the innermost paragraph — the point is that it
        // returns at all rather than crashing.
        assertEquals("", annotated.text)
    }

    @Test
    fun blocksJoinWithASingleSpace() {
        val annotated =
            build(
                listOf(
                    MarkdownBlockFfi.Heading(2u, listOf(MarkdownInlineFfi.Text("Title"))),
                    paragraph("first"),
                    paragraph("second"),
                ),
            )
        assertEquals("Title first second", annotated.text)
    }

    @Test
    fun thematicBreakContributesNothing() {
        val annotated =
            build(
                listOf(
                    paragraph("above"),
                    MarkdownBlockFfi.ThematicBreak,
                    paragraph("below"),
                ),
            )
        assertEquals("above below", annotated.text)
    }

    @Test
    fun inlineStylesSurviveFlattening() {
        val annotated =
            build(
                listOf(
                    MarkdownBlockFfi.Paragraph(
                        listOf(
                            MarkdownInlineFfi.Strong(listOf(MarkdownInlineFfi.Text("bold"))),
                            MarkdownInlineFfi.Text(" "),
                            MarkdownInlineFfi.Emph(listOf(MarkdownInlineFfi.Text("italic"))),
                            MarkdownInlineFfi.Text(" "),
                            MarkdownInlineFfi.Strikethrough(listOf(MarkdownInlineFfi.Text("gone"))),
                            MarkdownInlineFfi.Text(" "),
                            MarkdownInlineFfi.Code("ls"),
                        ),
                    ),
                ),
            )
        assertEquals("bold italic gone ls", annotated.text)
        val bold = annotated.spanStyles.first { it.item.fontWeight == FontWeight.Bold }
        assertEquals(0, bold.start)
        assertEquals(4, bold.end)
        val italic = annotated.spanStyles.first { it.item.fontStyle == FontStyle.Italic }
        assertEquals(5, italic.start)
        assertEquals(11, italic.end)
        val struck = annotated.spanStyles.first { it.item.textDecoration == TextDecoration.LineThrough }
        assertEquals(12, struck.start)
        assertEquals(16, struck.end)
        val code = annotated.spanStyles.first { it.item.fontFamily == FontFamily.Monospace }
        assertEquals(17, code.start)
        assertEquals(19, code.end)
    }

    @Test
    fun lineBreaksFlattenToSpaces() {
        val annotated =
            build(
                listOf(
                    MarkdownBlockFfi.Paragraph(
                        listOf(
                            MarkdownInlineFfi.Text("one"),
                            MarkdownInlineFfi.SoftBreak,
                            MarkdownInlineFfi.Text("two"),
                            MarkdownInlineFfi.HardBreak,
                            MarkdownInlineFfi.Text("three"),
                        ),
                    ),
                ),
            )
        assertEquals("one two three", annotated.text)
    }

    @Test
    fun codeBlockContentIsCodeStyledOnOneLine() {
        val annotated =
            build(
                listOf(
                    paragraph("look:"),
                    MarkdownBlockFfi.CodeBlock(
                        kind = MarkdownCodeBlockKindFfi.FENCED,
                        info = "kotlin",
                        content = "fun main() {\n    hi()\n}\n",
                    ),
                ),
            )
        assertEquals("look: fun main() { hi() }", annotated.text)
        val code = annotated.spanStyles.single()
        assertEquals(FontFamily.Monospace, code.item.fontFamily)
        assertEquals("look: ".length, code.start)
        assertEquals(annotated.length, code.end)
    }

    @Test
    fun linksRenderAsPlainUnannotatedText() {
        val annotated =
            build(
                listOf(
                    MarkdownBlockFfi.Paragraph(
                        listOf(
                            MarkdownInlineFfi.Link(
                                dest = "https://example.com/page",
                                title = null,
                                children = listOf(MarkdownInlineFfi.Text("example")),
                            ),
                            MarkdownInlineFfi.Text(" "),
                            MarkdownInlineFfi.Autolink("https://example.com", MarkdownAutolinkKindFfi.URI),
                        ),
                    ),
                ),
            )
        assertEquals("example https://example.com", annotated.text)
        assertTrue(annotated.getLinkAnnotations(0, annotated.length).isEmpty())
        // No underline / link styling either: the visible text is plain.
        assertTrue(annotated.spanStyles.isEmpty())
    }

    @Test
    fun imageRendersItsAltTextPlain() {
        val annotated =
            build(
                listOf(
                    MarkdownBlockFfi.Paragraph(
                        listOf(
                            MarkdownInlineFfi.Image(
                                dest = "https://example.com/cat.png",
                                title = null,
                                alt = listOf(MarkdownInlineFfi.Text("a cat")),
                            ),
                        ),
                    ),
                ),
            )
        assertEquals("a cat", annotated.text)
        assertTrue(annotated.getLinkAnnotations(0, annotated.length).isEmpty())
        assertTrue(annotated.spanStyles.isEmpty())
    }

    @Test
    fun quoteListAndTableContentsFlattenInOrder() {
        val annotated =
            build(
                listOf(
                    MarkdownBlockFfi.BlockQuote(listOf(paragraph("quoted"))),
                    MarkdownBlockFfi.ListBlock(
                        kind = MarkdownListKindFfi.Bullet(marker = "-"),
                        tight = true,
                        items =
                            listOf(
                                MarkdownListItemFfi(blocks = listOf(paragraph("alpha")), checked = null),
                                MarkdownListItemFfi(blocks = listOf(paragraph("beta")), checked = true),
                            ),
                    ),
                    MarkdownBlockFfi.Table(
                        alignments = listOf(MarkdownAlignmentFfi.NONE, MarkdownAlignmentFfi.NONE),
                        header =
                            listOf(
                                MarkdownTableCellFfi(listOf(MarkdownInlineFfi.Text("h1"))),
                                MarkdownTableCellFfi(listOf(MarkdownInlineFfi.Text("h2"))),
                            ),
                        rows =
                            listOf(
                                listOf(
                                    MarkdownTableCellFfi(listOf(MarkdownInlineFfi.Text("c1"))),
                                    MarkdownTableCellFfi(listOf(MarkdownInlineFfi.Text("c2"))),
                                ),
                            ),
                    ),
                ),
            )
        assertEquals("quoted alpha beta h1 h2 c1 c2", annotated.text)
    }

    @Test
    fun mentionsInPreviewsShowNamesOrShortenedBech32ButStayInert() {
        val knownNpub = "npub1" + "q".repeat(58)
        val unknownNpub = "npub1" + "z".repeat(58)
        val nevent = "nevent1" + "q".repeat(60)
        val annotated =
            markdownDocumentToPreviewAnnotatedString(
                document =
                    MarkdownDocumentFfi(
                        listOf(
                            MarkdownBlockFfi.Paragraph(
                                listOf(
                                    MarkdownInlineFfi.NostrMention(
                                        MarkdownNostrEntityFfi(MarkdownNostrHrpFfi.NPUB, knownNpub),
                                    ),
                                    MarkdownInlineFfi.Text(" "),
                                    MarkdownInlineFfi.NostrMention(
                                        MarkdownNostrEntityFfi(MarkdownNostrHrpFfi.NPUB, unknownNpub),
                                    ),
                                    MarkdownInlineFfi.Text(" "),
                                    MarkdownInlineFfi.NostrUri(
                                        MarkdownNostrEntityFfi(MarkdownNostrHrpFfi.NEVENT, nevent),
                                    ),
                                ),
                            ),
                        ),
                    ),
                codeStyle = codeStyle,
                mentionDisplayName = { bech32 -> "Alice".takeIf { bech32 == knownNpub } },
            )
        assertEquals("@Alice @npub1zzzzzzz…zzzzzz nevent1qqqqq…qqqqqq", annotated.text)
        // Previews never carry tap targets — not even for profile entities.
        assertTrue(annotated.getLinkAnnotations(0, annotated.length).isEmpty())
    }

    @Test
    fun documentMentionCollectorFindsNestedMentionsOnly() {
        val npub = "npub1" + "q".repeat(58)
        val note = "note1" + "q".repeat(58)
        val document =
            MarkdownDocumentFfi(
                listOf(
                    MarkdownBlockFfi.BlockQuote(
                        listOf(
                            MarkdownBlockFfi.Paragraph(
                                listOf(
                                    MarkdownInlineFfi.Emph(
                                        listOf(
                                            MarkdownInlineFfi.NostrMention(
                                                MarkdownNostrEntityFfi(MarkdownNostrHrpFfi.NPUB, npub),
                                            ),
                                        ),
                                    ),
                                    MarkdownInlineFfi.NostrUri(
                                        MarkdownNostrEntityFfi(MarkdownNostrHrpFfi.NOTE, note),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            )

        assertEquals(setOf(npub), markdownDocumentMentionBech32s(document))
    }

    @Test
    fun outputIsCappedAtMaxLength() {
        val annotated = build(listOf(paragraph("x".repeat(500))), maxLength = 200)
        assertEquals(200, annotated.length)
    }

    @Test
    fun capBacksOffInsteadOfSplittingSupplementaryPlaneCharacter() {
        val annotated = build(listOf(paragraph("x".repeat(199) + "😀tail")), maxLength = 200)

        assertEquals(199, annotated.length)
        assertEquals("x".repeat(199), annotated.text)
    }

    @Test
    fun capDoesNotLeaveSeparatorWhenNextBlockStartsWithUnsplittableCharacter() {
        val annotated = build(listOf(paragraph("x".repeat(198)), paragraph("😀tail")), maxLength = 200)

        assertEquals(198, annotated.length)
        assertEquals("x".repeat(198), annotated.text)
    }

    @Test
    fun capAppliesWithinASingleOversizedBlock() {
        val annotated =
            build(
                listOf(
                    MarkdownBlockFfi.Paragraph(
                        listOf(
                            MarkdownInlineFfi.Text("a".repeat(150)),
                            MarkdownInlineFfi.Text("b".repeat(150)),
                        ),
                    ),
                ),
                maxLength = 200,
            )
        assertEquals(200, annotated.length)
        assertEquals("a".repeat(150) + "b".repeat(50), annotated.text)
    }

    @Test
    fun giantCodeBlockCapsAtMaxLength() {
        val annotated =
            build(
                listOf(
                    MarkdownBlockFfi.CodeBlock(
                        kind = MarkdownCodeBlockKindFfi.FENCED,
                        info = "",
                        content = "x".repeat(100_000),
                    ),
                ),
                maxLength = 200,
            )
        assertEquals(200, annotated.length)
        assertEquals(
            FontFamily.Monospace,
            annotated.spanStyles
                .single()
                .item.fontFamily,
        )
    }

    @Test
    fun capStopsWalkingLaterBlocks() {
        val annotated =
            build(
                listOf(paragraph("a".repeat(250)), paragraph("never seen")),
                maxLength = 200,
            )
        assertEquals(200, annotated.length)
        assertTrue(annotated.text.all { it == 'a' })
    }
}
