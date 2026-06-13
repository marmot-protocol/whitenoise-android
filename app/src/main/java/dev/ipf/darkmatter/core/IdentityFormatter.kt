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
            // Sender clock skew or a relay-provided future timestamp.
            delta < 0 -> copy.future
            delta < 60 -> copy.now
            delta < 3_600 -> String.format(locale, copy.minutesFormat, delta / 60)
            delta < 86_400 -> String.format(locale, copy.hoursFormat, delta / 3_600)
            delta < 604_800 -> String.format(locale, copy.daysFormat, delta / 86_400)
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

data class RelativeTimeCopy(
    val future: String,
    val now: String,
    val minutesFormat: String,
    val hoursFormat: String,
    val daysFormat: String,
) {
    companion object {
        val Default =
            RelativeTimeCopy(
                future = "future",
                now = "now",
                minutesFormat = "%1\$dm",
                hoursFormat = "%1\$dh",
                daysFormat = "%1\$dd",
            )
    }
}
