package dev.ipf.darkmatter.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

object IdentityFormatter {
    fun short(
        value: String,
        prefix: Int = 8,
        suffix: Int = 4,
    ): String {
        if (value.length <= prefix + suffix + 1) return value
        return "${value.take(prefix)}...${value.takeLast(suffix)}"
    }

    fun initials(name: String): String {
        // Iterate code points so a leading non-BMP grapheme (emoji, CJK
        // extensions, mathematical alphanumerics) is taken whole and not as
        // a lone surrogate half.
        val words =
            name
                .trim()
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
        val letters =
            when {
                words.size >= 2 -> firstCodePoint(words[0]) + firstCodePoint(words[1])
                words.size == 1 -> firstTwoCodePoints(words[0])
                else -> "DM"
            }
        return letters.uppercase()
    }

    private fun firstCodePoint(value: String): String {
        if (value.isEmpty()) return ""
        val codePoint = value.codePointAt(0)
        return String(Character.toChars(codePoint))
    }

    private fun firstTwoCodePoints(value: String): String {
        if (value.isEmpty()) return ""
        val first = value.codePointAt(0)
        val firstWidth = Character.charCount(first)
        if (firstWidth >= value.length) return String(Character.toChars(first))
        val second = value.codePointAt(firstWidth)
        return String(Character.toChars(first)) + String(Character.toChars(second))
    }

    private const val CLOCK_SKEW_TOLERANCE_SECONDS = 60L

    fun relativeTime(
        epochSeconds: ULong,
        copy: RelativeTimeCopy = RelativeTimeCopy.Default,
        locale: Locale = Locale.getDefault(),
    ): String {
        if (epochSeconds == 0uL) return ""
        val instant = Instant.ofEpochSecond(epochSeconds.toLong())
        val now = Instant.now()
        val delta = now.epochSecond - instant.epochSecond
        return when {
            // Clock skew within tolerance reads as "now", not "future".
            delta < -CLOCK_SKEW_TOLERANCE_SECONDS -> copy.future
            delta < 60 -> copy.now
            // Plural-aware unit rendering: the callbacks resolve the correct
            // grammatical form (e.g. Russian one/few/many) for the count. The
            // count is clamped to a non-negative Int — every delta here is well
            // within the day/week ranges below, so the conversion is safe.
            delta < 3_600 -> copy.minutes((delta / 60).toInt())
            delta < 86_400 -> copy.hours((delta / 3_600).toInt())
            delta < 604_800 -> copy.days((delta / 86_400).toInt())
            else ->
                // Locale-aware month-day ordering rather than forced "MMM d".
                DateTimeFormatter
                    .ofLocalizedDate(FormatStyle.MEDIUM)
                    .withLocale(locale)
                    .withZone(ZoneId.systemDefault())
                    .format(instant)
        }
    }
}

/**
 * Copy used by [IdentityFormatter.relativeTime].
 *
 * The unit branches (minutes / hours / days) are resolved through plural-aware
 * callbacks rather than `String.format` against a flat `<string>`. This lets the
 * Compose layer back each callback with `Resources.getQuantityString(...)` so
 * inflected locales (Russian one/few/many, Polish, Arabic, ...) render the
 * grammatically correct form for the count. Keeping the callbacks here — instead
 * of threading a `Context` into [IdentityFormatter] — preserves the formatter as
 * a pure, JVM-testable function with no Android dependency.
 */
data class RelativeTimeCopy(
    val future: String,
    val now: String,
    val minutes: (count: Int) -> String,
    val hours: (count: Int) -> String,
    val days: (count: Int) -> String,
) {
    companion object {
        /**
         * Locale-agnostic fallback used as the default argument and in pure unit
         * tests. Renders compact non-pluralized forms (e.g. "2m", "3h", "5d").
         * Real UI supplies plural-aware callbacks via the Compose layer.
         */
        val Default =
            RelativeTimeCopy(
                future = "future",
                now = "now",
                minutes = { count -> "${count}m" },
                hours = { count -> "${count}h" },
                days = { count -> "${count}d" },
            )
    }
}
