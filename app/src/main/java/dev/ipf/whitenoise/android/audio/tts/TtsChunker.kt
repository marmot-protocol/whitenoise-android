package dev.ipf.whitenoise.android.audio.tts

import android.speech.tts.TextToSpeech
import java.text.BreakIterator
import java.util.Locale

/** One engine-safe piece of speakable text and its position in the final queue. */
data class TtsChunk(
    val text: String,
    val index: Int,
)

object TtsChunker {
    private val commonTitleAbbreviations =
        setOf(
            "dr.",
            "mr.",
            "mrs.",
            "ms.",
            "prof.",
            "sr.",
            "jr.",
        )

    fun chunk(
        text: String,
        locale: Locale,
        maxChunkLength: Int = TextToSpeech.getMaxSpeechInputLength(),
        leadingChunkReserve: Int = 0,
    ): List<TtsChunk> {
        require(maxChunkLength > 0) { "maxChunkLength must be positive" }
        require(leadingChunkReserve >= 0) { "leadingChunkReserve must be non-negative" }
        require(leadingChunkReserve < maxChunkLength) {
            "leadingChunkReserve must be less than maxChunkLength"
        }
        if (text.isBlank()) return emptyList()

        val iterator = BreakIterator.getSentenceInstance(locale).apply { setText(text) }
        val sentences = mutableListOf<String>()
        var pendingPrefix = ""
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            val candidate = text.substring(start, end)
            if (candidate.isNotBlank()) {
                if (candidate.endsWithCommonTitleAbbreviation(locale)) {
                    pendingPrefix += candidate
                } else {
                    sentences += (pendingPrefix + candidate).trim()
                    pendingPrefix = ""
                }
            }
            start = end
            end = iterator.next()
        }
        pendingPrefix.trim().takeIf(String::isNotEmpty)?.let(sentences::add)

        val firstChunkMaxLength = maxChunkLength - leadingChunkReserve
        return sentences
            .flatMapIndexed { sentenceIndex, sentence ->
                splitLongSentence(
                    sentence = sentence,
                    maxChunkLength = maxChunkLength,
                    firstChunkMaxLength = if (sentenceIndex == 0) firstChunkMaxLength else maxChunkLength,
                )
            }.filter(String::isNotBlank)
            .mapIndexed { index, chunk -> TtsChunk(text = chunk, index = index) }
    }

    private fun String.endsWithCommonTitleAbbreviation(locale: Locale): Boolean =
        trimEnd()
            .takeLastWhile { !it.isWhitespace() }
            .lowercase(locale) in commonTitleAbbreviations

    private fun splitLongSentence(
        sentence: String,
        maxChunkLength: Int,
        firstChunkMaxLength: Int = maxChunkLength,
    ): List<String> {
        var remaining = sentence.trim()
        if (remaining.length <= firstChunkMaxLength) return listOf(remaining)

        val chunks = mutableListOf<String>()
        var chunkLimit = firstChunkMaxLength
        while (remaining.length > chunkLimit) {
            val whitespaceBoundary =
                (chunkLimit downTo 1).firstOrNull { index -> remaining[index].isWhitespace() }
            val end = whitespaceBoundary ?: safeHardSplitIndex(remaining, chunkLimit)
            remaining
                .substring(0, end)
                .trimEnd()
                .takeIf(String::isNotEmpty)
                ?.let(chunks::add)
            remaining = remaining.substring(end).trimStart()
            chunkLimit = maxChunkLength
        }
        remaining.takeIf(String::isNotBlank)?.let(chunks::add)
        return chunks
    }

    private fun safeHardSplitIndex(
        text: String,
        maxChunkLength: Int,
    ): Int =
        if (
            text[maxChunkLength - 1].isHighSurrogate() &&
            text[maxChunkLength].isLowSurrogate()
        ) {
            // A UTF-16 limit of one cannot contain a supplementary code point;
            // Android's real limit is much larger, but preserve valid text even
            // for that artificial test/injected value.
            if (maxChunkLength == 1) 2 else maxChunkLength - 1
        } else {
            maxChunkLength
        }
}
