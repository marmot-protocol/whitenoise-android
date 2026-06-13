package dev.ipf.darkmatter.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
        val words =
            name
                .trim()
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
        val letters =
            when {
                // Iterate code points rather than UTF-16 `Char`s so a leading
                // non-BMP grapheme (emoji, mathematical alphanumerics, many
                // CJK extensions) is taken whole rather than as a lone
                // surrogate half that renders as a replacement glyph.
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

    fun relativeTime(epochSeconds: ULong): String {
        if (epochSeconds == 0uL) return ""
        val instant = Instant.ofEpochSecond(epochSeconds.toLong())
        val now = Instant.now()
        val delta = now.epochSecond - instant.epochSecond
        return when {
            // Sender clock skew or a relay-provided future timestamp.
            delta < 0 -> "future"
            delta < 60 -> "now"
            delta < 3_600 -> "${delta / 60}m"
            delta < 86_400 -> "${delta / 3_600}h"
            delta < 604_800 -> "${delta / 86_400}d"
            else ->
                DateTimeFormatter
                    .ofPattern("MMM d")
                    .withZone(ZoneId.systemDefault())
                    .format(instant)
        }
    }
}
