package dev.ipf.whitenoise.android.translation

import android.content.res.Resources
import dev.ipf.whitenoise.android.R
import java.lang.Character.UnicodeScript
import java.text.Normalizer
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlin.math.abs

/** Local language identification for already-authorized incoming translations. */
internal class IncomingLanguageDetector(
    resources: Resources,
) {
    private val profiles by lazy {
        GZIPInputStream(resources.openRawResource(R.raw.translation_language_profiles)).bufferedReader().useLines {
            it.map(::readProfile).groupBy(Profile::script)
        }
    }

    /** Return a suggestion only for a sufficiently long and unambiguous sample. */
    fun detect(text: String): String? {
        val withoutLinks = IncomingLinks.replace(text.take(MAX_SAMPLE_LENGTH), " ")
        val normalized = Normalizer.normalize(withoutLinks, Normalizer.Form.NFC).lowercase(Locale.ROOT)
        val letters = normalized.codePoints().filter(Character::isLetter).toArray()
        if (letters.size < MIN_LETTERS || letters.toSet().size < MIN_DISTINCT_LETTERS) return null
        val scripts = letters.map(UnicodeScript::of).filterNot { it == UnicodeScript.COMMON }.toSet()
        val script = scripts.singleOrNull()
        return when {
            scripts.any { it in KanaScripts } && scripts.all { it in JapaneseScripts } -> "ja"
            script != null -> ScriptLanguages[script] ?: identifyByTrigrams(script, normalized)
            else -> null
        }
    }

    /** Compare ranked trigram matches within one writing system and reject a close runner-up. */
    private fun identifyByTrigrams(
        script: UnicodeScript,
        text: String,
    ): String? {
        val cleaned = " ${NonLetters.replace(text, " ").trim()} "
        val sample =
            cleaned
                .windowed(size = 3)
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .take(PROFILE_LENGTH)
        val scores =
            profiles[script]
                .orEmpty()
                .map { profile ->
                    val distance =
                        sample.withIndex().sumOf { (rank, trigram) ->
                            profile.ranks[trigram.key]?.let { abs(it - rank) } ?: PROFILE_LENGTH
                        }
                    profile.code to (1.0 - distance.toDouble() / (sample.size * PROFILE_LENGTH))
                }.sortedByDescending { it.second }
        if (scores.size < 2) return null
        val (code, best) = scores[0]
        val runnerUp = scores[1].second
        // Sparse or close matches are too weak to use as a composer default.
        val minimumGap = runnerUp * (3.0 / sample.size + GAP_FLOOR)
        return code.takeIf { best >= MIN_SIMILARITY && best - runnerUp > minimumGap }
    }

    private data class Profile(
        val script: UnicodeScript,
        val code: String,
        val ranks: Map<String, Int>,
    )

    /** Read a bundled profile and map its language code to the translation catalog. */
    private fun readProfile(line: String): Profile {
        val (script, code, trigrams) = line.split('\t', limit = 3)
        val ranks =
            trigrams.split('|').withIndex().associate { (rank, trigram) -> trigram to rank }
        return Profile(UnicodeScript.valueOf(script), if (code == "pes") "fa" else code, ranks)
    }

    companion object {
        private var sharedDetector: IncomingLanguageDetector? = null

        /** Share the immutable profiles across conversations, with application resources. */
        @Synchronized
        fun shared(resources: Resources): IncomingLanguageDetector {
            val existing = sharedDetector
            if (existing != null) return existing
            return IncomingLanguageDetector(resources).also { sharedDetector = it }
        }

        private const val MAX_SAMPLE_LENGTH = 512
        private const val MIN_LETTERS = 14
        private const val MIN_DISTINCT_LETTERS = 8
        private const val PROFILE_LENGTH = 300
        private const val MIN_SIMILARITY = 0.24
        private const val GAP_FLOOR = 0.015
        private val IncomingLinks =
            Regex(
                """(?i)\b(?:(?:https?://|www\.|nostr:)\S+""" +
                    """|[\p{L}\p{N}.-]+\.[a-z]{2,24}(?:/\S*)?)""",
            )
        private val NonLetters = Regex("[^\\p{L}\\p{M}]+")
        private val KanaScripts = setOf(UnicodeScript.HIRAGANA, UnicodeScript.KATAKANA)
        private val JapaneseScripts = KanaScripts + UnicodeScript.HAN
        private val ScriptLanguages =
            mapOf(
                UnicodeScript.GEORGIAN to "ka",
                UnicodeScript.GREEK to "el",
                UnicodeScript.GUJARATI to "gu",
                UnicodeScript.HANGUL to "ko",
                UnicodeScript.KANNADA to "kn",
                UnicodeScript.MALAYALAM to "ml",
                UnicodeScript.TAMIL to "ta",
                UnicodeScript.TELUGU to "te",
                UnicodeScript.THAI to "th",
            )
    }
}
