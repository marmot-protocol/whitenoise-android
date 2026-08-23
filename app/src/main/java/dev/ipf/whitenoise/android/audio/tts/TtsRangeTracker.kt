package dev.ipf.whitenoise.android.audio.tts

import java.text.BreakIterator
import java.util.Locale

/** Generation-scoped engine payloads and their reversible visible-word mappings. */
internal class TtsRangeTracker {
    private val submittedChunks = mutableMapOf<Int, TtsChunk>()

    fun clear() {
        submittedChunks.clear()
    }

    fun record(chunk: TtsChunk) {
        submittedChunks[chunk.index] = chunk
    }

    fun remove(chunkIndex: Int) {
        submittedChunks.remove(chunkIndex)
    }

    /** The exact engine payload recorded for [chunkIndex], if still tracked. */
    fun submitted(chunkIndex: Int): TtsChunk? = submittedChunks[chunkIndex]

    fun fallbackPassage(chunk: TtsChunk): TtsPassage? =
        if (chunk.messageIdHex.isEmpty() || chunk.visibleSpans.isEmpty()) {
            null
        } else {
            TtsPassage(
                messageIdHex = chunk.messageIdHex,
                sentenceIndex = chunk.sentenceIndex,
                projectionId = chunk.projectionId,
                timelineAt = chunk.timelineAt,
            )
        }

    fun passageForRange(
        chunk: TtsChunk,
        callbackStart: Int,
        callbackEnd: Int,
    ): TtsPassage? {
        val fallback = fallbackPassage(chunk) ?: return null
        val visibleWord = submittedChunks[chunk.index]?.visibleWordForRange(callbackStart, callbackEnd)
        return fallback.copy(visibleWord = visibleWord.orEmpty())
    }

    @Suppress("ReturnCount")
    private fun TtsChunk.visibleWordForRange(
        callbackStart: Int,
        callbackEnd: Int,
    ): List<TtsVisibleTextSpan>? {
        val sourceWord = sourceWordRange(callbackStart, callbackEnd) ?: return null
        if (!sourceText.isCompleteGraphemeWord(sourceWord, locale)) return null
        return visibleSpans.visibleRange(callbackStart, callbackEnd)
    }

    private fun TtsChunk.sourceWordRange(
        callbackStart: Int,
        callbackEnd: Int,
    ): TtsTextRange? {
        val bodyStart = senderPrefix?.end ?: 0
        val bodyLength = text.length - bodyStart
        val valid =
            callbackStart >= 0 &&
                callbackEnd > callbackStart &&
                callbackEnd <= text.length &&
                bodyStart in 0..text.length &&
                callbackStart >= bodyStart &&
                sourceStart >= 0 &&
                sourceEnd <= sourceText.length &&
                sourceEnd - sourceStart == bodyLength
        return if (valid) {
            TtsTextRange(
                start = sourceStart + callbackStart - bodyStart,
                end = sourceStart + callbackEnd - bodyStart,
            )
        } else {
            null
        }
    }

    private fun List<TtsSpokenTextSpan>.visibleRange(
        start: Int,
        end: Int,
    ): List<TtsVisibleTextSpan>? {
        val visible = ArrayList<TtsVisibleTextSpan>()
        var cursor = start
        for (span in sortedBy { it.spoken.start }) {
            val overlapStart = maxOf(start, span.spoken.start)
            val overlapEnd = minOf(end, span.spoken.end)
            if (overlapStart >= overlapEnd) continue
            if (overlapStart != cursor) return null
            val visibleStart = span.visible.start + (overlapStart - span.spoken.start)
            val next =
                TtsVisibleTextSpan(
                    leafId = span.visible.leafId,
                    start = visibleStart,
                    end = visibleStart + (overlapEnd - overlapStart),
                )
            val previous = visible.lastOrNull()
            if (previous?.leafId == next.leafId && previous.end == next.start) {
                visible[visible.lastIndex] = previous.copy(end = next.end)
            } else {
                visible += next
            }
            cursor = overlapEnd
        }
        return visible.takeIf { cursor == end && it.isNotEmpty() }
    }
}

private fun String.isCompleteGraphemeWord(
    range: TtsTextRange,
    locale: Locale,
): Boolean =
    isUtf16Boundary(range.start) &&
        isUtf16Boundary(range.end) &&
        isGraphemeBoundary(range.start, locale) &&
        isGraphemeBoundary(range.end, locale) &&
        isCompleteWord(range.start, range.end, locale)

private fun String.isUtf16Boundary(index: Int): Boolean =
    index == 0 ||
        index == length ||
        !(this[index - 1].isHighSurrogate() && this[index].isLowSurrogate())

private fun String.isGraphemeBoundary(
    index: Int,
    locale: Locale,
): Boolean {
    val edge = index == 0 || index == length
    if (edge) return true
    val current = codePointAt(index)
    val previous = codePointBefore(index)
    val joinsCluster =
        current == ZERO_WIDTH_JOINER ||
            previous == ZERO_WIDTH_JOINER ||
            current.isCombiningCodePoint() ||
            current.isVariationSelector() ||
            current.isEmojiModifier()
    return !joinsCluster &&
        BreakIterator
            .getCharacterInstance(locale)
            .apply { setText(this@isGraphemeBoundary) }
            .isBoundary(index)
}

private fun String.isCompleteWord(
    start: Int,
    end: Int,
    locale: Locale,
): Boolean {
    val iterator = BreakIterator.getWordInstance(locale).apply { setText(this@isCompleteWord) }
    if (!iterator.isBoundary(start) || iterator.following(start) != end) return false

    var offset = start
    var completeWord = false
    while (offset < end && !completeWord) {
        val codePoint = codePointAt(offset)
        completeWord = Character.isLetterOrDigit(codePoint)
        offset += Character.charCount(codePoint)
    }
    return completeWord
}

private fun Int.isCombiningCodePoint(): Boolean =
    when (Character.getType(this)) {
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt(),
        -> true

        else -> false
    }

private fun Int.isVariationSelector(): Boolean =
    this in VARIATION_SELECTOR_START..VARIATION_SELECTOR_END ||
        this in SUPPLEMENTARY_VARIATION_SELECTOR_START..SUPPLEMENTARY_VARIATION_SELECTOR_END

private fun Int.isEmojiModifier(): Boolean = this in EMOJI_MODIFIER_START..EMOJI_MODIFIER_END

private const val ZERO_WIDTH_JOINER = 0x200D
private const val VARIATION_SELECTOR_START = 0xFE00
private const val VARIATION_SELECTOR_END = 0xFE0F
private const val SUPPLEMENTARY_VARIATION_SELECTOR_START = 0xE0100
private const val SUPPLEMENTARY_VARIATION_SELECTOR_END = 0xE01EF
private const val EMOJI_MODIFIER_START = 0x1F3FB
private const val EMOJI_MODIFIER_END = 0x1F3FF
