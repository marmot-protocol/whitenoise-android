package dev.ipf.whitenoise.android.media.sandbox

internal const val MAX_MEDIA_DURATION_MS = 24L * 60 * 60 * 1000
internal const val MAX_MEDIA_SOURCE_BYTES = 512L * 1024 * 1024

internal fun validMediaRange(
    size: Long,
    offset: Long,
    length: Long,
): Boolean = offset >= 0L && length > 0L && length <= size && offset <= size - length
