package dev.ipf.whitenoise.android.audio.tts.speech

import java.time.LocalDate
import java.time.temporal.IsoFields

internal object CalendarSpeech {
    private const val MONTHS_PER_YEAR = 12
    private const val DAYS_PER_WEEK = 7
    private const val LAST_ISO_WEEK_DAY = 28
    private const val MONTH_ABBREVIATION_LENGTH = 3
    private val months =
        listOf(
            "January",
            "February",
            "March",
            "April",
            "May",
            "June",
            "July",
            "August",
            "September",
            "October",
            "November",
            "December",
        )
    private val weekdays = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

    private fun date(
        year: Int,
        month: Int,
        day: Int,
    ): String? =
        runCatching {
            LocalDate.of(year, month, day)
        }.getOrNull()?.let { "${months[month - 1]} ${EnglishNumbers.ordinal(day)}, ${EnglishNumbers.year(year)}" }

    fun recognize(
        token: String,
        context: SpeechContext,
    ): String? {
        val rules =
            listOf(
                Regex("([0-9]{4})-([0-9]{2})-([0-9]{2})(?:T(.+))?") to { match: MatchResult -> isoDate(match) },
                Regex("([0-9]{4})-W([0-9]+)-([0-9]+)") to { match: MatchResult -> weekDate(match) },
                Regex("([0-9]{1,2})[/.]([0-9]{1,2})[/.]([0-9]{2}|[0-9]{4})") to
                    { match: MatchResult -> numericDate(match, context) },
                Regex("([A-Za-z]+) ([0-9]{1,2})(?:st|nd|rd|th)?(?:[–-]([0-9]{1,2}))?,? ([0-9]{4})") to
                    { match: MatchResult -> monthFirstDate(match) },
                Regex("([0-9]{1,2}) ([A-Za-z]+) ([0-9]{4})") to { match: MatchResult -> dayFirstDate(match) },
                Regex("([0-9]{4})-([0-9]{2,3})") to { match: MatchResult -> partialDate(match, context) },
            )
        val rule =
            rules.firstNotNullOfOrNull { (pattern, parse) ->
                pattern.matchEntire(token)?.let { match -> { parse(match) } }
            }
        return rule?.invoke()
    }

    // Numeric indices refer to the fixed capture groups in this grammar.
    @Suppress("MagicNumber")
    private fun isoDate(match: MatchResult): String? {
        val day =
            date(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt())
                ?: return null
        return if (match.groupValues[4].isEmpty()) day else ClockSpeech.dateTime(day, match.groupValues[4])
    }

    // Numeric indices refer to the fixed capture groups in this grammar.
    @Suppress("MagicNumber")
    private fun weekDate(match: MatchResult): String? {
        val year = match.groupValues[1].toInt()
        val week = match.groupValues[2].toIntOrNull()
        val day = match.groupValues[3].toIntOrNull()
        val maxWeek = LocalDate.of(year, MONTHS_PER_YEAR, LAST_ISO_WEEK_DAY).get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        return if (week in 1..maxWeek &&
            day in 1..DAYS_PER_WEEK
        ) {
            "${weekdays[
                requireNotNull(
                    day,
                ) - 1,
            ]} of week ${EnglishNumbers.cardinal(requireNotNull(week).toLong())}, ${EnglishNumbers.year(year)}"
        } else {
            null
        }
    }

    // Numeric indices refer to the fixed capture groups in this grammar.
    @Suppress("MagicNumber")
    private fun numericDate(
        match: MatchResult,
        context: SpeechContext,
    ): String? {
        val a = match.groupValues[1].toInt()
        val b = match.groupValues[2].toInt()
        val year = match.groupValues[3].toInt()
        val token = match.value
        val shortYear = match.groupValues[3].length == 2
        val us = context.sourceFormatLocale?.country == "US"
        val explicit = context.sourceFormatLocale != null
        val ambiguous = !explicit && a <= MONTHS_PER_YEAR && b <= MONTHS_PER_YEAR
        return when {
            shortYear || ambiguous -> EnglishNumbers.digits(token)
            us || !explicit && b > MONTHS_PER_YEAR -> date(year, a, b)
            else -> date(year, b, a)
        }
    }

    // Numeric indices refer to the fixed capture groups in this grammar.
    @Suppress("MagicNumber")
    private fun monthFirstDate(match: MatchResult): String? {
        val month = month(match.groupValues[1]) ?: return null
        val year = match.groupValues[4].toInt()
        return date(year, month, match.groupValues[2].toInt())?.let { first ->
            if (match.groupValues[3].isEmpty()) first else dateRange(match, year, month, first)
        }
    }

    private fun dateRange(
        match: MatchResult,
        year: Int,
        month: Int,
        first: String,
    ): String? {
        val end = match.groupValues[3].toInt()
        if (date(year, month, end) == null || end < match.groupValues[2].toInt()) return null
        return first.substringBefore(',') + " through ${EnglishNumbers.ordinal(end)}, ${EnglishNumbers.year(year)}"
    }

    // Numeric indices refer to the fixed capture groups in this grammar.
    @Suppress("MagicNumber")
    private fun dayFirstDate(match: MatchResult): String? {
        return date(
            match.groupValues[3].toInt(),
            month(match.groupValues[2]) ?: return null,
            match.groupValues[1].toInt(),
        )
    }

    // Numeric indices refer to the fixed capture groups in this grammar.
    @Suppress("MagicNumber")
    private fun partialDate(
        match: MatchResult,
        context: SpeechContext,
    ): String? {
        val year = match.groupValues[1].toInt()
        val field = match.groupValues[2].toInt()
        return when (context.semanticHint) {
            SpeechSemanticHint.YearMonth ->
                if (field in 1..MONTHS_PER_YEAR) "${months[field - 1]} ${EnglishNumbers.year(year)}" else null
            SpeechSemanticHint.OrdinalDate ->
                runCatching { LocalDate.ofYearDay(year, field) }
                    .getOrNull()
                    ?.let { date(year, it.monthValue, it.dayOfMonth) }
            else -> null
        }
    }

    private fun month(value: String): Int? =
        months
            .indexOfFirst {
                it.equals(value, true) ||
                    it.take(MONTH_ABBREVIATION_LENGTH).equals(value.take(MONTH_ABBREVIATION_LENGTH), true)
            }.takeIf {
                it >=
                    0
            }?.plus(1)
}
