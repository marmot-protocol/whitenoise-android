package dev.ipf.whitenoise.android.audio.tts.speech

internal object EnglishNumbers {
    private val small =
        listOf(
            "zero",
            "one",
            "two",
            "three",
            "four",
            "five",
            "six",
            "seven",
            "eight",
            "nine",
            "ten",
            "eleven",
            "twelve",
            "thirteen",
            "fourteen",
            "fifteen",
            "sixteen",
            "seventeen",
            "eighteen",
            "nineteen",
        )
    private val tens = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

    // Decimal place values are the grammar of English cardinal numbers.
    @Suppress("MagicNumber")
    fun cardinal(n: Long): String =
        when {
            n < 0 -> "negative ${cardinal(-n)}"
            n < 20 -> small[n.toInt()]
            n < 100 -> tens[(n / 10).toInt()] + if (n % 10 == 0L) "" else "-${cardinal(n % 10)}"
            n < 1000 -> "${cardinal(n / 100)} hundred" + if (n % 100 == 0L) "" else " ${cardinal(n % 100)}"
            else -> {
                val (scale, name) =
                    listOf(
                        1_000_000_000_000L to "trillion",
                        1_000_000_000L to "billion",
                        1_000_000L to "million",
                        1000L to "thousand",
                    ).first {
                        n >=
                            it.first
                    }
                "${cardinal(n / scale)} $name" + if (n % scale == 0L) "" else " ${cardinal(n % scale)}"
            }
        }

    fun digits(value: String): String =
        value
            .map {
                if (it.isDigit()) {
                    small[it.digitToInt()]
                } else {
                    when (it) {
                        '.' -> "point"
                        ',' -> "comma"
                        '/' -> "slash"
                        '-' -> "dash"
                        else -> it.toString()
                    }
                }
            }.joinToString(" ")

    fun ordinal(n: Int): String {
        val word = cardinal(n.toLong())
        val suffix =
            mapOf(
                "one" to "first",
                "two" to "second",
                "three" to "third",
                "five" to "fifth",
                "eight" to "eighth",
                "nine" to "ninth",
                "twelve" to "twelfth",
            )
        val last = word.substringAfterLast(' ').substringAfterLast('-')
        return word.dropLast(last.length) +
            (suffix[last] ?: if (last.endsWith("y")) last.dropLast(1) + "ieth" else last + "th")
    }

    // These historical year ranges and century divisions define the reading convention.
    @Suppress("MagicNumber")
    fun year(n: Int): String =
        if (n in 2010..2099 ||
            n in 1100..1999 &&
            n % 100 != 0
        ) {
            "${cardinal((n / 100).toLong())} ${cardinal((n % 100).toLong())}"
        } else {
            cardinal(n.toLong())
        }

    /** Canonical separators, without deriving source format from the voice. */
    fun canonical(
        raw: String,
        context: SpeechContext,
    ): String? {
        if (raw.count(Char::isDigit) > MAX_CANONICAL_DIGITS) return null
        val token = raw.removePrefix("+").removePrefix("-").removePrefix("−")
        val normalized =
            when {
                !Regex("[0-9]+(?:[.,][0-9]+)*").matches(token) -> null
                context.sourceFormatLocale != null ->
                    canonicalWithLocale(token, context.sourceFormatLocale.language)
                '.' in token && ',' in token -> canonicalMixedSeparators(token)
                else -> canonicalSingleSeparator(token)
            }
        return normalized?.let { (if (raw.startsWith('-') || raw.startsWith('−')) "-" else "") + it }
    }

    private fun groupedInteger(
        token: String,
        separator: Char,
    ): Boolean = Regex("[1-9][0-9]{0,2}(?:${Regex.escape(separator.toString())}[0-9]{3})+").matches(token)

    private fun canonicalWithLocale(
        token: String,
        language: String,
    ): String? {
        val decimal = if (language in setOf("de", "fr")) ',' else '.'
        val group = if (decimal == '.') ',' else '.'
        val parts = token.split(decimal)
        val invalidInteger = group in parts[0] && !groupedInteger(parts[0], group)
        val invalidFraction = parts.size == 2 && group in parts[1]
        return if (parts.size > 2 || invalidInteger || invalidFraction) {
            null
        } else {
            parts[0].replace(group.toString(), "") + if (parts.size == 2) ".${parts[1]}" else ""
        }
    }

    private fun canonicalMixedSeparators(token: String): String? {
        val decimal = if (token.lastIndexOf('.') > token.lastIndexOf(',')) '.' else ','
        val group = if (decimal == '.') ',' else '.'
        val parts = token.split(decimal)
        return if (parts.size != 2 || !groupedInteger(parts[0], group)) {
            null
        } else {
            parts[0].replace(group.toString(), "") + "." + parts[1]
        }
    }

    private fun canonicalSingleSeparator(token: String): String? {
        val separator = if (',' in token) ',' else '.'
        val parts = token.split(separator)
        return when {
            parts.size > 2 -> token.takeIf { groupedInteger(it, separator) }?.replace(separator.toString(), "")
            parts.size == 2 ->
                token.takeUnless {
                    separator == ',' || parts[1].length == GROUP_DIGITS && parts[0].toLongOrNull() != 0L
                }
            else -> token
        }
    }

    private const val MAX_CANONICAL_DIGITS = 15
    private const val GROUP_DIGITS = 3

    fun number(
        raw: String,
        context: SpeechContext,
        allowAmbiguous: Boolean = false,
    ): String? {
        val canonical =
            canonical(raw, context)
                ?: return if (allowAmbiguous && raw.length <= 24) digits(raw).replace("point", "dot") else null
        val negative = canonical.startsWith('-')
        val value = canonical.removePrefix("-")
        val parts = value.split('.')
        val integer = parts[0]
        val spoken = if (integer.length > 1 && integer.startsWith('0')) digits(integer) else cardinal(integer.toLong())
        return (if (negative) "negative " else "") + spoken + if (parts.size == 2) " point ${digits(parts[1])}" else ""
    }
}
