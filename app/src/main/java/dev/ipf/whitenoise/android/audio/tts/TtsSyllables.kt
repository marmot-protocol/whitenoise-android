package dev.ipf.whitenoise.android.audio.tts

/**
 * Approximate spoken syllable count for one token.
 *
 * The estimated word-timing schedule needs to know how LONG a word takes to
 * say, and syllables are what engines actually pace by: speech runs at roughly
 * constant syllables per second, while characters per second swings wildly —
 * "strengths" is nine characters of one syllable, "idea" four characters of
 * three. Weighting words by characters makes every syllable-dense word donate
 * time to the rest of the sentence and every cluster-heavy word steal it, and
 * those errors compound toward the end of long sentences.
 *
 * The count is heuristic and English-leaning: vowel groups, with the three
 * silent-ending repairs that matter at this granularity (silent -e, silent
 * -ed, plural -es). Words it gets wrong are off by one syllable; the estimate
 * re-anchors at every utterance start, which bounds the error to one chunk.
 */
internal object TtsSyllables {
    /** Syllables in [token]'s letters; 0 when it has none, otherwise at least 1. */
    @Suppress("CyclomaticComplexMethod")
    fun inWord(token: String): Int {
        var groups = 0
        var letters = 0
        var previousVowel = false
        for (index in token.indices) {
            val character = token[index]
            if (!character.isLetter()) {
                previousVowel = false
                continue
            }
            letters++
            val lower = character.lowercaseChar()
            // y is a vowel at a syllable's end ("sky", "rhythm") but a consonant
            // when it OPENS one ("beyond", "yes"): before a plain vowel it starts
            // a new group rather than merging into the previous.
            val vowel =
                lower in PLAIN_VOWELS ||
                    (lower == 'y' && token.getOrNull(index + 1)?.lowercaseChar() !in PLAIN_VOWEL_SET)
            if (vowel && !previousVowel) groups++
            previousVowel = vowel
        }
        if (letters == 0) return 0

        var count = groups
        val bare = token.trimEnd { !it.isLetter() }.lowercase()
        val pluralBare = bare.removeSuffix("s")
        when {
            // Silent -ed: "walked", "played" — but "wanted"/"needed" keep the
            // syllable (a t or d before the ending voices it), and a true vowel
            // before the e means the group was already merged ("carried"). y is
            // deliberately not in that set: "played" and "dyed" are one syllable.
            count > 1 &&
                bare.length > SILENT_ED_MIN_LENGTH &&
                bare.endsWith("ed") &&
                bare[bare.length - SILENT_ED_PRECEDING_OFFSET].let { it !in "td" && it !in "aeiou" } -> count--
            // Silent -e, with or without a plural s: "make", "makes". The -le
            // ending keeps its syllable ("table"), and a stripped plural after
            // s/x/z is voiced ("boxes", "houses") while the bare word's own -se
            // is not ("house").
            count > 1 &&
                pluralBare.length >= 2 &&
                pluralBare.endsWith("e") &&
                pluralBare[pluralBare.length - 2].let { previous ->
                    previous !in VOWELS &&
                        previous != 'l' &&
                        (pluralBare == bare || previous !in "sxz")
                } -> count--
        }
        return count.coerceAtLeast(1)
    }

    private const val SILENT_ED_MIN_LENGTH = 3

    /** The character inspected before a silent "-ed" ending sits two behind the final d. */
    private const val SILENT_ED_PRECEDING_OFFSET = 3
    private const val PLAIN_VOWELS = "aeiouàáâäãåèéêëìíîïòóôöõùúûüœæ"
    private val PLAIN_VOWEL_SET = PLAIN_VOWELS.toSet()

    /** y included: it carries the group in "sky" and "rhythm". */
    private const val VOWELS = PLAIN_VOWELS + "y"
}
