package dev.ipf.whitenoise.android.audio.tts.speech

/** Ordered whole-token recognition. Rejected structures never enter a later rule. */
object SpokenForms {
    private const val NUM = "[+−-]?[0-9]+(?:[.,][0-9]+)*"
    private const val MONTHS =
        "January|February|March|April|May|June|July|August|September|October|November|December|" +
            "Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec"
    private val units =
        linkedMapOf(
            "m/s²" to ("meter per second squared" to "meters per second squared"),
            "km/h" to ("kilometer per hour" to "kilometers per hour"),
            "MB/s" to ("megabyte per second" to "megabytes per second"),
            "Mb/s" to ("megabit per second" to "megabits per second"),
            "kg" to ("kilogram" to "kilograms"),
            "Mg" to ("megagram" to "megagrams"),
            "mg" to ("milligram" to "milligrams"),
            "µg" to ("microgram" to "micrograms"),
            "μg" to ("microgram" to "micrograms"),
            "mcg" to ("microgram" to "micrograms"),
            "g" to ("gram" to "grams"),
            "t" to ("tonne" to "tonnes"),
            "lbs" to ("pound" to "pounds"),
            "lb" to ("pound" to "pounds"),
            "oz" to ("ounce" to "ounces"),
            "st" to ("stone" to "stone"),
            "m²" to ("square meter" to "square meters"),
            "m³" to ("cubic meter" to "cubic meters"),
            "mL" to ("milliliter" to "milliliters"),
            "L" to ("liter" to "liters"),
            "km" to ("kilometer" to "kilometers"),
            "cm" to ("centimeter" to "centimeters"),
            "mm" to ("millimeter" to "millimeters"),
            "°C" to ("degree Celsius" to "degrees Celsius"),
            "°F" to ("degree Fahrenheit" to "degrees Fahrenheit"),
            "Hz" to ("hertz" to "hertz"),
            "kW" to ("kilowatt" to "kilowatts"),
            "W" to ("watt" to "watts"),
            "V" to ("volt" to "volts"),
            "mA" to ("milliampere" to "milliamperes"),
            "A" to ("ampere" to "amperes"),
            "kWh" to ("kilowatt hour" to "kilowatt hours"),
            "J" to ("joule" to "joules"),
            "h" to ("hour" to "hours"),
            "min" to ("minute" to "minutes"),
            "s" to ("second" to "seconds"),
            "sats" to ("satoshi" to "satoshis"),
            "sat" to ("satoshi" to "satoshis"),
            "BTC" to ("bitcoin" to "bitcoin"),
            "tons" to ("ton" to "tons"),
            "m" to ("meter" to "meters"),
            "in" to ("inch" to "inches"),
        )
    private val unitPattern = units.keys.sortedByDescending(String::length).joinToString("|", transform = Regex::escape)
    private val candidates =
        Regex(
            listOf(
                "https?://[^\\s]+",
                "v?[0-9]+(?:\\.[0-9]+){2,}",
                "[0-9]+(?:st|nd|rd|th)(?![\\p{L}])",
                "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:.]+(?:Z|[+-][0-9:]+)?",
                "(?:$MONTHS) [0-9]{1,2}(?:st|nd|rd|th)?(?:[–-][0-9]{1,2})?,? [0-9]{4}",
                "[0-9]{1,2} (?:$MONTHS) [0-9]{4}",
                "[0-9]{4}-W[0-9]+-[0-9]+",
                "[0-9]{4}-[0-9]{2}-[0-9]{2}",
                "[0-9]+[/.][0-9]+[/.][0-9]+",
                "[0-9]{4}-[0-9]{2,3}",
                "[0-9]{1,2}:[0-9]{1,2}(?::[0-9]{1,2}(?:\\.[0-9]+)?)?(?: ?[ap]\\.m\\.| ?[AP]M)?",
                "PT(?:[0-9]+H)?(?:[0-9]+M)?(?:[0-9]+S)?",
                "v[0-9]+(?:\\.[0-9]+)+",
                "[0-9]+(?:\\.[0-9]+){2,}",
                "[0-9]+(?:\\.[0-9]+)?e[+-]?[0-9]+",
                "[0-9]+ [0-9]+/[0-9]+",
                "[0-9]+/[0-9]+",
                "\\(?-?(?:[$£€¥]|[A-Z]{3} )$NUM(?:/mo)?\\)?",
                "$NUM (?:[A-Z]{3})(?![\\p{L}])",
                "(?:$NUM(?:[–-]$NUM)?½?) ?(?:$unitPattern)(?![\\p{L}\\p{N}])",
                "$NUM(?:st|nd|rd|th|%)?",
                "[\\p{L}_][\\p{L}\\p{N}_]*(?:['’][\\p{L}]+)*",
            ).joinToString("|"),
        )

    fun verbalize(
        source: String,
        leafId: String,
        context: SpeechContext,
    ): VerbalizedText {
        if (context.voiceLocale.language != "en") return literalText(source, leafId, SpeechLocaleSupport.Literal)
        val builder = NarrationBuilder()
        var cursor = 0
        for (match in candidates.findAll(source)) {
            if (match.range.first > cursor) {
                val gap = source.substring(cursor, match.range.first)
                if (gap.isBlank()) {
                    builder.add(
                        gap,
                    )
                } else {
                    builder.add(
                        gap,
                        listOf(SpeechSourceSpan(leafId, cursor, match.range.first)),
                        SpeechMappingKind.Identity,
                    )
                }
            }
            val token = match.value
            val preceding = source.substring(maxOf(0, match.range.first - 32), match.range.first)
            val replacement = if (token.count(Char::isDigit) > 48) null else recognize(token, context, preceding)
            builder.add(
                replacement ?: token,
                listOf(SpeechSourceSpan(leafId, match.range.first, match.range.last + 1)),
                if (replacement ==
                    null
                ) {
                    SpeechMappingKind.Identity
                } else {
                    SpeechMappingKind.Replacement
                },
            )
            cursor = match.range.last + 1
        }
        if (cursor <
            source.length
        ) {
            builder.add(
                source.substring(cursor),
                listOf(SpeechSourceSpan(leafId, cursor, source.length)),
                SpeechMappingKind.Identity,
            )
        }
        return builder.build()
    }

    private fun recognize(
        token: String,
        context: SpeechContext,
        preceding: String,
    ): String? =
        when {
            token.startsWith("http") -> null
            token.startsWith("PT") -> ClockSpeech.duration(token)
            Regex("v?[0-9]+(?:\\.[0-9]+){2,}").matches(token) -> version(token)
            else -> recognizeCalendarOrNumber(token, context, preceding)
        }

    private fun version(token: String): String =
        (if (token.startsWith('v')) "v " else "") +
            token.removePrefix("v").split('.').joinToString(" point ") { EnglishNumbers.digits(it) }

    private fun recognizeCalendarOrNumber(
        token: String,
        context: SpeechContext,
        preceding: String,
    ): String? {
        val calendar = CalendarSpeech.recognize(token, context)
        return when {
            calendar != null -> calendar
            Regex(
                "[0-9]{4}-.*|[0-9]+[/.][0-9]+[/.][0-9]+|(?:$MONTHS) .*|" +
                    "[0-9]+ (?:$MONTHS) .*|.*T[0-9].*",
            ).matches(token) -> null
            ':' in token -> clockOrRatio(token, context, preceding)
            Regex("[0-9.]+e[+-]?[0-9]+").matches(token) -> scientific(token, context)
            else -> recognizeQuantity(token, context, preceding)
        }
    }

    private fun clockOrRatio(
        token: String,
        context: SpeechContext,
        preceding: String,
    ): String? {
        val duration =
            context.semanticHint == SpeechSemanticHint.Duration ||
                Regex("(?i)duration: *$").containsMatchIn(preceding)
        return ClockSpeech.clock(token, duration) ?: if (Regex("(?i)ratio:? *$").containsMatchIn(preceding)) {
            token.split(':').takeIf { it.size == 2 }?.joinToString(" to ") { EnglishNumbers.cardinal(it.toLong()) }
        } else {
            null
        }
    }

    private fun scientific(
        token: String,
        context: SpeechContext,
    ): String? {
        val parts = token.split('e')
        val exponent = parts[1].toIntOrNull() ?: return null
        return if (context.semanticHint == SpeechSemanticHint.Number && exponent in 0..MAX_EXPONENT) {
            "${EnglishNumbers.number(parts[0], context)} times ten to the ${EnglishNumbers.ordinal(exponent)}"
        } else {
            null
        }
    }

    private fun recognizeQuantity(
        token: String,
        context: SpeechContext,
        preceding: String,
    ): String? {
        val money = MoneySpeech.recognize(token, context)
        val ordinal = Regex("([0-9]+)(st|nd|rd|th)").matchEntire(token)
        val measure = Regex("($NUM(?:[–-]$NUM)?½?) ?($unitPattern)").matchEntire(token)
        return when {
            money != null -> money
            isCurrency(token) -> null
            ordinal != null ->
                ordinal.groupValues[1]
                    .toIntOrNull()
                    ?.takeIf { it in 1..MAX_ORDINAL }
                    ?.let(EnglishNumbers::ordinal)
            measure != null -> measure(measure, context, preceding)
            '/' in token -> FractionSpeech.recognize(token, context)
            token.endsWith('%') -> EnglishNumbers.number(token.dropLast(1), context)?.let { "$it percent" }
            else -> EnglishNumbers.number(token, context)
        }
    }

    private fun isCurrency(token: String): Boolean =
        token.any { it in "$£€¥" } ||
            Regex("[A-Z]{3} .*|.* [A-Z]{3}").matches(token) &&
            !token.endsWith(" BTC")

    private fun measure(
        match: MatchResult,
        context: SpeechContext,
        preceding: String,
    ): String? {
        val value = match.groupValues[1]
        val unit = match.groupValues[2]
        if (unit in setOf("m", "in") && !Regex("(?i)length: *$").containsMatchIn(preceding)) return null
        return measureValue(value, context)?.let { spoken ->
            val names = units.getValue(unit)
            val amount = if (value.startsWith('−')) spoken.replaceFirst("negative", "minus") else spoken
            "$amount ${if (EnglishNumbers.canonical(value, context) == "1") names.first else names.second}"
        }
    }

    private fun measureValue(
        value: String,
        context: SpeechContext,
    ): String? =
        when {
            value.endsWith('½') -> EnglishNumbers.number(value.dropLast(1), context)?.let { "$it and a half" }
            '–' in value -> {
                val readings = value.split('–').map { EnglishNumbers.number(it, context) }
                if (readings.any { it == null }) null else readings.joinToString(" to ")
            }
            else -> EnglishNumbers.number(value, context, allowAmbiguous = true)
        }

    private const val MAX_EXPONENT = 100
    private const val MAX_ORDINAL = 9999
}
