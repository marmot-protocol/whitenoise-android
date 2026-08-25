package dev.ipf.whitenoise.android.ui.conversation.messages

import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownListItemFfi
import dev.ipf.marmotkit.MarkdownListKindFfi
import dev.ipf.marmotkit.MarkdownTableCellFfi
import dev.ipf.whitenoise.android.audio.tts.TtsPassage
import dev.ipf.whitenoise.android.audio.tts.TtsVisibleTextSpan
import dev.ipf.whitenoise.android.ui.legacyTextToSpeakableProjection
import dev.ipf.whitenoise.android.ui.markdownDocumentToSpeakableProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class TtsPassageHighlightMappingTest {
    @Test
    fun activeMixedMarkdownListRowHighlightsItsMarkerAndWordButNotSiblingMarker() {
        val document =
            MarkdownDocumentFfi(
                truncated = false,
                blankLinesBefore = byteArrayOf(),
                blocks =
                    listOf(
                        MarkdownBlockFfi.ListBlock(
                            kind = MarkdownListKindFfi.Bullet("-"),
                            tight = true,
                            items =
                                listOf(
                                    listItem(MarkdownInlineFfi.Text("First row")),
                                    listItem(
                                        MarkdownInlineFfi.Text("Read "),
                                        MarkdownInlineFfi.Strong(listOf(MarkdownInlineFfi.Text("aloud"))),
                                        MarkdownInlineFfi.Text(" now"),
                                    ),
                                ),
                        ),
                    ),
            )
        val projection = markdownDocumentToSpeakableProjection(document)
        val passage =
            TtsPassage(
                messageIdHex = "m1",
                sentenceIndex = 1,
                projectionId = projection.projectionId,
                visibleWord = listOf(TtsVisibleTextSpan("b0/i1/b0/n1/n0", 0, 5)),
            )
        val resolver = createTtsLeafHighlightResolver(passage, "m1", projection, Locale.US)

        assertNull(resolver("b0/m0", "•"))
        assertEquals(listOf(0 until 1), resolver("b0/m1", "•")?.sentenceRanges)
        assertEquals(5 until 10, resolver("b0/i1/b0", "Read aloud now")?.word)
    }

    @Test
    fun nestedListSentenceHighlightsOnlyItsOwnMarker() {
        val nestedList =
            MarkdownBlockFfi.ListBlock(
                kind = MarkdownListKindFfi.Bullet("-"),
                tight = true,
                items = listOf(listItem(MarkdownInlineFfi.Text("Nested row"))),
            )
        val document =
            MarkdownDocumentFfi(
                truncated = false,
                blankLinesBefore = byteArrayOf(),
                blocks =
                    listOf(
                        MarkdownBlockFfi.ListBlock(
                            kind = MarkdownListKindFfi.Bullet("-"),
                            tight = true,
                            items =
                                listOf(
                                    MarkdownListItemFfi(
                                        blocks =
                                            listOf(
                                                MarkdownBlockFfi.Paragraph(
                                                    listOf(MarkdownInlineFfi.Text("Outer row")),
                                                ),
                                                nestedList,
                                            ),
                                        checked = null,
                                        blankLinesBefore = byteArrayOf(),
                                    ),
                                ),
                        ),
                    ),
            )
        val projection = markdownDocumentToSpeakableProjection(document)
        val passage = TtsPassage("m1", sentenceIndex = 1, projectionId = projection.projectionId)
        val resolver = createTtsLeafHighlightResolver(passage, "m1", projection, Locale.US)

        assertNull(resolver("b0/m0", "•"))
        assertEquals(listOf(0 until 1), resolver("b0/i0/b1/m0", "•")?.sentenceRanges)
        assertEquals(listOf(0 until 10), resolver("b0/i0/b1/i0/b0", "Nested row")?.sentenceRanges)
    }

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
                sentenceIndex = 0,
                projectionId = projection.projectionId,
                visibleWord = listOf(TtsVisibleTextSpan("b1/n0", 0, 6)),
            )

        assertEquals(
            0 until 6,
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
            0 until 5,
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
            0 until 5,
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
                                    MarkdownTableCellFfi(listOf(MarkdownInlineFfi.Text("Two"))),
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
            )

        assertEquals(
            0 until 3,
            resolveTtsRenderedHighlight(
                passage = passage,
                messageIdHex = "m1",
                projection = projection,
                renderedLeafId = "b0/h1",
                renderedText = "Two",
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
    fun omittedUrlKeepsSentenceBandOnBothRenderedSides() {
        val rendered = "Check https://example.com/page now."
        val projection = legacyTextToSpeakableProjection(rendered)
        val passage =
            TtsPassage(
                messageIdHex = "m1",
                sentenceIndex = 0,
                projectionId = projection.projectionId,
            )

        val highlight =
            createTtsLeafHighlightResolver(
                passage = passage,
                messageIdHex = "m1",
                projection = projection,
                locale = Locale.US,
            )("plain", rendered)

        // The URL is rendered but never spoken, so the spoken sentence covers
        // two disjoint rendered pieces. Both keep the band; the URL does not.
        assertEquals(
            listOf(0 until rendered.indexOf("https"), rendered.indexOf("now.") until rendered.length),
            highlight?.sentenceRanges,
        )
        assertEquals(0 until rendered.length, highlight?.sentence)
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
            4 until 7,
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
            6 until 11,
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

    private fun paragraph(vararg lines: String): MarkdownBlockFfi.Paragraph {
        val inlines = mutableListOf<MarkdownInlineFfi>()
        lines.forEachIndexed { index, line ->
            if (index > 0) inlines += MarkdownInlineFfi.SoftBreak
            inlines += MarkdownInlineFfi.Text(line)
        }
        return MarkdownBlockFfi.Paragraph(inlines)
    }

    private fun listItem(vararg inlines: MarkdownInlineFfi) =
        MarkdownListItemFfi(
            blocks = listOf(MarkdownBlockFfi.Paragraph(inlines.toList())),
            checked = null,
            blankLinesBefore = byteArrayOf(),
        )
}
