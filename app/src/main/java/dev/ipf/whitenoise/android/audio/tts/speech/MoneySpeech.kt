package dev.ipf.whitenoise.android.audio.tts.speech

internal object MoneySpeech {
    private const val NUM = "[+−-]?[0-9]+(?:[.,][0-9]+)*"
    private const val MAX_LITERAL_LENGTH = 24

    private data class Currency(
        val major: String,
        val majors: String,
        val minor: String,
        val minors: String,
        val scale: Int = 2,
    )

    private val currencies =
        mapOf(
            "$" to Currency("dollar", "dollars", "cent", "cents"),
            "USD" to Currency("US dollar", "US dollars", "cent", "cents"),
            "£" to Currency("pound", "pounds", "penny", "pence"),
            "GBP" to Currency("pound", "pounds", "penny", "pence"),
            "€" to Currency("euro", "euros", "cent", "cents"),
            "EUR" to Currency("euro", "euros", "cent", "cents"),
            "¥" to Currency("yen", "yen", "", "", 0),
            "JPY" to Currency("yen", "yen", "", "", 0),
            "KWD" to Currency("Kuwaiti dinar", "Kuwaiti dinars", "fils", "fils", DINAR_SCALE),
        )
    private const val DINAR_SCALE = 3

    // Numeric indices select the fixed amount, sign and currency capture groups.
    @Suppress("MagicNumber")
    fun recognize(
        token: String,
        context: SpeechContext,
    ): String? {
        val match =
            Regex(
                "(\\()?([-])?(?:([$£€¥]|[A-Z]{3}) ?($NUM)|($NUM) ([A-Z]{3}))(/mo)?(\\))?",
            ).matchEntire(token) ?: return null
        val currency = match.groupValues[3].ifEmpty { match.groupValues[6] }
        val raw = match.groupValues[4].ifEmpty { match.groupValues[5] }
        val canonical = EnglishNumbers.canonical(raw, context)
        return when {
            canonical == null -> literalAmount(raw, currency)
            currency !in currencies ->
                EnglishNumbers.number(raw, context)?.let {
                    "$it ${if (currency == "BTC") "bitcoin" else currency.toList().joinToString(" ")}"
                }
            else -> {
                val negative =
                    match.groupValues[2].isNotEmpty() ||
                        canonical.startsWith('-') ||
                        match.groupValues[1].isNotEmpty() &&
                        context.semanticHint == SpeechSemanticHint.AccountingAmount
                val body = amountWords(raw, canonical, currencies.getValue(currency), context)
                (if (negative) "negative " else "") + body +
                    if (match.groupValues[7].isNotEmpty()) " per month" else ""
            }
        }
    }

    private fun literalAmount(
        raw: String,
        currency: String,
    ): String? =
        if (raw.length <= MAX_LITERAL_LENGTH) {
            val name =
                when (currency) {
                    "$", "USD" -> "dollars"
                    "£", "GBP" -> "pounds"
                    "EUR", "€" -> "euros"
                    "JPY", "¥" -> "yen"
                    else -> currency
                }
            EnglishNumbers.digits(raw) + " " + name
        } else {
            null
        }

    private fun amountWords(
        raw: String,
        canonical: String,
        names: Currency,
        context: SpeechContext,
    ): String {
        val parts = canonical.removePrefix("-").split('.')
        return if (parts.size == 2 && parts[1].length > names.scale) {
            "${EnglishNumbers.number(raw.removePrefix("-"), context)} ${names.majors}"
        } else {
            val integer = parts[0].toLong()
            val minor = if (parts.size == 2 && names.scale > 0) parts[1].padEnd(names.scale, '0').toInt() else 0
            val majorWords = "${EnglishNumbers.cardinal(integer)} ${if (integer == 1L) names.major else names.majors}"
            majorWords +
                if (minor == 0) {
                    ""
                } else {
                    " and ${EnglishNumbers.cardinal(minor.toLong())} ${if (minor == 1) names.minor else names.minors}"
                }
        }
    }
}
