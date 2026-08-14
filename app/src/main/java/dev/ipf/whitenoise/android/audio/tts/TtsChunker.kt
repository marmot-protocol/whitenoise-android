package dev.ipf.whitenoise.android.audio.tts

import android.speech.tts.TextToSpeech
import java.text.BreakIterator
import java.util.Locale

/**
 * One engine-safe piece of speakable text and its position in the final queue.
 * [sentenceIndex] identifies the logical sentence within its message: a long
 * sentence split into several engine-safe chunks keeps one shared index, so
 * navigation and progress count sentences, never raw chunks.
 *
 * [sourceStart] and [sourceEnd] are half-open UTF-16 offsets into the message's
 * projected speech text. The queue uses them to retain reversible visible-text
 * mappings through sentence and engine-length splitting.
 */
data class TtsChunk(
    val text: String,
    val index: Int,
    val sentenceIndex: Int = 0,
    val sourceStart: Int = 0,
    val sourceEnd: Int = text.length,
    /** Full projected speech text used to validate word and grapheme boundaries across hard splits. */
    val sourceText: String = text,
    val messageIdHex: String = "",
    val projectionId: String = "",
    val timelineAt: ULong = 0uL,
    // Offsets are relative to the exact [text] submitted to the engine.
    val visibleSpans: List<TtsSpokenTextSpan> = emptyList(),
    val senderPrefix: TtsTextRange? = null,
    val locale: Locale = Locale.getDefault(),
)

object TtsChunker {
    /** Logical sentences in [text], using the same boundaries as chunking. */
    internal fun sentences(
        text: String,
        locale: Locale,
    ): List<String> = logicalSentences(text, locale).map(TextSlice::text)

    /** Index of the logical sentence containing [offset] in UTF-16 code units. */
    internal fun sentenceIndexAtOffset(
        text: String,
        offset: Int,
        locale: Locale,
    ): Int? {
        val sentences = logicalSentences(text, locale)
        val clamped = offset.coerceIn(0, text.length)
        return when {
            sentences.isEmpty() -> null
            clamped == text.length -> sentences.lastIndex
            else -> sentences.indexOfFirst { clamped >= it.start && clamped < it.end }.takeIf { it >= 0 }
        }
    }

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
        // An oversized sender label degrades to a tighter first chunk instead
        // of crashing speak() over a long display name.
        val boundedReserve = leadingChunkReserve.coerceAtMost(maxChunkLength - 1)
        if (text.isBlank()) return emptyList()

        val sentences = logicalSentences(text, locale)

        // Every sentence-first chunk keeps the reserve, not just the message's
        // opening chunk: any logical sentence can become a navigation target,
        // and a cross-message target absorbs the sender announcement inline.
        val firstChunkMaxLength = maxChunkLength - boundedReserve
        return sentences
            .flatMapIndexed { sentenceIndex, sentence ->
                splitLongSentence(
                    sentence = sentence,
                    maxChunkLength = maxChunkLength,
                    firstChunkMaxLength = firstChunkMaxLength,
                ).map { piece -> sentenceIndex to piece }
            }.filter { (_, piece) -> piece.text.isNotBlank() }
            .mapIndexed { index, (sentenceIndex, piece) ->
                TtsChunk(
                    text = piece.text,
                    index = index,
                    sentenceIndex = sentenceIndex,
                    sourceStart = piece.start,
                    sourceEnd = piece.end,
                    sourceText = text,
                    locale = locale,
                )
            }
    }

    private fun logicalSentences(
        text: String,
        locale: Locale,
    ): List<TextSlice> {
        if (text.isBlank()) return emptyList()
        val iterator = BreakIterator.getSentenceInstance(locale).apply { setText(text) }
        val sentences = mutableListOf<TextSlice>()
        var pendingStart: Int? = null
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            val candidate = text.trimmedSlice(start, end)
            if (candidate != null) {
                if (candidate.text.endsWithCommonTitleAbbreviation(locale)) {
                    if (pendingStart == null) pendingStart = candidate.start
                } else {
                    val sentenceStart = pendingStart ?: candidate.start
                    sentences += TextSlice(text, sentenceStart, candidate.end)
                    pendingStart = null
                }
            }
            start = end
            end = iterator.next()
        }
        pendingStart?.let { first ->
            text.trimmedSlice(first, text.length)?.let(sentences::add)
        }
        return sentences
    }

    private fun String.endsWithCommonTitleAbbreviation(locale: Locale): Boolean =
        trimEnd()
            .takeLastWhile { !it.isWhitespace() }
            .lowercase(locale) in commonTitleAbbreviations

    private fun splitLongSentence(
        sentence: TextSlice,
        maxChunkLength: Int,
        firstChunkMaxLength: Int = maxChunkLength,
    ): List<TextSlice> {
        var remainingStart = sentence.start
        if (sentence.length <= firstChunkMaxLength) return listOf(sentence)

        val chunks = mutableListOf<TextSlice>()
        var chunkLimit = firstChunkMaxLength
        while (sentence.end - remainingStart > chunkLimit) {
            val remaining = sentence.source.substring(remainingStart, sentence.end)
            val whitespaceBoundary =
                (chunkLimit downTo 1).firstOrNull { index -> remaining[index].isWhitespace() }
            val split = remainingStart + (whitespaceBoundary ?: safeHardSplitIndex(remaining, chunkLimit))
            sentence.source.trimmedSlice(remainingStart, split)?.let(chunks::add)
            remainingStart = split
            while (remainingStart < sentence.end && sentence.source[remainingStart].isWhitespace()) {
                remainingStart++
            }
            chunkLimit = maxChunkLength
        }
        sentence.source.trimmedSlice(remainingStart, sentence.end)?.let(chunks::add)
        return chunks
    }

    private data class TextSlice(
        val source: String,
        val start: Int,
        val end: Int,
    ) {
        val text: String
            get() = source.substring(start, end)

        val length: Int
            get() = end - start
    }

    private fun String.trimmedSlice(
        start: Int,
        end: Int,
    ): TextSlice? {
        var first = start
        var last = end
        while (first < last && this[first].isWhitespace()) first++
        while (last > first && this[last - 1].isWhitespace()) last--
        return if (first < last) TextSlice(this, first, last) else null
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
