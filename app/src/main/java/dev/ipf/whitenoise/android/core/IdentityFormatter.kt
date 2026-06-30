package dev.ipf.whitenoise.android.core

import java.text.BreakIterator
import java.time.Instant
import java.time.ZoneId
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

object IdentityFormatter {
    private const val ELLIPSIS = "..."

    fun short(
        value: String,
        prefix: Int = 8,
        suffix: Int = 4,
    ): String {
        // Skip the abbreviation when it would not actually shorten the input.
        // The previous guard used `+ 1` even though the ellipsis is 3 chars,
        // so 14-char inputs with the 8/4 defaults expanded to 15 chars.
        if (value.length <= prefix + suffix + ELLIPSIS.length) return value
        return "${value.take(prefix)}$ELLIPSIS${value.takeLast(suffix)}"
    }

    fun initials(name: String): String {
        val words =
            name
                .trim()
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
        // Candidate initials, taken as whole grapheme clusters so emoji,
        // surrogate pairs and ZWJ sequences (👨‍👩‍👧, 🏃‍♂️) are never split into a
        // lone surrogate half (#112). Two words → the lead grapheme of each;
        // one word → its first two graphemes.
        val candidates =
            when {
                words.size >= 2 -> listOfNotNull(firstGrapheme(words[0]), firstGrapheme(words[1]))
                words.size == 1 -> firstGraphemes(words[0], limit = 2)
                else -> emptyList()
            }
        if (candidates.isEmpty()) return "DM"
        // Prefer letters: an emoji or symbol rendered alone in the avatar circle
        // clips or shows as tofu, so a letter always wins when the name has one
        // ("Alice 😀" → "A", "😀 Alice" → "A"). Only a name with no letters at
        // all falls back to its first emoji grapheme ("😀🔥" → "😀"). See #427.
        val letters = candidates.filter { isLetter(it) }
        return if (letters.isNotEmpty()) {
            letters.take(2).joinToString("").uppercase()
        } else {
            candidates.first()
        }
    }

    /** First grapheme cluster of [value], or null when empty. */
    private fun firstGrapheme(value: String): String? = firstGraphemes(value, limit = 1).firstOrNull()

    /** Up to [limit] leading grapheme clusters of [value]. */
    private fun firstGraphemes(
        value: String,
        limit: Int,
    ): List<String> {
        if (value.isEmpty()) return emptyList()
        val boundaries = BreakIterator.getCharacterInstance()
        boundaries.setText(value)
        val out = mutableListOf<String>()
        var start = boundaries.first()
        var end = boundaries.next()
        while (end != BreakIterator.DONE && out.size < limit) {
            out.add(value.substring(start, end))
            start = end
            end = boundaries.next()
        }
        return out
    }

    private fun isLetter(grapheme: String): Boolean = grapheme.isNotEmpty() && Character.isLetter(grapheme.codePointAt(0))

    private const val CLOCK_SKEW_TOLERANCE_SECONDS = 60L

    // Upper bound for a displayable timestamp: 9999-12-31T23:59:59Z. `epochSeconds`
    // is untrusted peer input, and `ULong.toLong()` wraps any high-bit value to a
    // negative Long, which `Instant.ofEpochSecond` rejects with DateTimeException —
    // and the localized formatter throws on extreme years too. Clamping to a sane
    // window (plus the guards below) keeps a crafted timestamp from crashing the
    // row that renders it (#468).
    private const val MAX_DISPLAYABLE_EPOCH_SECONDS = 253_402_300_799L

    fun relativeTime(
        epochSeconds: ULong,
        copy: RelativeTimeCopy = RelativeTimeCopy.Default,
        locale: Locale = Locale.getDefault(),
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        if (epochSeconds == 0uL) return ""
        val seconds = epochSeconds.toLong().coerceIn(0L, MAX_DISPLAYABLE_EPOCH_SECONDS)
        val instant = runCatching { Instant.ofEpochSecond(seconds) }.getOrNull() ?: return copy.now
        val delta = now.epochSecond - instant.epochSecond
        return when {
            // Clock skew within tolerance reads as "now", not "future".
            delta < -CLOCK_SKEW_TOLERANCE_SECONDS -> copy.future
            delta < 60 -> copy.now
            // Plural-aware unit rendering: the callback resolves the correct
            // grammatical form (e.g. Russian one/few/many) for the count, clamped
            // to a non-negative Int (the sub-hour delta makes the conversion safe).
            delta < 3_600 -> copy.minutes((delta / 60).toInt())
            // Within the first 24 elapsed hours the elapsed hour count is the useful signal.
            // Same-date rows on a 25-hour DST fall-back civil day are handled below.
            delta < 86_400 -> copy.hours((delta / 3_600).toInt())
            // Past 24h the clock time is noise on a chat-list row (#848): the day
            // name (within the past week) or a date carries "when did this thread
            // last move" without the minute. No time component at any rung here.
            else -> {
                runCatching {
                    val messageDate = instant.atZone(zone).toLocalDate()
                    val days = ChronoUnit.DAYS.between(messageDate, now.atZone(zone).toLocalDate())
                    when {
                        // A DST fall-back civil day can be 25 hours long. A >=24h
                        // elapsed delta can still be on today's local date, so keep
                        // same-date rows on the compact-hour rung instead of
                        // incorrectly labeling them as yesterday (#862).
                        days == 0L -> copy.hours((delta / 3_600).toInt())
                        days == 1L -> copy.yesterday
                        days < 7L -> messageDate.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
                        days < 365L -> localizedDateWithoutYearFormatter(locale).format(messageDate)
                        else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale).format(messageDate)
                    }
                }.getOrDefault(copy.now)
            }
        }
    }

    private fun localizedDateWithoutYearFormatter(locale: Locale): DateTimeFormatter {
        val localizedPattern =
            DateTimeFormatterBuilder.getLocalizedDateTimePattern(
                FormatStyle.MEDIUM,
                null,
                IsoChronology.INSTANCE,
                locale,
            )
        val pattern = stripYearFromLocalizedDatePattern(localizedPattern)
        return DateTimeFormatter.ofPattern(pattern.ifBlank { "d MMM" }, locale)
    }

    internal fun stripYearFromLocalizedDatePattern(pattern: String): String {
        val chars = pattern.toMutableList()
        var index = 0
        var inQuote = false
        while (index < chars.size) {
            val char = chars[index]
            if (char == '\'') {
                inQuote = !inQuote
                index += 1
                continue
            }
            if (!inQuote && char == 'y') {
                val start = index
                while (index < chars.size && chars[index] == 'y') index += 1
                chars.subList(start, index).clear()
                index = start
                while (index < chars.size && chars[index].isYearSeparator()) chars.removeAt(index)
                while (index > 0 && chars[index - 1].isYearSeparator()) {
                    chars.removeAt(index - 1)
                    index -= 1
                }
            } else {
                index += 1
            }
        }
        return chars.joinToString("").trim().trim(',', '.', '/', '-', ' ')
    }

    private fun Char.isYearSeparator(): Boolean = this.isWhitespace() || this in setOf(',', '.', '/', '-', '年')
}

/**
 * Copy used by [IdentityFormatter.relativeTime].
 *
 * The sub-day unit branches ([minutes], [hours]) are resolved through
 * plural-aware callbacks rather than `String.format` against a flat `<string>`.
 * This lets the Compose layer back them with `Resources.getQuantityString(...)`
 * so inflected locales render the grammatically correct form while keeping
 * [IdentityFormatter] pure. [yesterday] is a flat localized string; the weekday
 * and date rungs are produced from the locale directly and need no copy.
 */
data class RelativeTimeCopy(
    val future: String,
    val now: String,
    val yesterday: String,
    val minutes: (count: Int) -> String,
    val hours: (count: Int) -> String,
) {
    companion object {
        /**
         * Locale-agnostic fallback used as the default argument and in pure unit
         * tests. Renders compact non-pluralized forms (e.g. "2m", "3h"). Real UI
         * supplies plural-aware callbacks and a localized "yesterday" via Compose.
         */
        val Default =
            RelativeTimeCopy(
                future = "future",
                now = "now",
                yesterday = "yesterday",
                minutes = { count -> "${count}m" },
                hours = { count -> "${count}h" },
            )
    }
}
