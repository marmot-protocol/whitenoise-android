package dev.ipf.whitenoise.android.core

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
