package dev.ipf.whitenoise.android.ui

import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** The signed seconds remain canonical; no rendered label is stored on a message. */
internal fun markdownTimestampToken(
    seconds: Long,
    style: Char,
): String = "<t:$seconds:$style>"

internal data class TimestampRelativeAmount(
    val count: Long,
    val unit: Char,
    val future: Boolean,
)

private val timestampRelativeScales = longArrayOf(31536000, 2592000, 86400, 3600, 60, 1)
private const val TIMESTAMP_RELATIVE_UNITS = "yMdhms"

internal fun timestampRelativeAmount(
    seconds: Long,
    now: Long,
): TimestampRelativeAmount {
    // Bias signed instants into unsigned order before subtracting: even both i64 extremes fit.
    val at = seconds.toULong() xor (1uL shl 63)
    val current = now.toULong() xor (1uL shl 63)
    val future = at > current
    val distance = if (future) at - current else current - at
    var index = 0
    while (index < timestampRelativeScales.lastIndex && distance < timestampRelativeScales[index].toULong()) index++
    return TimestampRelativeAmount((distance / timestampRelativeScales[index].toULong()).toLong(), TIMESTAMP_RELATIVE_UNITS[index], future)
}

internal fun markdownTimestampAbsolute(
    seconds: Long,
    style: Char,
    locale: Locale = Locale.getDefault(),
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val formatter =
        when (style) {
            't' -> DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
            'T' -> DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM)
            'd' -> DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
            'D' -> DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
            'F' -> DateTimeFormatter.ofLocalizedDateTime(FormatStyle.FULL, FormatStyle.SHORT)
            's' -> DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT, FormatStyle.SHORT)
            'S' -> DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT, FormatStyle.MEDIUM)
            else -> DateTimeFormatter.ofLocalizedDateTime(FormatStyle.LONG, FormatStyle.SHORT)
        }
    return try {
        formatter.withLocale(locale).withZone(zone).format(Instant.ofEpochSecond(seconds))
    } catch (_: DateTimeException) {
        // The parser accepts all i64 seconds; java.time has a smaller calendar range.
        markdownTimestampToken(seconds, style)
    }
}
