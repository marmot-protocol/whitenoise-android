package dev.ipf.whitenoise.android.core

import java.text.BreakIterator
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object IdentityFormatter {
    private const val ELLIPSIS = "..."
    private const val NOSTR_HEX_ID_LENGTH = 64

    // Immutable/reusable objects hoisted off the scroll-hot avatar/timestamp path
    // so each visible row does not re-allocate them (#1011).
    private val WHITESPACE = Regex("\\s+")
    private val YEAR_SEPARATORS = setOf(',', '.', '/', '-', '年')
    private const val HOUR_PATTERN_TOKENS = "hKHk"
    private const val DAY_PERIOD_PATTERN_TOKENS = "abB"

    // BreakIterator is not thread-safe, so keep one per thread rather than
    // allocating a fresh instance on every initials() call.
    private val graphemeBreaker = ThreadLocal.withInitial { BreakIterator.getCharacterInstance() }

    // DateTimeFormatters are immutable and thread-safe; memoize locale-derived
    // formatters used on timestamp display paths.
    private val noYearFormatters = ConcurrentHashMap<Locale, DateTimeFormatter>()
    private val shortDateFormatters = ConcurrentHashMap<Locale, DateTimeFormatter>()
    private val shortTimeFormatters = ConcurrentHashMap<Locale, DateTimeFormatter>()
    private val coercedClockFormatters = ConcurrentHashMap<Pair<Locale, Boolean>, DateTimeFormatter>()
    private val displayFormatters = ConcurrentHashMap<DisplayFormatterKey, DateTimeFormatter>()
    private val numericDateFormatters = ConcurrentHashMap<String, DateTimeFormatter>()

    private data class DisplayFormatterKey(
        val locale: Locale,
        val display: DateTimeDisplay,
        val dateStyle: FormatStyle?,
        val timeStyle: FormatStyle?,
        val withoutYear: Boolean,
    )

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

    fun isNostrIdentityFallback(value: String): Boolean {
        val normalized = value.trim().lowercase(Locale.ROOT)
        return normalized.startsWith("npub1") ||
            (normalized.length == NOSTR_HEX_ID_LENGTH && normalized.all { it.isDigit() || it in 'a'..'f' })
    }

    /**
     * Single-glyph monogram for a display name: the first letter, else the first emoji grapheme, else the DM
     * fallback.
     */
    fun initials(name: String): String {
        val words =
            name
                .trim()
                .split(WHITESPACE)
                .filter { it.isNotBlank() }
        // The prototype's monogram is one glyph. Candidates are whole grapheme
        // clusters so emoji, surrogate pairs and ZWJ sequences (👨‍👩‍👧, 🏃‍♂️) are
        // never split into a lone surrogate half (#112): the lead grapheme of
        // the first two words, or the first two graphemes of a single word.
        val candidates =
            when {
                words.size >= 2 -> listOfNotNull(firstGrapheme(words[0]), firstGrapheme(words[1]))
                words.size == 1 -> firstGraphemes(words[0], limit = 2)
                else -> emptyList()
            }
        if (candidates.isEmpty()) return "DM"
        // Prefer a letter: an emoji or symbol rendered alone in the avatar circle
        // clips or shows as tofu, so the first letter wins when the name has one
        // ("Alice 😀" → "A", "😀 Alice" → "A"). Only a name with no letters at
        // all falls back to its first emoji grapheme ("😀🔥" → "😀"). See #427.
        val letters = candidates.filter { isLetter(it) }
        return if (letters.isNotEmpty()) {
            letters.first().uppercase()
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
        // withInitial never yields null, but ThreadLocal.get() is typed nullable.
        val boundaries = graphemeBreaker.get()!!
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
    private const val BUBBLE_RELATIVE_TIME_WINDOW_SECONDS = 3_600L

    // Upper bound for a displayable timestamp: 9999-12-31T23:59:59Z. `epochSeconds`
    // is untrusted peer input, and `ULong.toLong()` wraps any high-bit value to a
    // negative Long, which `Instant.ofEpochSecond` rejects with DateTimeException —
    // and the localized formatter throws on extreme years too. Clamping to a sane
    // window (plus the guards below) keeps a crafted timestamp from crashing the
    // row that renders it (#468).
    internal const val MAX_DISPLAYABLE_EPOCH_SECONDS = 253_402_300_799L

    fun relativeTime(
        epochSeconds: ULong,
        copy: RelativeTimeCopy = RelativeTimeCopy.Default,
        locale: Locale = Locale.getDefault(),
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        dateFormat: DateFormatPreference = DateFormatPreference.Automatic,
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
                    val formattingLocale = DateTimeFormatState.formattingLocale(locale)
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
                        else -> compactDate(messageDate, withoutYear = days < 365L, formattingLocale, dateFormat)
                    }
                }.getOrDefault(copy.now)
            }
        }
    }

    /** A chat-list date: the explicit numeric order when chosen, else the locale's compact date. */
    private fun compactDate(
        date: LocalDate,
        withoutYear: Boolean,
        locale: Locale,
        dateFormat: DateFormatPreference,
    ): String {
        val numeric = if (withoutYear) dateFormat.withoutYearPattern else dateFormat.pattern
        return when {
            numeric != null -> numericDateFormatter(numeric).format(date)
            withoutYear -> localizedDateWithoutYearFormatter(locale).format(date)
            else -> shortDateFormatter(locale).format(date)
        }
    }

    /** Locale-aware clock time for an epoch-second stamp. */
    fun clockTime(
        epochSeconds: ULong,
        locale: Locale = Locale.getDefault(),
        zone: ZoneId = ZoneId.systemDefault(),
        force24Hour: Boolean? = null,
    ): String {
        if (epochSeconds == 0uL) return ""
        val seconds = epochSeconds.toLong().coerceIn(0L, MAX_DISPLAYABLE_EPOCH_SECONDS)
        val formattingLocale = DateTimeFormatState.formattingLocale(locale)
        val formatter =
            when (force24Hour) {
                null -> shortTimeFormatter(formattingLocale)
                else -> coercedClockFormatter(formattingLocale, force24Hour)
            }
        return runCatching {
            Instant
                .ofEpochSecond(seconds)
                .atZone(zone)
                .format(formatter)
        }.getOrDefault("")
    }

    /**
     * Compact bubble-footer time: elapsed copy for the first hour, then the localized clock time.
     *
     * The conversation's day separators keep ownership of the date while [now] makes the recent
     * label deterministic and lets the Compose owner advance it only when the screen is resumed.
     */
    fun messageBubbleTime(
        epochSeconds: ULong,
        copy: RelativeTimeCopy = RelativeTimeCopy.Default,
        locale: Locale = Locale.getDefault(),
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        force24Hour: Boolean? = null,
    ): String {
        if (epochSeconds == 0uL) return ""
        val seconds = epochSeconds.toLong().coerceIn(0L, MAX_DISPLAYABLE_EPOCH_SECONDS)
        return if (now.epochSecond - seconds < BUBBLE_RELATIVE_TIME_WINDOW_SECONDS) {
            relativeTime(epochSeconds, copy, locale, now, zone)
        } else {
            clockTime(epochSeconds, locale, zone, force24Hour)
        }
    }

    /** A calendar day in the user's explicit numeric order, else the locale's [style] date. */
    fun date(
        date: LocalDate,
        locale: Locale = Locale.getDefault(),
        dateFormat: DateFormatPreference = DateFormatPreference.Automatic,
        style: FormatStyle = FormatStyle.MEDIUM,
    ): String =
        runCatching {
            dateFormat.pattern?.let { pattern -> numericDateFormatter(pattern).format(date) }
                ?: displayFormatter(locale, DateTimeDisplay.LocaleDefault, style, null, false).format(date)
        }.getOrDefault("")

    /**
     * Absolute date and/or time for [instant] honoring [display]. A null [dateStyle] renders the
     * time only and a null [timeStyle] the date only; [withoutYear] drops the year from the date.
     * Returns an empty string when out of range.
     */
    @Suppress("LongParameterList")
    fun dateTime(
        instant: Instant,
        locale: Locale = Locale.getDefault(),
        zone: ZoneId = ZoneId.systemDefault(),
        display: DateTimeDisplay = DateTimeDisplay.LocaleDefault,
        dateStyle: FormatStyle? = FormatStyle.MEDIUM,
        timeStyle: FormatStyle? = FormatStyle.SHORT,
        withoutYear: Boolean = false,
    ): String {
        require(dateStyle != null || timeStyle != null) { "A date or time style is required" }
        return try {
            val formatter = displayFormatter(locale, display, dateStyle, timeStyle, withoutYear && dateStyle != null)
            formatter.format(instant.atZone(zone))
        } catch (_: DateTimeException) {
            ""
        } catch (_: ArithmeticException) {
            ""
        }
    }

    /** [dateTime] for an epoch-second stamp; zero and unrepresentable stamps render empty. */
    @Suppress("LongParameterList")
    fun dateTime(
        epochSeconds: ULong,
        locale: Locale = Locale.getDefault(),
        zone: ZoneId = ZoneId.systemDefault(),
        display: DateTimeDisplay = DateTimeDisplay.LocaleDefault,
        dateStyle: FormatStyle? = FormatStyle.MEDIUM,
        timeStyle: FormatStyle? = FormatStyle.SHORT,
        withoutYear: Boolean = false,
    ): String {
        val instant = epochSeconds.takeIf { it != 0uL }?.let { instant(it) } ?: return ""
        return dateTime(instant, locale, zone, display, dateStyle, timeStyle, withoutYear)
    }

    /** Whether an epoch-second stamp falls in [now]'s calendar year in [zone], for year-free labels. */
    fun isCurrentYear(
        epochSeconds: ULong,
        zone: ZoneId = ZoneId.systemDefault(),
        now: Instant = Instant.now(),
    ): Boolean =
        instant(epochSeconds)?.let { instant ->
            runCatching { instant.atZone(zone).year == now.atZone(zone).year }.getOrDefault(false)
        } ?: false

    /**
     * The calendar day an untrusted epoch-second stamp falls on in [zone], or null past
     * 9999-12-31T23:59:59Z. Inside that window neither the instant nor its zoned date can overflow.
     */
    fun localDate(
        epochSeconds: ULong,
        zone: ZoneId = ZoneId.systemDefault(),
    ): LocalDate? = instant(epochSeconds)?.atZone(zone)?.toLocalDate()

    /** Bounded received seconds, preserving zero for consumers that treat it as a valid epoch. */
    fun instant(epochSeconds: ULong): Instant? =
        epochSeconds
            .takeIf { it <= MAX_DISPLAYABLE_EPOCH_SECONDS.toULong() }
            ?.let { Instant.ofEpochSecond(it.toLong()) }

    /** Milliseconds for display/filter consumers, or null for an unknown date. */
    fun epochMillis(epochSeconds: ULong): Long? = instant(epochSeconds)?.toEpochMilli()

    /** [localDate], with stamps past that bound placed on its day in [zone] so grouping stays total and ordered. */
    fun clampedLocalDate(
        epochSeconds: ULong,
        zone: ZoneId = ZoneId.systemDefault(),
    ): LocalDate =
        instant(epochSeconds.coerceAtMost(MAX_DISPLAYABLE_EPOCH_SECONDS.toULong()))!!
            .atZone(zone)
            .toLocalDate()

    private fun numericDateFormatter(pattern: String): DateTimeFormatter =
        numericDateFormatters.computeIfAbsent(pattern) { DateTimeFormatter.ofPattern(it, Locale.ROOT) }

    private fun displayFormatter(
        locale: Locale,
        display: DateTimeDisplay,
        dateStyle: FormatStyle?,
        timeStyle: FormatStyle?,
        withoutYear: Boolean,
    ): DateTimeFormatter {
        // The region only reaches the formatter through the locale; drop it from the cache key.
        val key =
            DisplayFormatterKey(
                DateTimeFormatState.formattingLocale(locale),
                display.copy(region = null),
                dateStyle,
                timeStyle,
                withoutYear,
            )
        return displayFormatters.computeIfAbsent(key) { request ->
            if (request.display == DateTimeDisplay.LocaleDefault && !request.withoutYear) {
                // No override: the platform's localized formatter, exactly as before #3602.
                when {
                    request.timeStyle == null -> DateTimeFormatter.ofLocalizedDate(request.dateStyle)
                    request.dateStyle == null -> DateTimeFormatter.ofLocalizedTime(request.timeStyle)
                    else -> DateTimeFormatter.ofLocalizedDateTime(request.dateStyle, request.timeStyle)
                }.withLocale(request.locale)
            } else {
                val pattern =
                    displayPattern(
                        request.locale,
                        request.display,
                        request.dateStyle,
                        request.timeStyle,
                        request.withoutYear,
                    )
                DateTimeFormatter.ofPattern(pattern, request.locale)
            }
        }
    }

    /**
     * The pattern for [display]. The date and time parts are rewritten on their own, so the clock
     * rewrite never sees date fields: an explicit numeric date replaces the localized date part,
     * [withoutYear] strips its year, and an explicit clock system rewrites the time part's hours.
     * The rewritten parts go back into the locale's combined pattern, keeping its own glue.
     */
    internal fun displayPattern(
        locale: Locale,
        display: DateTimeDisplay,
        dateStyle: FormatStyle?,
        timeStyle: FormatStyle?,
        withoutYear: Boolean = false,
    ): String {
        fun localized(
            date: FormatStyle?,
            time: FormatStyle?,
        ) = DateTimeFormatterBuilder.getLocalizedDateTimePattern(date, time, IsoChronology.INSTANCE, locale)

        val date = dateStyle?.let { DatePart(localized(it, null), display.dateFormat, withoutYear) }
        val time = timeStyle?.let { TimePart(localized(null, it), display.use24Hour) }
        return when {
            date == null -> requireNotNull(time).rewritten
            time == null -> date.rewritten
            else -> replaceParts(localized(dateStyle, timeStyle), date, time)
        }
    }

    /** One localized part of a combined pattern and its rewrite. */
    private sealed interface PatternPart {
        val localized: String
        val rewritten: String
    }

    private class DatePart(
        override val localized: String,
        dateFormat: DateFormatPreference,
        withoutYear: Boolean,
    ) : PatternPart {
        override val rewritten: String =
            (if (withoutYear) dateFormat.withoutYearPattern else dateFormat.pattern)
                ?: if (withoutYear) stripYearFromLocalizedDatePattern(localized).ifBlank { localized } else localized
    }

    private class TimePart(
        override val localized: String,
        use24Hour: Boolean?,
    ) : PatternPart {
        override val rewritten: String = use24Hour?.let { coerceClockPattern(localized, it) } ?: localized
    }

    /** Puts the rewritten parts back into [combined], keeping the locale's own glue between them. */
    private fun replaceParts(
        combined: String,
        date: PatternPart,
        time: PatternPart,
    ): String {
        val placed = listOf(date, time).map { combined.indexOf(it.localized) to it }.sortedBy { it.first }
        val (first, second) = placed
        val separate = first.first >= 0 && first.first + first.second.localized.length <= second.first
        if (!separate) return "${date.rewritten} ${time.rewritten}"
        return buildString {
            var index = 0
            placed.forEach { (start, part) ->
                append(combined, index, start)
                append(part.rewritten)
                index = start + part.localized.length
            }
            append(combined, index, combined.length)
        }
    }

    private fun localizedDateWithoutYearFormatter(locale: Locale): DateTimeFormatter =
        noYearFormatters.computeIfAbsent(locale) { requestedLocale ->
            val localizedPattern =
                DateTimeFormatterBuilder.getLocalizedDateTimePattern(
                    FormatStyle.MEDIUM,
                    null,
                    IsoChronology.INSTANCE,
                    requestedLocale,
                )
            val pattern = stripYearFromLocalizedDatePattern(localizedPattern)
            DateTimeFormatter.ofPattern(pattern.ifBlank { "d MMM" }, requestedLocale)
        }

    private fun shortDateFormatter(locale: Locale): DateTimeFormatter =
        shortDateFormatters.computeIfAbsent(locale) { requestedLocale ->
            DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(requestedLocale)
        }

    private fun shortTimeFormatter(locale: Locale): DateTimeFormatter =
        shortTimeFormatters.computeIfAbsent(locale) { requestedLocale ->
            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(requestedLocale)
        }

    private fun coercedClockFormatter(
        locale: Locale,
        use24Hour: Boolean,
    ): DateTimeFormatter =
        coercedClockFormatters.computeIfAbsent(locale to use24Hour) { (requestedLocale, requested24) ->
            val localized =
                DateTimeFormatterBuilder.getLocalizedDateTimePattern(
                    null,
                    FormatStyle.SHORT,
                    IsoChronology.INSTANCE,
                    requestedLocale,
                )
            DateTimeFormatter.ofPattern(coerceClockPattern(localized, requested24), requestedLocale)
        }

    /**
     * Rewrites a localized SHORT time pattern to the requested clock system so
     * Android's explicit 12/24-hour setting wins over the locale default.
     * Quoted literals are preserved. 24-hour drops the day-period token and its
     * separator and zero-pads the hour; 12-hour uses a single-digit hour and
     * appends a day-period marker when the locale pattern has none.
     */
    internal fun coerceClockPattern(
        pattern: String,
        use24Hour: Boolean,
    ): String {
        // A pattern already in the requested clock system stays byte-identical,
        // so a preference matching the locale default changes nothing.
        if (patternMatchesClockSystem(pattern, use24Hour)) return pattern
        val out = StringBuilder()
        var index = 0
        var inQuote = false
        while (index < pattern.length) {
            val char = pattern[index]
            when {
                char == '\'' || inQuote -> {
                    if (char == '\'') inQuote = !inQuote
                    out.append(char)
                    index += 1
                }
                char in HOUR_PATTERN_TOKENS -> {
                    out.append(if (use24Hour) "HH" else "h")
                    index = endOfPatternRun(pattern, index)
                }
                char in DAY_PERIOD_PATTERN_TOKENS && use24Hour -> index = dropDayPeriod(pattern, index, out)
                else -> {
                    out.append(char)
                    index += 1
                }
            }
        }
        return finishCoercedPattern(out, pattern, use24Hour)
    }

    /**
     * Drops the day-period token at [start] with one separator run (a plain or narrow no-break
     * space): the one after it ("a h:mm"), else the one before it at the end ("h:mm a"). Glued
     * to a field ("Bh:mm") it goes alone. Returns the index after what was consumed.
     */
    private fun dropDayPeriod(
        pattern: String,
        start: Int,
        out: StringBuilder,
    ): Int {
        var index = endOfPatternRun(pattern, start)
        if (index < pattern.length && pattern[index].isWhitespace()) {
            while (index < pattern.length && pattern[index].isWhitespace()) index += 1
        } else if (index == pattern.length || !pattern[index].isLetter()) {
            while (out.isNotEmpty() && out.last().isWhitespace()) out.deleteCharAt(out.length - 1)
        }
        return index
    }

    private fun patternMatchesClockSystem(
        pattern: String,
        use24Hour: Boolean,
    ): Boolean =
        if (use24Hour) {
            patternContainsUnquoted(pattern, "Hk") && !patternContainsUnquoted(pattern, "hK")
        } else {
            patternContainsUnquoted(pattern, "hK") && !patternContainsUnquoted(pattern, "Hk")
        }

    private fun finishCoercedPattern(
        out: StringBuilder,
        pattern: String,
        use24Hour: Boolean,
    ): String {
        val result = out.toString().trim()
        val needsDayPeriod = !use24Hour && !patternContainsUnquoted(pattern, DAY_PERIOD_PATTERN_TOKENS)
        return if (needsDayPeriod) "$result a" else result
    }

    private fun endOfPatternRun(
        pattern: String,
        start: Int,
    ): Int {
        val token = pattern[start]
        var index = start
        while (index < pattern.length && pattern[index] == token) index += 1
        return index
    }

    private fun patternContainsUnquoted(
        pattern: String,
        tokens: String,
    ): Boolean {
        var inQuote = false
        for (char in pattern) {
            if (char == '\'') {
                inQuote = !inQuote
            } else if (!inQuote && char in tokens) {
                return true
            }
        }
        return false
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
                index = removeYearAffixAfter(chars, index)
                index = removeYearAffixBefore(chars, index)
            } else {
                index += 1
            }
        }
        return chars.joinToString("").trim().trim(',', '.', '/', '-', ' ')
    }

    private fun Char.isYearSeparator(): Boolean = this.isWhitespace() || this in YEAR_SEPARATORS

    private fun removeYearAffixAfter(
        chars: MutableList<Char>,
        index: Int,
    ): Int {
        while (index < chars.size && chars[index].isYearSeparator()) chars.removeAt(index)
        if (index < chars.size && chars[index] == '\'') {
            val close = chars.withIndex().firstOrNull { it.index > index && it.value == '\'' }?.index ?: -1
            if (close > index) {
                chars.subList(index, close + 1).clear()
                while (index < chars.size && chars[index].isYearSeparator()) chars.removeAt(index)
            }
        }
        return index
    }

    private fun removeYearAffixBefore(
        chars: MutableList<Char>,
        startIndex: Int,
    ): Int {
        var index = startIndex
        while (index > 0 && chars[index - 1].isYearSeparator()) {
            chars.removeAt(index - 1)
            index -= 1
        }
        if (index > 0 && chars[index - 1] == '\'') {
            val open = chars.subList(0, index - 1).lastIndexOf('\'')
            if (open >= 0) {
                chars.subList(open, index).clear()
                index = open
                while (index > 0 && chars[index - 1].isYearSeparator()) {
                    chars.removeAt(index - 1)
                    index -= 1
                }
            }
        }
        return index
    }
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
