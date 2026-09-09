package dev.ipf.whitenoise.android.audio.tts

import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownCodeBlockKindFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.audio.tts.speech.SpeechContext
import dev.ipf.whitenoise.android.ui.SpeakableTextProjection
import dev.ipf.whitenoise.android.ui.conversation.messages.preparedHighlightSpeech
import dev.ipf.whitenoise.android.ui.legacyTextToSpeakableProjection
import dev.ipf.whitenoise.android.ui.markdownDocumentToSpeakableProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class TtsNaturalSpeechIntegrationTest {
    @Test
    fun runtimeOmittedUrlDoesNotBorrowTheNeighborSentence() {
        val source = "Read https://example.com now. Next."
        val projection = legacyTextToSpeakableProjection(source)
        val resolver =
            dev.ipf.whitenoise.android.ui.conversation.messages
                .TtsHighlightProjectionResolver(projection, preparedHighlightSpeech(projection))
        assertEquals(
            null,
            resolver.sentenceIndexAtRenderedOffset(
                dev.ipf.whitenoise.android.ui.conversation.messages
                    .RenderedTextHit("plain", source, 12),
            ),
        )
        assertEquals(
            1,
            resolver.sentenceIndexAtRenderedOffset(
                dev.ipf.whitenoise.android.ui.conversation.messages
                    .RenderedTextHit("plain", source, source.indexOf("Next")),
            ),
        )
    }

    @Test
    fun runtimeLiteralCodeRetainsLeadingTrailingAndBlankLineStructure() {
        val source = "  x \n\n\ty"
        val projection =
            markdownDocumentToSpeakableProjection(
                MarkdownDocumentFfi(
                    truncated = false,
                    blankLinesBefore = byteArrayOf(),
                    blocks = listOf(MarkdownBlockFfi.CodeBlock(MarkdownCodeBlockKindFfi.FENCED, "kotlin", source)),
                ),
            )
        val prepared =
            requireNotNull(
                projection.entry().prepareSpeech(
                    SpeechContext(
                        Locale.US,
                        mode = dev.ipf.whitenoise.android.audio.tts.speech.SpeechMode.LiteralCode,
                    ),
                ),
            )
        val spoken = prepared.sentences.joinToString(" ") { it.utterance.engineText }
        assertTrue(spoken.contains("space space"))
        assertTrue(spoken.contains("x space"))
        assertTrue(spoken.contains("tab"))
        assertEquals(2, Regex("New line").findAll(spoken).count())
    }

    @Test
    fun oneLargeExpandedSentenceKeepsOneOrdinalAcrossPreparationBatches() {
        val source = "$12.50 ".repeat(1500).trim()
        val prepared =
            requireNotNull(legacyTextToSpeakableProjection(source).entry().prepareSpeech(SpeechContext(Locale.US)))
        assertEquals(1, prepared.sentences.size)
        val message = requireNotNull(preparedQueuedMessage(prepared, "", "", 4000))
        assertTrue(message.chunks.size > 8)
        assertTrue(message.chunks.all { it.sentenceIndex == 0 })
        assertEquals(source, message.preview)
    }

    @Test
    fun runtimeConsumesAllOutputBatchesWithoutLosingLaterSentences() {
        val source = "Pay $12.50. ".repeat(1000).trim()
        val prepared =
            requireNotNull(legacyTextToSpeakableProjection(source).entry().prepareSpeech(SpeechContext(Locale.US)))
        assertEquals(1000, prepared.sentences.size)
        assertEquals((0 until 1000).toList(), prepared.sentences.map { it.ordinal })
        assertTrue(prepared.sentences.sumOf { it.utterance.engineText.length } > 32_000)
        assertEquals(source, prepared.displayPreview)
    }

    @Test
    fun runtimeProjectionExpandsMoneyAndKeepsOriginalCoordinates() {
        val source = "Pay $12.50. Then pay $12.50."
        val projection = legacyTextToSpeakableProjection(source)
        val prepared = requireNotNull(projection.entry().prepareSpeech(SpeechContext(Locale.US)))
        val message = requireNotNull(preparedQueuedMessage(prepared, "", "", 4000))
        val chunk = message.chunks.last()
        val tracker = TtsRangeTracker().apply { record(chunk) }
        val start = chunk.text.indexOf("fifty")
        val passage = requireNotNull(tracker.passageForRange(chunk, start, start + 5))

        assertEquals(source, message.preview)
        assertEquals(1, passage.sentenceIndex)
        assertEquals(
            listOf(TtsVisibleTextSpan("plain", source.lastIndexOf('$'), source.length - 1)),
            passage.visibleWord,
        )
    }

    @Test
    fun runtimeCodeRoleProtectsDateAndCurrencySyntax() {
        val source = "date = \"2026-09-08\"\n    echo \"$5\""
        val projection =
            markdownDocumentToSpeakableProjection(
                MarkdownDocumentFfi(
                    truncated = false,
                    blankLinesBefore = byteArrayOf(),
                    blocks = listOf(MarkdownBlockFfi.CodeBlock(MarkdownCodeBlockKindFfi.FENCED, "kotlin", source)),
                ),
            )
        val prepared = requireNotNull(projection.entry().prepareSpeech(SpeechContext(Locale.US)))
        val spoken = prepared.sentences.joinToString(" ") { it.utterance.engineText }

        assertTrue(spoken.contains("minus"))
        assertTrue(spoken.contains("Indent"))
        assertTrue(spoken.contains("dollar"))
        assertFalse(spoken.contains("September"))
        assertFalse(spoken.contains("five dollars"))
        assertTrue(
            prepared.sentences
                .flatMap { it.utterance.originRuns }
                .flatMap { it.sources }
                .any { it.leafId == "b0/code" && it.end == source.length },
        )
    }

    @Test
    fun runtimeNeverInfersFixtureOnlyHints() {
        val source = "3/4 2026-09 2026-251 5 m"
        val prepared =
            requireNotNull(legacyTextToSpeakableProjection(source).entry().prepareSpeech(SpeechContext(Locale.US)))
        val spoken = prepared.sentences.joinToString(" ") { it.utterance.engineText }

        assertFalse(spoken.contains("quarters"))
        assertFalse(spoken.contains("September"))
        assertFalse(spoken.contains("meters"))
    }

    @Test
    fun runtimePreparedTableResolvesAnExactRenderedHit() {
        val text = "Pay $12.50. Then pay $12.50."
        val prepared =
            requireNotNull(legacyTextToSpeakableProjection(text).entry().prepareSpeech(SpeechContext(Locale.US)))
        val target =
            dev.ipf.whitenoise.android.audio.tts.speech.PreparedSeekResolver.resolve(
                prepared,
                dev.ipf.whitenoise.android.audio.tts.speech
                    .PreparedRenderedHit("plain", text, text.lastIndexOf('$')),
            )
        assertEquals(1, (target as dev.ipf.whitenoise.android.audio.tts.speech.PreparedSeekTarget.Sentence).ordinal)
    }

    @Test
    fun originalLeafBytesInvalidateEvenWhenOmittedSpeechIsIdentical() {
        val first = legacyTextToSpeakableProjection("Read https://one.example now.")
        val second = legacyTextToSpeakableProjection("Read https://two.example now.")
        assertEquals(first.text, second.text)
        assertFalse(first.projectionId == second.projectionId)
    }

    @Test
    fun runtimeUnsupportedLanguageRetainsLiteralContent() {
        val prepared =
            requireNotNull(
                legacyTextToSpeakableProjection("Pay $12.50.").entry().prepareSpeech(SpeechContext(Locale.FRANCE)),
            )
        assertEquals(
            "Pay $12.50.",
            prepared.sentences
                .single()
                .utterance.engineText,
        )
    }

    private fun SpeakableTextProjection.entry() =
        TtsSpeakableEntry(
            senderKey = "",
            senderDisplayName = "",
            text = text,
            messageIdHex = "m1",
            projectionId = projectionId,
            spokenTextSpans =
                spans.map {
                    TtsSpokenTextSpan(
                        TtsTextRange(it.spokenStart, it.spokenEnd),
                        TtsVisibleTextSpan(it.leafId, it.visibleStart, it.visibleEnd),
                    )
                },
            speechRoles = speechRoles,
            visibleLeaves = visibleLeaves,
        )
}
