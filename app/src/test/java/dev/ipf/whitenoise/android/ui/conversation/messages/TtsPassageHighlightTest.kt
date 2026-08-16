package dev.ipf.whitenoise.android.ui.conversation.messages

import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.whitenoise.android.audio.tts.TtsPassage
import dev.ipf.whitenoise.android.audio.tts.TtsVisibleTextSpan
import dev.ipf.whitenoise.android.ui.SpeakableTextProjection
import dev.ipf.whitenoise.android.ui.SpeakableTextProjectionSpan
import dev.ipf.whitenoise.android.ui.legacyTextToSpeakableProjection
import dev.ipf.whitenoise.android.ui.markdownDocumentToSpeakableProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class TtsPassageHighlightTest {
    @Test
    fun sentenceLayoutRequiresAndCombinesEveryRenderedLeafFragment() {
        val projection =
            SpeakableTextProjection(
                text = "Hello world.",
                spans =
                    listOf(
                        SpeakableTextProjectionSpan(0, 6, "b0", 0, 6),
                        SpeakableTextProjectionSpan(6, 12, "b1", 0, 6),
                    ),
            )
        val resolver = TtsHighlightProjectionResolver(projection, Locale.US)

        val first = resolver.sentenceLayoutFor(sentenceIndex = 0, renderedLeafId = "b0", renderedText = "Hello ")
        val second = resolver.sentenceLayoutFor(sentenceIndex = 0, renderedLeafId = "b1", renderedText = "world.")

        assertEquals(listOf(0 until 6), first?.renderedRanges)
        assertEquals(listOf(0 until 6), second?.renderedRanges)
        assertEquals(2, first?.expectedCoverage?.size)
        assertEquals(2, second?.expectedCoverage?.size)
        assertEquals(first?.expectedCoverage, first?.coverage.orEmpty() + second?.coverage.orEmpty())
    }

    @Test
    fun projectionResolverReusesRenderedLeafMappingAcrossWordUpdates() {
        val projection = legacyTextToSpeakableProjection("Hello bright world.")
        val projectionResolver = TtsHighlightProjectionResolver(projection, Locale.US)
        val firstPassage =
            TtsPassage(
                messageIdHex = "m1",
                sentenceIndex = 0,
                projectionId = projection.projectionId,
                visibleWord = listOf(TtsVisibleTextSpan("plain", 0, 5)),
            )
        val secondPassage =
            firstPassage.copy(
                visibleWord = listOf(TtsVisibleTextSpan("plain", 6, 12)),
            )
        val firstResolver = projectionResolver.resolverFor(firstPassage, "m1")
        val secondResolver = projectionResolver.resolverFor(secondPassage, "m1")

        assertEquals(0 until 5, firstResolver("plain", projection.text))
        assertEquals(1, projectionResolver.cachedLeafCount)
        assertEquals(6 until 12, secondResolver("plain", projection.text))
        assertEquals(1, projectionResolver.cachedLeafCount)
    }

    @Test
    fun wordHighlightMapsVisibleLeafCoordinatesIntoRenderedParagraphText() {
        val projection =
            markdownDocumentToSpeakableProjection(
                MarkdownDocumentFfi(
                    truncated = false,
                    blankLinesBefore = byteArrayOf(),
                    blocks =
                        listOf(
                            MarkdownBlockFfi.Paragraph(
                                inlines =
                                    listOf(
                                        MarkdownInlineFfi.Text("Read "),
                                        MarkdownInlineFfi.Strong(listOf(MarkdownInlineFfi.Text("bold"))),
                                        MarkdownInlineFfi.Text(" docs"),
                                    ),
                            ),
                        ),
                ),
            )
        val passage =
            TtsPassage(
                messageIdHex = "m1",
                sentenceIndex = 0,
                projectionId = projection.projectionId,
                visibleWord = listOf(TtsVisibleTextSpan("b0/n1/n0", 0, 4)),
            )

        assertEquals(
            5 until 9,
            resolveTtsRenderedHighlight(
                passage = passage,
                messageIdHex = "m1",
                projection = projection,
                renderedLeafId = "b0",
                renderedText = "Read bold docs",
                locale = Locale.US,
            ),
        )
    }

    @Test
    fun sentenceFallbackHighlightsTheQueuedSentenceWhenVisibleWordIsEmpty() {
        val projection = legacyTextToSpeakableProjection("First sentence. Second sentence.")
        val passage =
            TtsPassage(
                messageIdHex = "m1",
                sentenceIndex = 1,
                projectionId = projection.projectionId,
            )

        assertEquals(
            16 until 32,
            resolveTtsRenderedHighlight(
                passage = passage,
                messageIdHex = "m1",
                projection = projection,
                renderedLeafId = "plain",
                renderedText = "First sentence. Second sentence.",
                locale = Locale.US,
            ),
        )
    }

    @Test
    fun sentenceFallbackCombinesInlineLeavesIntoRenderedParagraphRange() {
        val renderedText = "Read bold docs."
        val projection =
            markdownDocumentToSpeakableProjection(
                MarkdownDocumentFfi(
                    truncated = false,
                    blankLinesBefore = byteArrayOf(),
                    blocks =
                        listOf(
                            MarkdownBlockFfi.Paragraph(
                                inlines =
                                    listOf(
                                        MarkdownInlineFfi.Text("Read "),
                                        MarkdownInlineFfi.Strong(listOf(MarkdownInlineFfi.Text("bold"))),
                                        MarkdownInlineFfi.Text(" docs."),
                                    ),
                            ),
                        ),
                ),
            )
        val passage =
            TtsPassage(
                messageIdHex = "m1",
                sentenceIndex = 0,
                projectionId = projection.projectionId,
            )

        assertEquals(
            0 until renderedText.length,
            resolveTtsRenderedHighlight(
                passage = passage,
                messageIdHex = "m1",
                projection = projection,
                renderedLeafId = "b0",
                renderedText = renderedText,
                locale = Locale.US,
            ),
        )
    }

    @Test
    fun mismatchedProjectionIdReturnsNoHighlight() {
        val projection = legacyTextToSpeakableProjection("Hello world.")
        val passage =
            TtsPassage(
                messageIdHex = "m1",
                sentenceIndex = 0,
                projectionId = "stale-projection",
                visibleWord = listOf(TtsVisibleTextSpan("plain", 0, 5)),
            )

        assertNull(
            resolveTtsRenderedHighlight(
                passage = passage,
                messageIdHex = "m1",
                projection = projection,
                renderedLeafId = "plain",
                renderedText = "Hello world.",
                locale = Locale.US,
            ),
        )
    }

    @Test
    fun emptyPassageProjectionIdDoesNotBypassProjectionScope() {
        val projection = legacyTextToSpeakableProjection("Hello world.")
        val passage =
            TtsPassage(
                messageIdHex = "m1",
                sentenceIndex = 0,
                projectionId = "",
                visibleWord = listOf(TtsVisibleTextSpan("plain", 0, 5)),
            )

        assertNull(
            resolveTtsRenderedHighlight(
                passage = passage,
                messageIdHex = "m1",
                projection = projection,
                renderedLeafId = "plain",
                renderedText = "Hello world.",
                locale = Locale.US,
            ),
        )
    }

    @Test
    fun mismatchedMessageIdReturnsNoHighlight() {
        val projection = legacyTextToSpeakableProjection("Hello world.")
        val passage =
            TtsPassage(
                messageIdHex = "other",
                sentenceIndex = 0,
                projectionId = projection.projectionId,
                visibleWord = listOf(TtsVisibleTextSpan("plain", 0, 5)),
            )

        assertNull(
            resolveTtsRenderedHighlight(
                passage = passage,
                messageIdHex = "m1",
                projection = projection,
                renderedLeafId = "plain",
                renderedText = "Hello world.",
                locale = Locale.US,
            ),
        )
    }

    @Test
    fun childLeafHighlightDoesNotApplyToAnotherRenderedLeaf() {
        val projection =
            markdownDocumentToSpeakableProjection(
                MarkdownDocumentFfi(
                    truncated = false,
                    blankLinesBefore = byteArrayOf(),
                    blocks =
                        listOf(
                            MarkdownBlockFfi.Paragraph(
                                inlines = listOf(MarkdownInlineFfi.Text("Alpha")),
                            ),
                            MarkdownBlockFfi.Paragraph(
                                inlines = listOf(MarkdownInlineFfi.Text("Beta")),
                            ),
                        ),
                ),
            )
        val passage =
            TtsPassage(
                messageIdHex = "m1",
                sentenceIndex = 0,
                projectionId = projection.projectionId,
                visibleWord = listOf(TtsVisibleTextSpan("b1/n0", 0, 4)),
            )

        assertNull(
            resolveTtsRenderedHighlight(
                passage = passage,
                messageIdHex = "m1",
                projection = projection,
                renderedLeafId = "b0",
                renderedText = "Alpha",
                locale = Locale.US,
            ),
        )
    }
}
