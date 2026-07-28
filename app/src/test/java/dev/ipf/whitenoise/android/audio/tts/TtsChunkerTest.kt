package dev.ipf.whitenoise.android.audio.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class TtsChunkerTest {
    @Test
    fun sentenceBoundariesKeepCommonTitleAbbreviationsWithTheirSentence() {
        val chunks =
            TtsChunker.chunk(
                text = "Dr. Smith went to Washington. He arrived.",
                locale = Locale.US,
                maxChunkLength = 4_000,
            )

        assertEquals(
            listOf("Dr. Smith went to Washington.", "He arrived."),
            chunks.map(TtsChunk::text),
        )
        assertEquals(listOf(0, 1), chunks.map(TtsChunk::index))
    }

    @Test
    fun midSentenceTitleAbbreviationDoesNotCreateAnExtraChunk() {
        val chunks =
            TtsChunker.chunk(
                text = "I met Dr. Smith in Washington. He arrived.",
                locale = Locale.US,
                maxChunkLength = 4_000,
            )

        assertEquals(
            listOf("I met Dr. Smith in Washington.", "He arrived."),
            chunks.map(TtsChunk::text),
        )
        assertEquals(listOf(0, 1), chunks.map(TtsChunk::index))
    }

    @Test
    fun whitespaceOnlyInputProducesNoChunks() {
        assertTrue(TtsChunker.chunk("  \n\t", Locale.US, maxChunkLength = 10).isEmpty())
    }

    @Test
    fun overlongSentenceSplitsAtWordBoundariesWithoutDroppingText() {
        val text = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda"

        val chunks = TtsChunker.chunk(text, Locale.US, maxChunkLength = 18)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.text.length <= 18 })
        assertEquals(text, chunks.joinToString(" ", transform = TtsChunk::text))
        assertEquals(chunks.indices.toList(), chunks.map(TtsChunk::index))
    }

    @Test
    fun aWordLongerThanTheEngineLimitIsHardSplitAndSpokenFully() {
        val text = "supercalifragilisticexpialidocious"

        val chunks = TtsChunker.chunk(text, Locale.US, maxChunkLength = 8)

        assertTrue(chunks.all { it.text.length <= 8 })
        assertEquals(text, chunks.joinToString("", transform = TtsChunk::text))
    }

    @Test
    fun leadingChunkReserveShrinksOnlyTheFirstOutputChunk() {
        val text = "alpha beta gamma delta epsilon zeta eta theta"
        val reserve = 10
        val maxChunkLength = 20

        val chunks =
            TtsChunker.chunk(
                text = text,
                locale = Locale.US,
                maxChunkLength = maxChunkLength,
                leadingChunkReserve = reserve,
            )

        val firstChunkLimit = maxChunkLength - reserve
        assertTrue(chunks.first().text.length <= firstChunkLimit)
        assertTrue(chunks.drop(1).all { it.text.length <= maxChunkLength })
        assertEquals(text, chunks.joinToString(" ", transform = TtsChunk::text))
    }

    @Test
    fun hardSplitDoesNotBisectUtf16SurrogatePairs() {
        val text = "ab😀cd"

        val chunks = TtsChunker.chunk(text, Locale.US, maxChunkLength = 3)

        assertEquals(text, chunks.joinToString("", transform = TtsChunk::text))
        assertTrue(chunks.all { chunk -> chunk.text.hasOnlyPairedSurrogates() })
    }

    private fun String.hasOnlyPairedSurrogates(): Boolean {
        var index = 0
        while (index < length) {
            when {
                this[index].isHighSurrogate() -> {
                    if (index + 1 >= length || !this[index + 1].isLowSurrogate()) return false
                    index += 2
                }
                this[index].isLowSurrogate() -> return false
                else -> index += 1
            }
        }
        return true
    }
}
