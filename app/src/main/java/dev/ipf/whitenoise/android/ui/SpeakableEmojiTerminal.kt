@file:Suppress("TooManyFunctions", "ReturnCount", "MaxLineLength")

package dev.ipf.whitenoise.android.ui

/** True when [text] ends with a complete Unicode emoji sequence rather than a lone extender. */
internal fun String.endsWithSpeakableEmojiSequence(): Boolean = trailingSpeakableEmojiSequenceStart()?.let { it < length } == true

private fun String.trailingSpeakableEmojiSequenceStart(): Int? {
    if (isEmpty()) return null
    var start = trailingSpeakableEmojiUnitStart(length)
    if (start == length) return null
    while (start > 0) {
        val joiner = codePointBefore(start)
        if (joiner != SPEAKABLE_ZERO_WIDTH_JOINER) break
        val beforeJoiner = start - Character.charCount(joiner)
        val joinedStart = trailingSpeakableEmojiUnitStart(beforeJoiner)
        if (joinedStart == beforeJoiner) return null
        start = joinedStart
    }
    return start
}

private fun String.trailingSpeakableEmojiUnitStart(end: Int): Int {
    if (end == 0) return end
    return trailingSpeakableTagSequenceStart(end)
        ?: trailingSpeakableKeycapSequenceStart(end)
        ?: trailingSpeakableRegionalIndicatorPairStart(end)
        ?: trailingSpeakableModifierSequenceStart(end)
        ?: trailingSpeakablePictographicSequenceStart(end)
        ?: end
}

private fun String.trailingSpeakableTagSequenceStart(end: Int): Int? {
    if (end == 0) return null
    var index = end
    val cancel = codePointBefore(index)
    if (!cancel.isSpeakableTagCancel()) return null
    index -= Character.charCount(cancel)
    var tagCount = 0
    while (index > 0) {
        val tag = codePointBefore(index)
        if (!tag.isSpeakableTagCharacter()) break
        tagCount++
        index -= Character.charCount(tag)
    }
    if (tagCount == 0) return null
    if (index == 0) return null
    val base = codePointBefore(index)
    if (base != SPEAKABLE_BLACK_FLAG) return null
    return index - Character.charCount(base)
}

private fun String.trailingSpeakableKeycapSequenceStart(end: Int): Int? {
    if (end == 0) return null
    var index = end
    val keycap = codePointBefore(index)
    if (keycap != SPEAKABLE_COMBINING_ENCLOSING_KEYCAP) return null
    index -= Character.charCount(keycap)
    if (index > 0) {
        val variation = codePointBefore(index)
        if (variation == SPEAKABLE_EMOJI_VARIATION_SELECTOR) {
            index -= Character.charCount(variation)
        }
    }
    if (index == 0) return null
    val base = codePointBefore(index)
    if (!base.isSpeakableKeycapBase()) return null
    return index - Character.charCount(base)
}

private fun String.trailingSpeakableRegionalIndicatorPairStart(end: Int): Int? {
    if (end < SPEAKABLE_REGIONAL_INDICATOR_UTF16_WIDTH) return null
    var index = end
    var regionalIndicatorCount = 0
    while (index > 0) {
        val symbol = codePointBefore(index)
        if (!symbol.isRegionalIndicatorSymbol()) break
        regionalIndicatorCount++
        index -= SPEAKABLE_REGIONAL_INDICATOR_UTF16_WIDTH
    }
    if (regionalIndicatorCount < 2 || regionalIndicatorCount % 2 != 0) return null
    return end - SPEAKABLE_REGIONAL_INDICATOR_UTF16_WIDTH * 2
}

private fun String.trailingSpeakableModifierSequenceStart(end: Int): Int? {
    if (end == 0) return null
    var index = end
    val modifier = codePointBefore(index)
    if (!modifier.isSpeakableEmojiModifier()) return null
    index -= Character.charCount(modifier)
    if (index > 0) {
        val variation = codePointBefore(index)
        if (variation == SPEAKABLE_EMOJI_VARIATION_SELECTOR) {
            index -= Character.charCount(variation)
        }
    }
    if (index == 0) return null
    val base = codePointBefore(index)
    if (!base.isEmojiModifierBase()) return null
    return index - Character.charCount(base)
}

private fun String.trailingSpeakablePictographicSequenceStart(end: Int): Int? {
    if (end == 0) return null
    var index = end
    var hasEmojiVariation = false
    if (index > 0) {
        val variation = codePointBefore(index)
        if (variation == SPEAKABLE_EMOJI_VARIATION_SELECTOR) {
            hasEmojiVariation = true
            index -= Character.charCount(variation)
        }
    }
    if (index == 0) return null
    val base = codePointBefore(index)
    return when {
        hasEmojiVariation && base.isEmojiDefaultText() -> index - Character.charCount(base)
        !hasEmojiVariation && base.isStandaloneEmojiPresentation() -> index - Character.charCount(base)
        else -> null
    }
}

private fun Int.isStandaloneEmojiPresentation(): Boolean =
    isEmojiPresentation() &&
        !isRegionalIndicatorSymbol() &&
        !isSpeakableEmojiModifier()

private fun Int.inSpeakableCodePointRanges(ranges: IntArray): Boolean {
    var index = 0
    while (index < ranges.size) {
        val start = ranges[index]
        val end = ranges[index + 1]
        if (this in start..end) return true
        index += 2
    }
    return false
}

private fun Int.isEmojiPresentation(): Boolean = inSpeakableCodePointRanges(SPEAKABLE_EMOJI_PRESENTATION_RANGES)

private fun Int.isEmojiDefaultText(): Boolean = inSpeakableCodePointRanges(SPEAKABLE_EMOJI_DEFAULT_TEXT_RANGES)

private fun Int.isEmojiModifierBase(): Boolean = inSpeakableCodePointRanges(SPEAKABLE_EMOJI_MODIFIER_BASE_RANGES)

private fun Int.isRegionalIndicatorSymbol(): Boolean = this in SPEAKABLE_REGIONAL_INDICATOR_START..SPEAKABLE_REGIONAL_INDICATOR_END

private fun Int.isSpeakableEmojiModifier(): Boolean = this in SPEAKABLE_EMOJI_MODIFIER_START..SPEAKABLE_EMOJI_MODIFIER_END

private fun Int.isSpeakableTagCharacter(): Boolean = this in SPEAKABLE_TAG_CHARACTER_START..SPEAKABLE_TAG_CHARACTER_END

private fun Int.isSpeakableTagCancel(): Boolean = this == SPEAKABLE_TAG_CANCEL

private fun Int.isSpeakableKeycapBase(): Boolean =
    this == SPEAKABLE_KEYCAP_POUND ||
        this == SPEAKABLE_KEYCAP_ASTERISK ||
        this in SPEAKABLE_KEYCAP_ZERO..SPEAKABLE_KEYCAP_NINE

private const val SPEAKABLE_ZERO_WIDTH_JOINER = 0x200D
private const val SPEAKABLE_EMOJI_VARIATION_SELECTOR = 0xFE0F
private const val SPEAKABLE_EMOJI_MODIFIER_START = 0x1F3FB
private const val SPEAKABLE_EMOJI_MODIFIER_END = 0x1F3FF
private const val SPEAKABLE_COMBINING_ENCLOSING_KEYCAP = 0x20E3
private const val SPEAKABLE_BLACK_FLAG = 0x1F3F4
private const val SPEAKABLE_REGIONAL_INDICATOR_START = 0x1F1E6
private const val SPEAKABLE_REGIONAL_INDICATOR_END = 0x1F1FF
private const val SPEAKABLE_REGIONAL_INDICATOR_UTF16_WIDTH = 2
private const val SPEAKABLE_TAG_CHARACTER_START = 0xE0020
private const val SPEAKABLE_TAG_CHARACTER_END = 0xE007E
private const val SPEAKABLE_TAG_CANCEL = 0xE007F
private const val SPEAKABLE_KEYCAP_POUND = '#'.code
private const val SPEAKABLE_KEYCAP_ASTERISK = '*'.code
private const val SPEAKABLE_KEYCAP_ZERO = '0'.code
private const val SPEAKABLE_KEYCAP_NINE = '9'.code
