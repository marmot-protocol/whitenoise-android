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

/** A whole count of the coarsest relative unit, its unit code and its direction from now. */
internal data class TimestampRelativeAmount(
    val count: Long,
    val unit: Char,
    val future: Boolean,
)

private val timestampRelativeScales = longArrayOf(31536000, 2592000, 86400, 3600, 60, 1)
private const val TIMESTAMP_RELATIVE_UNITS = "yMdhms"
private const val MILLIS_PER_SECOND = 1000L

// Bounds each wait, so a label recovers within an hour if the wall clock jumps without a broadcast.
private const val TIMESTAMP_REFRESH_CAP_SECONDS = 3600L

/** Splits the unsigned distance between two signed instants into a count of its coarsest unit. */
internal fun timestampRelativeAmount(
    seconds: Long,
    now: Long,
): TimestampRelativeAmount {
    val (distance, future) = timestampDistance(seconds, now)
    val index = timestampRelativeScaleIndex(distance)
    return TimestampRelativeAmount(
        (distance / timestampRelativeScales[index].toULong()).toLong(),
        TIMESTAMP_RELATIVE_UNITS[index],
        future,
    )
}

/** Whole seconds until the relative label for [seconds] changes, as seen at [now]. */
internal fun timestampRelativeChangeSeconds(
    seconds: Long,
    now: Long,
): Long {
    val (distance, future) = timestampDistance(seconds, now)
    val scale = timestampRelativeScales[timestampRelativeScaleIndex(distance)]
    val remainder = (distance % scale.toULong()).toLong()
    // A past count grows at the next whole unit, a future count shrinks once its remainder drains.
    return if (future) remainder + 1 else scale - remainder
}

/** Milliseconds from [nowMillis] until the soonest of [relativeSeconds] changes its label. */
internal fun timestampRelativeRefreshMillis(
    relativeSeconds: List<Long>,
    nowMillis: Long,
): Long {
    val now = Math.floorDiv(nowMillis, MILLIS_PER_SECOND)
    val wait =
        relativeSeconds
            .minOf { timestampRelativeChangeSeconds(it, now) }
            .coerceAtMost(TIMESTAMP_REFRESH_CAP_SECONDS)
    // Land on the second boundary, where Instant.now().epochSecond yields the new label.
    return wait * MILLIS_PER_SECOND - Math.floorMod(nowMillis, MILLIS_PER_SECOND)
}

/** The unsigned distance between two signed instants and whether [seconds] lies after [now]. */
private fun timestampDistance(
    seconds: Long,
    now: Long,
): Pair<ULong, Boolean> {
    // Bias signed instants into unsigned order before subtracting: even both i64 extremes fit.
    val at = seconds.toULong() xor (1uL shl 63)
    val current = now.toULong() xor (1uL shl 63)
    val future = at > current
    return (if (future) at - current else current - at) to future
}

/** Index of the coarsest unit that [distance] fills at least once, falling back to seconds. */
private fun timestampRelativeScaleIndex(distance: ULong): Int {
    var index = 0
    while (index < timestampRelativeScales.lastIndex && distance < timestampRelativeScales[index].toULong()) {
        index++
    }
    return index
}

/** Formats [seconds] in [style] for [locale] and [zone], falling back to the canonical token out of range. */
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
