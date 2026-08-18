package dev.ipf.whitenoise.android.ui.conversation.nostr

import dev.ipf.whitenoise.android.core.ProfileSanitizer

internal fun String.safeField(): String = sanitizedText().takeCodePoints(MAX_FIELD_CODE_POINTS)

internal fun String.safeExcerpt(): String = sanitizedText().takeCodePoints(MAX_EXCERPT_CODE_POINTS)

internal fun String.safeReaderBody(): String =
    ProfileSanitizer
        .stripUnsafe(this)
        .trim()
        .takeCodePoints(MAX_READER_BODY_CODE_POINTS)

private fun String.sanitizedText(): String =
    filterNot { it == '\u0000' || (it.isISOControl() && !it.isWhitespace()) }
        .trim()

private fun String.takeCodePoints(maxCodePoints: Int): String {
    if (codePointCount(0, length) <= maxCodePoints) return this
    return substring(0, offsetByCodePoints(0, maxCodePoints))
}

private const val MAX_FIELD_CODE_POINTS = 160
private const val MAX_EXCERPT_CODE_POINTS = 420
private const val MAX_READER_BODY_CODE_POINTS = 64 * 1_024
