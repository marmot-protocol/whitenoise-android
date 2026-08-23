package dev.ipf.whitenoise.android.audio.tts

import java.text.BreakIterator
import java.util.Locale

/** A word's span within the engine payload and when it is expected to start. */
internal data class TtsEstimatedWord(
    val start: Int,
    val end: Int,
    val startMs: Long,
)

/** A whitespace token's span, its spoken cost, and the pause the engine takes after it. */
internal data class TtsWeightedToken(
    val start: Int,
    val end: Int,
    val speechWeight: Int,
    val pauseWeight: Int,
)

/**
 * Approximate word timing for engines that never report `onRangeStart`.
 *
 * All weights are in TENTHS OF A SYLLABLE. Engines pace speech by syllables,
 * not by characters: weighting by characters makes every syllable-dense word
 * donate time to the rest of the utterance and every consonant cluster steal
 * it, and those errors compound into a highlight that falls further behind
 * toward the end of long sentences. Everything an engine expands beyond its
 * spelling is costed at its SPOKEN syllables: an acronym's letter names, a
 * dotted abbreviation's expansion, digits read as number names, symbols read
 * by name.
 *
 * The unit is shared, deliberately, by the three parties that must agree or
 * the schedule skews: [plan] (per-word cost), [weightedLengthOf] (whole
 * payload cost) and [TtsPaceCalibrator] (which learns ms per THIS unit from
 * measured utterance durations). A rate learned per one unit and spent per
 * another runs systematically slow on every digit- or acronym-carrying
 * sentence.
 *
 * Highlight spans are aligned to [BreakIterator] word boundaries so they pass
 * the same complete-word validation engine-reported ranges do; a token whose
 * letters split into several iterator words shares its duration among them by
 * syllable count.
 */
internal object TtsWordTimingEstimate {
    private val TOKEN = Regex("\\S+")

    /** One syllable in tenths; the resolution every weight below is expressed in. */
    private const val SYLLABLE = 10

    /**
     * Starting guess for one speech unit at 1x. [TtsPaceCalibrator] replaces it
     * with the installed voice's measured pace.
     */
    const val DEFAULT_MS_PER_UNIT_AT_1X = 17.5

    /**
     * Word schedule for [text] as submitted to the engine. [msPerUnitAt1x]
     * comes from the calibrator, which measures the installed voice; the
     * default is only the starting guess.
     */
    fun plan(
        text: String,
        locale: Locale,
        rate: Float,
        msPerUnitAt1x: Double = DEFAULT_MS_PER_UNIT_AT_1X,
    ): List<TtsEstimatedWord> {
        val tokens = weightedTokens(text)
        if (tokens.isEmpty()) return emptyList()
        val iteratorWords = iteratorWords(text, locale)
        val msPerUnit = msPerUnitAt1x / clampRate(rate)
        val words = ArrayList<TtsEstimatedWord>()
        var elapsed = 0.0
        var nextIteratorWord = 0
        for (token in tokens) {
            val tokenMs = (token.speechWeight + token.pauseWeight) * msPerUnit
            while (nextIteratorWord < iteratorWords.size && iteratorWords[nextIteratorWord].last < token.start) {
                nextIteratorWord++
            }
            val pieces = ArrayList<IntRange>()
            var probe = nextIteratorWord
            while (probe < iteratorWords.size && iteratorWords[probe].first < token.end) {
                pieces += iteratorWords[probe]
                probe++
            }
            when {
                pieces.isEmpty() -> Unit // Symbol-only token: the previous word stays lit.
                pieces.size == 1 ->
                    words += TtsEstimatedWord(pieces[0].first, pieces[0].last + 1, elapsed.toLong())

                else -> {
                    // "well-known" splits at the hyphen: share the token's time
                    // among its iterator words by their own syllable counts.
                    val syllables = pieces.map { TtsSyllables.inWord(text.substring(it)).coerceAtLeast(1) }
                    val total = syllables.sum().toDouble()
                    var pieceElapsed = elapsed
                    pieces.forEachIndexed { index, piece ->
                        words += TtsEstimatedWord(piece.first, piece.last + 1, pieceElapsed.toLong())
                        pieceElapsed += tokenMs * (syllables[index] / total)
                    }
                }
            }
            elapsed += tokenMs
        }
        return words
    }

    /**
     * Every whitespace token of [text] with what it costs to speak. The pause
     * an engine takes after clause punctuation is kept separate from the
     * speech cost so a future measured-audio schedule can take its pauses from
     * measured silence instead.
     */
    internal fun weightedTokens(text: String): List<TtsWeightedToken> {
        if (text.isBlank()) return emptyList()
        val shouted = isShoutedText(text)
        return TOKEN
            .findAll(text)
            .map { match ->
                val token = text.substring(match.range.first, match.range.last + 1)
                TtsWeightedToken(
                    start = match.range.first,
                    end = match.range.last + 1,
                    speechWeight = spokenWeightOf(token, shouted) + WORD_INTERCEPT,
                    pauseWeight = pauseWeightOf(token),
                )
            }.toList()
    }

    /**
     * The payload's spoken length in weight units — what the pace calibrator
     * must be fed. This is the single definition of the unit's total.
     */
    fun weightedLengthOf(text: String): Int = weightedTokens(text).sumOf { it.speechWeight + it.pauseWeight }

    /**
     * The breath an engine takes after clause punctuation. Terminal sentence
     * punctuation is deliberately cheap: the queue splits utterances at
     * sentence boundaries, so most of that pause falls between utterances.
     */
    internal fun pauseWeightOf(token: String): Int {
        // A closing quote or bracket may trail the punctuation carrying the pause.
        val bare = token.trimEnd { it in TRAILING_WRAPPERS }
        return when (bare.lastOrNull()) {
            ',' -> COMMA_PAUSE
            ';', ':', '—', '–' -> CLAUSE_PAUSE
            else -> 0
        }
    }

    /**
     * Roughly how many tenths of a syllable [token] costs to say.
     *
     * Written and spoken length diverge wherever the engine expands what is on
     * the page: an acronym's letters are spoken as names ("tee ar en gee"), a
     * dotted abbreviation becomes its full word ("Dr." is "doctor"), and
     * digits are read as number names ("1984" is "nineteen eighty-four").
     */
    internal fun spokenWeightOf(
        token: String,
        inShoutedText: Boolean = false,
    ): Int {
        // Trailing quote/bracket/comma punctuation is silent and must not defeat
        // the dictionary lookups below; a terminal dot stays because it IS the
        // abbreviation.
        val bare = token.trimEnd { it in TRAILING_SILENT }
        ABBREVIATION_SYLLABLES[bare.lowercase(Locale.ROOT)]?.let { return it * SYLLABLE }
        val digits = token.count(Char::isDigit) * DIGIT_WEIGHT
        val symbols = token.sumOf { SYMBOL_WEIGHTS[it] ?: 0 }
        val spelledOut =
            isDottedInitialism(bare) ||
                (!inShoutedText && isSpelledOutAcronym(token))
        val letters =
            if (spelledOut) {
                // Letter names are one syllable each, except w ("double-u").
                token.sumOf { character ->
                    when {
                        !character.isLetter() -> 0
                        character == 'w' || character == 'W' -> W_LETTER_NAME_SYLLABLES * SYLLABLE
                        else -> SYLLABLE
                    }
                }
            } else {
                TtsSyllables.inWord(token) * SYLLABLE
            }
        return digits + symbols + letters
    }

    /** A dotted initialism ("U.S.", "a.m.") is spelled out letter by letter regardless of case. */
    @Suppress("ReturnCount")
    internal fun isDottedInitialism(token: String): Boolean {
        if (token.length < DOTTED_INITIALISM_MIN_LENGTH || token.count { it == '.' } < 2) return false
        var expectLetter = true
        for (character in token) {
            if (expectLetter && !character.isLetter()) return false
            if (!expectLetter && character != '.') return false
            expectLetter = !expectLetter
        }
        // A dot was consumed last, so a letter is what would come next.
        return expectLetter
    }

    /**
     * An all-caps run of letters is read letter by letter — unless the whole
     * text is shouted, in which case it is ordinary prose that happens to be
     * capitalized. The length bound keeps a shouted heading from being charged
     * a letter name per character.
     */
    internal fun isSpelledOutAcronym(token: String): Boolean {
        val letters = token.filter(Char::isLetter)
        return letters.length in 2..ACRONYM_MAX_LETTERS &&
            letters.all(Char::isUpperCase) &&
            token.none(Char::isDigit)
    }

    /** Mostly-uppercase text is shouted prose, not a string of acronyms. */
    private fun isShoutedText(text: String): Boolean {
        val letters = text.count(Char::isLetter)
        if (letters < SHOUTED_MIN_LETTERS) return false
        return text.count(Char::isUpperCase) * SHOUTED_RATIO_DENOMINATOR >= letters * SHOUTED_RATIO_NUMERATOR
    }

    /** All BreakIterator word spans of [text] that contain letters or digits, in order. */
    private fun iteratorWords(
        text: String,
        locale: Locale,
    ): List<IntRange> {
        val iterator = BreakIterator.getWordInstance(locale).apply { setText(text) }
        val words = ArrayList<IntRange>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            if (text.hasLetterOrDigit(start, end)) {
                words += start until end
            }
            start = end
            end = iterator.next()
        }
        return words
    }

    private fun String.hasLetterOrDigit(
        start: Int,
        end: Int,
    ): Boolean {
        var offset = start
        while (offset < end) {
            val codePoint = codePointAt(offset)
            if (Character.isLetterOrDigit(codePoint)) return true
            offset += Character.charCount(codePoint)
        }
        return false
    }

    internal fun clampRate(rate: Float): Float = rate.coerceIn(MIN_RATE, MAX_RATE)

    private const val MIN_RATE = 0.25f
    private const val MAX_RATE = 4.0f

    /**
     * The fixed per-word cost on top of its syllables: articulation onsets and
     * the tiny inter-word gaps engines leave. Real word duration is closer to
     * a + b*syllables than to b*syllables alone; without the intercept,
     * one-syllable function words finish before their scheduled slot and the
     * highlight jitters.
     */
    private const val WORD_INTERCEPT = 3

    /** A comma's breath, roughly a fifth of a second at the default rate. */
    private const val COMMA_PAUSE = 12

    /** Semicolons, colons and dashes pause longer than a comma. */
    private const val CLAUSE_PAUSE = 20

    /** Closing punctuation that may trail the character that actually carries a pause. */
    private const val TRAILING_WRAPPERS = ")]}\"'”’"

    /** A digit is read as part of a number name; empirically just under two syllables each. */
    private const val DIGIT_WEIGHT = 18

    /** Punctuation with no spoken form that may trail a token inside a sentence. */
    private const val TRAILING_SILENT = ",;:)]}\"'”’"

    private const val W_LETTER_NAME_SYLLABLES = 3
    private const val DOTTED_INITIALISM_MIN_LENGTH = 4
    private const val ACRONYM_MAX_LETTERS = 6
    private const val SHOUTED_MIN_LETTERS = 12
    private const val SHOUTED_RATIO_NUMERATOR = 7
    private const val SHOUTED_RATIO_DENOMINATOR = 10

    /**
     * Spoken syllable counts of the expansions engines substitute for
     * abbreviations. "no." and "st." are deliberately absent: they end
     * ordinary sentences and follow ordinary names often enough that expanding
     * them mis-times more prose than it fixes.
     */
    private val ABBREVIATION_SYLLABLES =
        mapOf(
            "mr." to 2,
            "mrs." to 2,
            "ms." to 1,
            "dr." to 2,
            "prof." to 3,
            "sr." to 2,
            "jr." to 2,
            "vs." to 2,
            "etc." to 4,
            "e.g." to 4,
            "i.e." to 2,
        )

    /** Symbols engines read out by name, in tenths of a syllable. */
    private val SYMBOL_WEIGHTS =
        mapOf(
            '%' to 2 * SYLLABLE,
            '$' to 2 * SYLLABLE,
            '€' to 2 * SYLLABLE,
            '£' to 1 * SYLLABLE,
            '&' to 1 * SYLLABLE,
            '@' to 1 * SYLLABLE,
            '+' to 1 * SYLLABLE,
            '=' to 2 * SYLLABLE,
        )
}
