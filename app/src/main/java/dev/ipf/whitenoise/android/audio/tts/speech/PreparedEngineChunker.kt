package dev.ipf.whitenoise.android.audio.tts.speech

import dev.ipf.whitenoise.android.audio.tts.TtsTextRange

data class PreparedEngineChunk(
    val text: String,
    val sentenceId: String,
    val sentenceOrdinal: Int,
    val utteranceOffset: Int,
    val originRuns: List<SpokenOriginRun>,
    val senderPrefix: TtsTextRange?,
    val utterance: PreparedUtterance,
)

sealed interface PreparedChunkingResult {
    data class Chunks(
        val chunks: List<PreparedEngineChunk>,
    ) : PreparedChunkingResult

    data class UnsupportedInput(
        val sentenceId: String,
    ) : PreparedChunkingResult
}

object PreparedEngineChunker {
    fun chunk(
        message: PreparedSpeechMessage,
        maxChunkLength: Int,
    ): PreparedChunkingResult {
        require(maxChunkLength > 0)
        val chunks = mutableListOf<PreparedEngineChunk>()
        for (sentence in message.sentences) {
            val sentenceChunks =
                chunkSentence(sentence, maxChunkLength)
                    ?: return PreparedChunkingResult.UnsupportedInput(sentence.sentenceId)
            chunks += sentenceChunks
        }
        return PreparedChunkingResult.Chunks(chunks)
    }

    private fun chunkSentence(
        sentence: PreparedSentence,
        maxChunkLength: Int,
    ): List<PreparedEngineChunk>? {
        val chunks = mutableListOf<PreparedEngineChunk>()
        val utterance = sentence.utterance
        val text = utterance.engineText
        val boundaries = graphemeBoundaries(text)
        if (boundaries.zipWithNext().any { (a, b) -> b - a > maxChunkLength }) {
            return null
        }
        var start = 0
        while (start < text.length) {
            while (start < text.length && text[start].isWhitespace()) start++
            if (start == text.length) break
            val end = chunkEnd(text, boundaries, start, maxChunkLength)
            check(end > start) { "Validated grapheme boundary must advance" }
            val range = TtsTextRange(start, end)
            val prefix =
                utterance.senderPrefix?.let {
                    if (it.end >
                        start
                    ) {
                        TtsTextRange(0, minOf(it.end, end) - start)
                    } else {
                        null
                    }
                }
            chunks +=
                PreparedEngineChunk(
                    text.substring(start, end),
                    sentence.sentenceId,
                    sentence.ordinal,
                    start,
                    utterance.originRuns.mapNotNull {
                        it.slice(range, start)
                    },
                    prefix,
                    utterance,
                )
            start = end
        }
        return chunks
    }

    private fun chunkEnd(
        text: String,
        boundaries: List<Int>,
        start: Int,
        maxChunkLength: Int,
    ): Int {
        val limit = minOf(text.length, start + maxChunkLength)
        var end = boundaries.last { it <= limit }
        if (end < text.length) {
            val whitespace =
                (end downTo start + 1).firstOrNull {
                    it < text.length &&
                        text[it].isWhitespace() &&
                        it in boundaries
                }
            if (whitespace != null) end = whitespace
        }
        return end
    }
}

/** UTF-16 boundaries excluding combining marks, emoji modifiers and ZWJ joins. */
internal fun graphemeBoundaries(text: String): List<Int> {
    val result = mutableListOf(0)
    var offset = 0
    var previous = -1
    var regional = 0
    while (offset < text.length) {
        val code = text.codePointAt(offset)
        val isRegional = code in REGIONAL_INDICATORS
        val joined = code == ZERO_WIDTH_JOINER || previous == ZERO_WIDTH_JOINER
        val pairedRegional = isRegional && regional % 2 == 1
        val continuation = extendsGrapheme(code) || joined || pairedRegional
        if (offset > 0 && !continuation) {
            result += offset
        }
        regional = if (isRegional) regional + 1 else 0
        previous = code
        offset += Character.charCount(code)
    }
    if (result.last() != text.length) result += text.length
    return result
}

private const val ZERO_WIDTH_JOINER = 0x200D
private val REGIONAL_INDICATORS = 0x1F1E6..0x1F1FF

// These are Unicode's fixed variation-selector and emoji-modifier ranges.
@Suppress("MagicNumber")
private fun extendsGrapheme(code: Int): Boolean {
    val combining =
        Character.getType(code) in
            listOf(
                Character.NON_SPACING_MARK.toInt(),
                Character.COMBINING_SPACING_MARK.toInt(),
                Character.ENCLOSING_MARK.toInt(),
            )
    val variation = code in 0xFE00..0xFE0F || code in 0xE0100..0xE01EF
    return combining || variation || code in 0x1F3FB..0x1F3FF
}
