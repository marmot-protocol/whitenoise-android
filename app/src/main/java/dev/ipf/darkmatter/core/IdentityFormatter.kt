package dev.ipf.darkmatter.core

import java.text.BreakIterator
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
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
    ): String {
        if (epochSeconds == 0uL) return ""
        val seconds = epochSeconds.toLong().coerceIn(0L, MAX_DISPLAYABLE_EPOCH_SECONDS)
        val instant = runCatching { Instant.ofEpochSecond(seconds) }.getOrNull() ?: return copy.now
        val now = Instant.now()
        val delta = now.epochSecond - instant.epochSecond
        return when {
            // Clock skew within tolerance reads as "now", not "future".
            delta < -CLOCK_SKEW_TOLERANCE_SECONDS -> copy.future
            delta < 60 -> copy.now
            // Plural-aware unit rendering: the callback resolves the correct
            // grammatical form (e.g. Russian one/few/many) for the count, clamped
            // to a non-negative Int (the sub-hour delta makes the conversion safe).
            delta < 3_600 -> copy.minutes((delta / 60).toInt())
            // Past an hour the clock is more informative than "N hours ago": show
            // the localized time, prefixed with the localized date once the
            // instant falls on an earlier day.
            else -> {
                val zone = ZoneId.systemDefault()
                runCatching {
                    val time =
                        DateTimeFormatter
                            .ofLocalizedTime(FormatStyle.SHORT)
                            .withLocale(locale)
                            .withZone(zone)
                            .format(instant)
                    if (instant.atZone(zone).toLocalDate() == now.atZone(zone).toLocalDate()) {
                        time
                    } else {
                        // Day + month, no year — the year is noise on a chat-list
                        // row (e.g. "14 Jun 08:14").
                        val date =
                            DateTimeFormatter
                                .ofPattern("d MMM", locale)
                                .withZone(zone)
                                .format(instant)
                        "$date $time"
                    }
                }.getOrDefault(copy.now)
            }
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
