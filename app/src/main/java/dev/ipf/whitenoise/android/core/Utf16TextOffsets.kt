package dev.ipf.whitenoise.android.core

import android.icu.text.BreakIterator
import java.util.Locale

internal fun String.codePointBoundaryAtOrBefore(offset: Int): Int {
    val clamped = offset.coerceIn(0, length)
    return if (
        clamped in 1 until length &&
        Character.isHighSurrogate(this[clamped - 1]) &&
        Character.isLowSurrogate(this[clamped])
    ) {
        clamped - 1
    } else {
        clamped
    }
}

internal fun String.codePointBoundaryAtOrAfter(offset: Int): Int {
    val clamped = offset.coerceIn(0, length)
    return if (
        clamped in 1 until length &&
        Character.isHighSurrogate(this[clamped - 1]) &&
        Character.isLowSurrogate(this[clamped])
    ) {
        clamped + 1
    } else {
        clamped
    }
}

internal fun String.graphemeBoundaryAtOrBefore(offset: Int): Int {
    val clamped = offset.coerceIn(0, length)
    val iterator = graphemeBreakIterator()
    return if (iterator.isBoundary(clamped)) clamped else iterator.preceding(clamped).coerceAtLeast(0)
}

internal fun String.graphemeBoundaryAtOrAfter(offset: Int): Int {
    val clamped = offset.coerceIn(0, length)
    val iterator = graphemeBreakIterator()
    if (iterator.isBoundary(clamped)) return clamped
    return iterator.following(clamped).takeUnless { it == BreakIterator.DONE } ?: length
}

private fun String.graphemeBreakIterator() = BreakIterator.getCharacterInstance(Locale.ROOT).also { it.setText(this) }
