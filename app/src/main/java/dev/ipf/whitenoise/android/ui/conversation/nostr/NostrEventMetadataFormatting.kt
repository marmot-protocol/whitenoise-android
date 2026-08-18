package dev.ipf.whitenoise.android.ui.conversation.nostr

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun String.asDurationLabel(): String? {
    val seconds = toLongOrNull()?.takeIf { it in 0..MAX_DURATION_SECONDS } ?: return null
    val minutes = seconds / SECONDS_PER_MINUTE
    val remainder = seconds % SECONDS_PER_MINUTE
    return if (minutes == 0L) "${remainder}s" else "$minutes:${remainder.toString().padStart(2, '0')}"
}

internal fun String.asByteCountLabel(): String? {
    val bytes = toLongOrNull()?.takeIf { it in 0..MAX_FILE_BYTES } ?: return null
    return when {
        bytes >= BYTES_PER_MEBIBYTE ->
            String.format(Locale.ROOT, "%.1f MB", bytes / BYTES_PER_MEBIBYTE.toDouble())
        bytes >= BYTES_PER_KIBIBYTE ->
            String.format(Locale.ROOT, "%.1f KB", bytes / BYTES_PER_KIBIBYTE.toDouble())
        else -> "$bytes B"
    }
}

internal fun String.asEpochLabel(): String? =
    toLongOrNull()
        ?.takeIf { it > 0 }
        ?.let { seconds ->
            runCatching {
                DateTimeFormatter.ISO_LOCAL_DATE.format(
                    Instant.ofEpochSecond(seconds).atZone(ZoneOffset.UTC),
                )
            }.getOrNull()
        }

private const val SECONDS_PER_MINUTE = 60L
private const val BYTES_PER_KIBIBYTE = 1_024L
private const val BYTES_PER_MEBIBYTE = BYTES_PER_KIBIBYTE * BYTES_PER_KIBIBYTE
private const val MAX_DURATION_SECONDS = 7 * 24 * SECONDS_PER_MINUTE * SECONDS_PER_MINUTE
private const val MAX_FILE_BYTES = 16L * BYTES_PER_KIBIBYTE * BYTES_PER_KIBIBYTE * BYTES_PER_KIBIBYTE
