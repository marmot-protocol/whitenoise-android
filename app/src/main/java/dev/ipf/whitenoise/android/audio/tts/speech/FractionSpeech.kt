package dev.ipf.whitenoise.android.audio.tts.speech

internal object FractionSpeech {
    private const val MAX_COMPONENT = 99

    fun recognize(
        token: String,
        context: SpeechContext,
    ): String? {
        if (context.semanticHint != SpeechSemanticHint.Fraction) return null
        val parts = token.split(' ')
        val fraction = parts.last().split('/')
        val numerator = fraction[0].toIntOrNull()
        val denominator = fraction[1].toIntOrNull()
        return if (numerator in 1..MAX_COMPONENT && denominator in 2..MAX_COMPONENT) {
            val name = fractionName(requireNotNull(numerator), requireNotNull(denominator))
            if (parts.size == 2) "${EnglishNumbers.number(parts[0], context)} and $name" else name
        } else {
            null
        }
    }

    private fun fractionName(
        numerator: Int,
        denominator: Int,
    ): String {
        val count = EnglishNumbers.cardinal(numerator.toLong())
        return when (denominator) {
            2 -> if (numerator == 1) "a half" else "$count halves"
            QUARTER_DENOMINATOR -> "$count ${if (numerator == 1) "quarter" else "quarters"}"
            else -> "$count ${EnglishNumbers.ordinal(denominator)}${if (numerator == 1) "" else "s"}"
        }
    }

    private const val QUARTER_DENOMINATOR = 4
}
