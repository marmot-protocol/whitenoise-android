package dev.ipf.whitenoise.android.audio.tts.speech

internal object ClockSpeech {
    private const val OFFSET_HOUR_END = 3
    private const val OFFSET_MINUTE_START = 4
    private const val MAX_OFFSET_HOURS = 18
    private const val MAX_CLOCK_COMPONENT = 59
    private const val MAX_CLOCK_HOUR = 23
    private const val HALF_DAY_HOURS = 12
    private const val TWO_DIGIT_MINUTE = 10
    private const val MAX_FRACTION_DIGITS = 9

    fun dateTime(
        day: String,
        time: String,
    ): String? {
        val zone = Regex("(Z|[+-][0-9]{2}:[0-9]{2})$").find(time)
        val body = if (zone == null) time else time.substring(0, zone.range.first)
        val reading = clock(body, false) ?: return null
        return zoneWords(zone?.value)?.let { "$day at $reading$it" }
    }

    private fun zoneWords(zone: String?): String? =
        when (zone) {
            null -> ""
            "Z" -> " UTC"
            else -> offsetWords(zone)
        }

    private fun offsetWords(zone: String): String? {
        val hours = zone.substring(1, OFFSET_HOUR_END).toInt()
        val minutes = zone.substring(OFFSET_MINUTE_START).toInt()
        val beyondMaximum = hours > MAX_OFFSET_HOURS || hours == MAX_OFFSET_HOURS && minutes != 0
        return if (beyondMaximum || minutes > MAX_CLOCK_COMPONENT) {
            null
        } else {
            val sign = if (zone.startsWith('-')) "minus" else "plus"
            " $sign ${EnglishNumbers.cardinal(hours.toLong())} hours " +
                "${EnglishNumbers.cardinal(minutes.toLong())} minutes"
        }
    }

    // Numeric indices refer to the fixed capture groups in this grammar.
    @Suppress("MagicNumber")
    fun clock(
        token: String,
        duration: Boolean,
    ): String? {
        val match =
            Regex(
                "([0-9]{1,2}):([0-9]{2})(?::([0-9]{2})(?:\\.([0-9]+))?)?(?: ?([ap])\\.m\\.| ?([AP])M)?",
            ).matchEntire(token)
                ?: return null
        val hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].toInt()
        val seconds = match.groupValues[3].toIntOrNull()
        val fraction = match.groupValues[4]
        val meridiem = match.groupValues[5].ifEmpty { match.groupValues[6] }.lowercase()
        val invalidComponents = minute > MAX_CLOCK_COMPONENT || (seconds ?: 0) > MAX_CLOCK_COMPONENT
        return when {
            invalidComponents || fraction.length > MAX_FRACTION_DIGITS -> null
            duration -> durationWords(hour, minute, seconds)
            hour > MAX_CLOCK_HOUR || meridiem.isNotEmpty() && hour !in 1..HALF_DAY_HOURS -> null
            else -> clockWords(hour, minute, seconds, fraction, meridiem)
        }
    }

    private fun durationWords(
        hour: Int,
        minute: Int,
        seconds: Int?,
    ): String {
        val components =
            listOfNotNull(
                hour.takeIf { it != 0 }?.let { it to "hour" },
                minute.takeIf { it != 0 }?.let { it to "minute" },
                seconds?.takeIf { it != 0 }?.let { it to "second" },
            )
        return components
            .joinToString(" ") { (n, unit) ->
                "${EnglishNumbers.cardinal(n.toLong())} $unit${if (n == 1) "" else "s"}"
            }.ifEmpty { "zero seconds" }
    }

    private fun clockWords(
        hour: Int,
        minute: Int,
        seconds: Int?,
        fraction: String,
        meridiem: String,
    ): String {
        val special = if (minute == 0 && seconds == null) specialHour(hour, meridiem) else null
        val minuteWords = EnglishNumbers.cardinal(minute.toLong())
        return special ?: (
            EnglishNumbers.cardinal(hour.toLong()) + " " +
                (if (minute < TWO_DIGIT_MINUTE) "oh $minuteWords" else minuteWords) +
                secondsWords(seconds, fraction) +
                (if (meridiem.isEmpty()) "" else " ${meridiem.uppercase()} M")
        )
    }

    private fun specialHour(
        hour: Int,
        meridiem: String,
    ): String? =
        when {
            hour == 0 || hour == HALF_DAY_HOURS && meridiem == "a" -> "midnight"
            hour == HALF_DAY_HOURS && meridiem == "p" -> "noon"
            else -> null
        }

    private fun secondsWords(
        seconds: Int?,
        fraction: String,
    ): String =
        if (seconds == null) {
            ""
        } else {
            val fractional = if (fraction.isEmpty()) "" else " point ${EnglishNumbers.digits(fraction)}"
            " and ${EnglishNumbers.cardinal(seconds.toLong())}$fractional seconds"
        }

    // Numeric indices refer to the fixed capture groups in this grammar.
    @Suppress("MagicNumber")
    fun duration(token: String): String? {
        val match = Regex("PT(?:([0-9]{1,6})H)?(?:([0-9]{1,6})M)?(?:([0-9]{1,6})S)?").matchEntire(token) ?: return null
        return (1..3)
            .mapNotNull { index ->
                match.groupValues[index].toLongOrNull()?.let { n ->
                    "${EnglishNumbers.cardinal(
                        n,
                    )} ${listOf("hour", "minute", "second")[index - 1]}${if (n == 1L) "" else "s"}"
                }
            }.joinToString(" ")
            .takeIf { it.isNotEmpty() }
    }
}
