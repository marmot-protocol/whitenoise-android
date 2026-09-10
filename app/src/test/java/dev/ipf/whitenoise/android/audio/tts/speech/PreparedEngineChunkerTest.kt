package dev.ipf.whitenoise.android.audio.tts.speech

import dev.ipf.whitenoise.android.audio.tts.TtsTextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Engine-size splitting happens after expansion and sender-prefix insertion and
 * must not change logical navigation units or lose provenance.
 */
class PreparedEngineChunkerTest {
    @Test
    fun oneLongSentenceStaysOneNavigableUnitAcrossManyChunks() {
        val source = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda"
        val message = prepareMessage(proseRun(source))

        val chunks = chunks(message, maxChunkLength = 18)

        assertTrue(chunks.size > 1)
        assertEquals(1, chunks.map(PreparedEngineChunk::sentenceId).distinct().size)
        assertEquals(listOf(0), chunks.map(PreparedEngineChunk::sentenceOrdinal).distinct())
    }

    @Test
    fun chunkTextConcatenatesBackToTheCompleteUtterance() {
        val source = "alpha beta gamma delta epsilon zeta eta theta"
        val message = prepareMessage(proseRun(source))
        val utterance = message.sentences.single().utterance

        val chunks = chunks(message, maxChunkLength = 16)

        assertEquals(
            utterance.engineText.filterNot(Char::isWhitespace),
            chunks.joinToString("") { it.text }.filterNot(Char::isWhitespace),
        )
    }

    @Test
    fun theLastWordIsNeverDropped() {
        val source = "alpha beta gamma delta final"
        val message = prepareMessage(proseRun(source))

        val chunks = chunks(message, maxChunkLength = 12)

        assertTrue(chunks.last().text.contains("final"))
    }

    @Test
    fun aHardSplitNeverBisectsASurrogatePair() {
        val message = prepareMessage(proseRun("ab😀cd"))

        val chunks = chunks(message, maxChunkLength = 3)

        chunks.forEach { chunk ->
            assertTrue("chunk '${chunk.text}' has an unpaired surrogate", chunk.text.hasOnlyPairedSurrogates())
        }
    }

    @Test
    fun engineLimitsApplyToTheExpandedTextNotTheSource() {
        val message = prepareMessage(proseRun("Pay \$12.50."))

        val chunks = chunks(message, maxChunkLength = 12)

        assertTrue("the expansion is longer than the source and must be split", chunks.size > 1)
        assertTrue(chunks.all { it.text.length <= 12 })
    }

    @Test
    fun aReplacementCrossingChunksKeepsTheCompleteSourceAtomInEachChunk() {
        val source = "Pay \$12.50."
        val amount = sourceSpan(source, "\$12.50")
        val message = prepareMessage(proseRun(source))

        val chunks = chunks(message, maxChunkLength = 12)
        val amountChunks =
            chunks.filter { chunk ->
                chunk.originRuns.any { run -> amount in run.sources }
            }

        assertTrue("the amount expansion must cross a chunk boundary", amountChunks.size > 1)
        assertEquals(1, amountChunks.map(PreparedEngineChunk::sentenceId).distinct().size)
    }

    @Test
    fun identityMappingsAreSlicedWhileReplacementsAreRetainedWhole() {
        val source = "alpha beta gamma 2.5 kg"
        val message = prepareMessage(proseRun(source))

        val chunks = chunks(message, maxChunkLength = 14)

        chunks.flatMap(PreparedEngineChunk::originRuns).forEach { run ->
            when (run.kind) {
                SpeechMappingKind.Identity ->
                    assertEquals(
                        "identity runs must stay equal length after slicing",
                        run.spoken.end - run.spoken.start,
                        run.sources.sumOf { it.end - it.start },
                    )
                SpeechMappingKind.Replacement ->
                    assertEquals(
                        listOf(sourceSpan(source, "2.5 kg")),
                        run.sources,
                    )
                SpeechMappingKind.Synthetic -> assertEquals(emptyList<SpeechSourceSpan>(), run.sources)
            }
        }
    }

    @Test
    fun theSenderPrefixIsSyntheticAndPartOfTheFirstChunkTransform() {
        val message = prepareMessage(proseRun("Yes."), senderAnnouncement = "Alice")

        val first = chunks(message, maxChunkLength = 200).first()

        val prefix = requireNotNull(first.senderPrefix)
        assertEquals(0, prefix.start)
        assertTrue(prefix.end > 0)
        assertEquals(0, first.utteranceOffset)
        assertTrue(
            "no source word may live inside the synthetic prefix",
            first.originRuns.none { it.kind != SpeechMappingKind.Synthetic && it.spoken.start < prefix.end },
        )
    }

    @Test
    fun eachChunkRecordsItsUtteranceLocalToPreparedSpokenTransform() {
        val message = prepareMessage(proseRun("alpha beta gamma delta epsilon"))
        val utterance = message.sentences.single().utterance

        val chunks = chunks(message, maxChunkLength = 12)

        chunks.forEach { chunk ->
            val projected = TtsTextRange(chunk.utteranceOffset, chunk.utteranceOffset + chunk.text.length)
            assertTrue(
                "chunk transform must stay inside the prepared utterance",
                projected.end <= utterance.engineText.length,
            )
        }
        assertEquals(chunks.map { it.utteranceOffset }.sorted(), chunks.map { it.utteranceOffset })
    }

    @Test
    fun aSingleGraphemeLongerThanTheEngineLimitIsRecoverablyUnsupported() {
        val message = prepareMessage(proseRun("👩‍💻"))

        val result = PreparedEngineChunker.chunk(message, maxChunkLength = 2)

        assertTrue(
            "an oversized grapheme must not be split or retried, got $result",
            result is PreparedChunkingResult.UnsupportedInput,
        )
        assertEquals(
            message.sentences.single().sentenceId,
            (result as PreparedChunkingResult.UnsupportedInput).sentenceId,
        )
    }

    @Test
    fun separateLogicalSentencesKeepSeparateIdsAcrossChunking() {
        val message = prepareMessage(proseRun("alpha beta gamma delta. Yes. OK"))

        val chunks = chunks(message, maxChunkLength = 12)

        assertEquals(3, chunks.map(PreparedEngineChunk::sentenceId).distinct().size)
        assertEquals(listOf(0, 1, 2), chunks.map(PreparedEngineChunk::sentenceOrdinal).distinct())
    }

    private fun chunks(
        message: PreparedSpeechMessage,
        maxChunkLength: Int,
    ): List<PreparedEngineChunk> {
        val result = PreparedEngineChunker.chunk(message, maxChunkLength = maxChunkLength)
        assertTrue("chunking must succeed, got $result", result is PreparedChunkingResult.Chunks)
        return (result as PreparedChunkingResult.Chunks).chunks
    }

    private fun String.hasOnlyPairedSurrogates(): Boolean {
        var index = 0
        var valid = true
        while (index < length && valid) {
            when {
                this[index].isHighSurrogate() -> {
                    valid = index + 1 < length && this[index + 1].isLowSurrogate()
                    index += 2
                }
                this[index].isLowSurrogate() -> valid = false
                else -> index += 1
            }
        }
        return valid
    }
}
