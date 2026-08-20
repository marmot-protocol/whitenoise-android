package dev.ipf.whitenoise.android.ui.conversation.messages

import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownListItemFfi
import dev.ipf.marmotkit.MarkdownListKindFfi
import dev.ipf.marmotkit.MarkdownTableCellFfi
import dev.ipf.whitenoise.android.audio.tts.TtsPassage
import dev.ipf.whitenoise.android.audio.tts.TtsVisibleTextSpan
import dev.ipf.whitenoise.android.ui.TtsLeafHighlight
import dev.ipf.whitenoise.android.ui.legacyTextToSpeakableProjection
import dev.ipf.whitenoise.android.ui.markdownDocumentToSpeakableProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class TtsPassageHighlightMappingTest {
    @Test
    fun multiBlockDetailsContentResolvesAgainstSiblingBlockLeafId() {
        val document =
            MarkdownDocumentFfi(
                truncated = false,
                blankLinesBefore = byteArrayOf(),
                blocks =
                    listOf(
                        paragraph("<details>", "<summary>Logs</summary>"),
                        paragraph("hidden line"),
                        paragraph("</details>"),
                    ),
            )
        val projection = markdownDocumentToSpeakableProjection(document)
        val passage =
            TtsPassage(
                messageIdHex = "m1",
                sentenceIndex = 1,
                projectionId = projection.projectionId,
                visibleWord = listOf(TtsVisibleTextSpan("b1/n0", 0, 6)),
            )

        assertEquals(
            TtsLeafHighlight(
                sentenceRanges = listOf(0 until "hidden line".length),
                wordRange = 0 until 6,
            ),
            resolveTtsRenderedHighlight(
                passage = passage,
                messageIdHex = "m1",
                projection = projection,
                renderedLeafId = "b1",
                renderedText = "hidden line",
                locale = Locale.US,
            ),
        )
        assertNull(
            resolveTtsRenderedHighlight(
                passage = passage,
                messageIdHex = "m1",
                projection = projection,
                renderedLeafId = "b0/b0",
                renderedText = "hidden line",
                locale = Locale.US,
            ),
        )
    }

    @Test
    fun firstQueuedSentenceHighlightsOnlyItsRenderedLeaf() {
        val projection = alphaBetaProjection()
        val firstSentence =
            TtsPassage(
                messageIdHex = "m1",
                sentenceIndex = 0,
                projectionId = projection.projectionId,
            )

        assertEquals(
            TtsLeafHighlight(sentenceRanges = listOf(0 until 5)),
            resolveTtsRenderedHighlight(
                passage = firstSentence,
                messageIdHex = "m1",
                projection = projection,
                renderedLeafId = "b0",
                renderedText = "Alpha ",
                locale = Locale.US,
            ),
        )
        assertNull(
            resolveTtsRenderedHighlight(
                passage = firstSentence,
                messageIdHex = "m1",
                projection = projection,
                renderedLeafId = "b1",
                renderedText = "Beta.",
                locale = Locale.US,
            ),
        )
    }

    @Test
    fun secondQueuedSentenceHighlightsOnlyItsRenderedLeaf() {
        val projection = alphaBetaProjection()
        val secondSentence =
            TtsPassage(
                messageIdHex = "m1",
                sentenceIndex = 1,
                projectionId = projection.projectionId,
            )

        assertEquals(
            TtsLeafHighlight(sentenceRanges = listOf(0 until 5)),
            resolveTtsRenderedHighlight(
                passage = secondSentence,
                messageIdHex = "m1",
                projection = projection,
                renderedLeafId = "b1",
                renderedText = "Beta.",
                locale = Locale.US,
            ),
        )
        assertNull(
            resolveTtsRenderedHighlight(
                passage = secondSentence,
                messageIdHex = "m1",
                projection = projection,
                renderedLeafId = "b0",
                renderedText = "Alpha ",
                locale = Locale.US,
            ),
        )
    }

    private fun alphaBetaProjection() =
        markdownDocumentToSpeakableProjection(
            MarkdownDocumentFfi(
                truncated = false,
                blankLinesBefore = byteArrayOf(),
                blocks =
                    listOf(
                        MarkdownBlockFfi.Paragraph(
                            inlines = listOf(MarkdownInlineFfi.Text("Alpha ")),
                        ),
                        MarkdownBlockFfi.Paragraph(
                            inlines = listOf(MarkdownInlineFfi.Text("Beta.")),
                        ),
                    ),
            ),
        )

    @Test
    fun tableSentenceHighlightsOnlyTheActiveCellLeaf() {
        val document =
            MarkdownDocumentFfi(
                truncated = false,
                blankLinesBefore = byteArrayOf(),
                blocks =
                    listOf(
                        MarkdownBlockFfi.Table(
                            alignments = emptyList(),
                            header =
                                listOf(
                                    MarkdownTableCellFfi(listOf(MarkdownInlineFfi.Text("One"))),
                                    MarkdownTableCellFfi(listOf(MarkdownInlineFfi.Text("Second cell"))),
                                ),
                            rows = emptyList(),
                        ),
                    ),
            )
        val projection = markdownDocumentToSpeakableProjection(document)
        val passage =
            TtsPassage(
                messageIdHex = "m1",
                sentenceIndex = 1,
                projectionId = projection.projectionId,
                visibleWord = listOf(TtsVisibleTextSpan("b0/h1/n0", 7, 11)),
            )

        assertEquals(
            TtsLeafHighlight(
                sentenceRanges = listOf(0 until "Second cell".length),
                wordRange = 7 until 11,
            ),
            resolveTtsRenderedHighlight(
                passage = passage,
                messageIdHex = "m1",
                projection = projection,
                renderedLeafId = "b0/h1",
                renderedText = "Second cell",
                locale = Locale.US,
            ),
        )
        assertNull(
            resolveTtsRenderedHighlight(
                passage = passage,
                messageIdHex = "m1",
                projection = projection,
                renderedLeafId = "b0/h0",
                renderedText = "One",
                locale = Locale.US,
            ),
        )
    }

    @Test
    fun nestedListHighlightsOnlyTheActiveSpokenRow() {
        val document = nestedListDocument()
        val projection = markdownDocumentToSpeakableProjection(document)
        val passage =
            TtsPassage(
                messageIdHex = "m1",
                sentenceIndex = 1,
                projectionId = projection.projectionId,
                visibleWord = listOf(TtsVisibleTextSpan("b0/i0/b1/i0/b0/n0", 0, 6)),
            )

        assertEquals(
            TtsLeafHighlight(
                sentenceRanges = listOf(0 until "Nested row".length),
                wordRange = 0 until 6,
            ),
            resolveTtsRenderedHighlight(
                passage = passage,
                messageIdHex = "m1",
                projection = projection,
                renderedLeafId = "b0/i0/b1/i0/b0",
                renderedText = "Nested row",
                locale = Locale.US,
            ),
        )
        assertNull(
            resolveTtsRenderedHighlight(
                passage = passage,
                messageIdHex = "m1",
                projection = projection,
                renderedLeafId = "b0/i0/b0",
                renderedText = "Parent row",
                locale = Locale.US,
            ),
        )
        assertNull(
            resolveTtsRenderedHighlight(
                passage = passage,
                messageIdHex = "m1",
                projection = projection,
                renderedLeafId = "b0/i1/b0",
                renderedText = "Sibling row",
                locale = Locale.US,
            ),
        )
    }

    @Test
    fun blockQuoteHighlightsOnlyTheActiveLeaf() {
        val document =
            MarkdownDocumentFfi(
                truncated = false,
                blankLinesBefore = byteArrayOf(),
                blocks =
                    listOf(
                        MarkdownBlockFfi.BlockQuote(
                            blocks = listOf(paragraph("Quoted first"), paragraph("Quoted second")),
                            blankLinesBefore = byteArrayOf(),
                        ),
                    ),
            )
        val projection = markdownDocumentToSpeakableProjection(document)
        val passage =
            TtsPassage(
                messageIdHex = "m1",
                sentenceIndex = 1,
                projectionId = projection.projectionId,
                visibleWord = listOf(TtsVisibleTextSpan("b0/q/b1/n0", 7, 13)),
            )

        assertEquals(
            TtsLeafHighlight(
                sentenceRanges = listOf(0 until "Quoted second".length),
                wordRange = 7 until 13,
            ),
            resolveTtsRenderedHighlight(
                passage = passage,
                messageIdHex = "m1",
                projection = projection,
                renderedLeafId = "b0/q/b1",
                renderedText = "Quoted second",
                locale = Locale.US,
            ),
        )
        assertNull(
            resolveTtsRenderedHighlight(
                passage = passage,
                messageIdHex = "m1",
                projection = projection,
                renderedLeafId = "b0/q/b0",
                renderedText = "Quoted first",
                locale = Locale.US,
            ),
        )
    }

    @Test
    fun repeatedWordUsesVisibleCoordinates() {
        val projection = legacyTextToSpeakableProjection("cat cat cat")
        val passage =
            TtsPassage(
                messageIdHex = "m1",
                sentenceIndex = 0,
                projectionId = projection.projectionId,
                visibleWord = listOf(TtsVisibleTextSpan("plain", 4, 7)),
            )

        assertEquals(
            TtsLeafHighlight(
                sentenceRanges = listOf(0 until "cat cat cat".length),
                wordRange = 4 until 7,
            ),
            resolveTtsRenderedHighlight(
                passage = passage,
                messageIdHex = "m1",
                projection = projection,
                renderedLeafId = "plain",
                renderedText = "cat cat cat",
                locale = Locale.US,
            ),
        )
    }

    @Test
    fun repeatedSentenceUsesSentenceProjectionCoordinates() {
        val projection = legacyTextToSpeakableProjection("Done. Done.")
        val passage =
            TtsPassage(
                messageIdHex = "m1",
                sentenceIndex = 1,
                projectionId = projection.projectionId,
            )

        assertEquals(
            TtsLeafHighlight(sentenceRanges = listOf(6 until 11)),
            resolveTtsRenderedHighlight(
                passage = passage,
                messageIdHex = "m1",
                projection = projection,
                renderedLeafId = "plain",
                renderedText = "Done. Done.",
                locale = Locale.US,
            ),
        )
    }

    private fun nestedListDocument(): MarkdownDocumentFfi =
        MarkdownDocumentFfi(
            truncated = false,
            blankLinesBefore = byteArrayOf(),
            blocks =
                listOf(
                    MarkdownBlockFfi.ListBlock(
                        kind = MarkdownListKindFfi.Ordered(start = 3u, delimiter = "."),
                        tight = true,
                        items =
                            listOf(
                                MarkdownListItemFfi(
                                    blocks =
                                        listOf(
                                            paragraph("Parent row"),
                                            MarkdownBlockFfi.ListBlock(
                                                kind = MarkdownListKindFfi.Bullet(marker = "-"),
                                                tight = true,
                                                items =
                                                    listOf(
                                                        MarkdownListItemFfi(
                                                            blocks = listOf(paragraph("Nested row")),
                                                            checked = null,
                                                            blankLinesBefore = byteArrayOf(),
                                                        ),
                                                    ),
                                            ),
                                        ),
                                    checked = null,
                                    blankLinesBefore = byteArrayOf(),
                                ),
                                MarkdownListItemFfi(
                                    blocks = listOf(paragraph("Sibling row")),
                                    checked = null,
                                    blankLinesBefore = byteArrayOf(),
                                ),
                            ),
                    ),
                ),
        )

    private fun paragraph(vararg lines: String): MarkdownBlockFfi.Paragraph {
        val inlines = mutableListOf<MarkdownInlineFfi>()
        lines.forEachIndexed { index, line ->
            if (index > 0) inlines += MarkdownInlineFfi.SoftBreak
            inlines += MarkdownInlineFfi.Text(line)
        }
        return MarkdownBlockFfi.Paragraph(inlines)
    }
}
