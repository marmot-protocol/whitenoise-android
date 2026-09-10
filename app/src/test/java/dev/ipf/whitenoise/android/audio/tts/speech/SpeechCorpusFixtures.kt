package dev.ipf.whitenoise.android.audio.tts.speech

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * One checked-in prose verbalization expectation.
 *
 * Expectations are authored by hand in
 * `app/src/test/resources/tts/speech-verbalization-corpus.json`. Nothing here
 * calls the production verbalizer, so a wrong reading can never become its own
 * expected value.
 */
internal data class SpeechCorpusCase(
    val id: String,
    val family: String,
    val input: String,
    val voiceLocale: Locale,
    val sourceFormatLocale: Locale?,
    val semanticHint: SpeechSemanticHint,
    val expectedSpokenCore: String?,
    val expectedContains: List<String>,
    val expectedContainsAny: List<List<String>>,
    val expectedAbsent: List<String>,
    val expectedLiteral: Boolean,
) {
    val context: SpeechContext
        get() =
            SpeechContext(
                voiceLocale = voiceLocale,
                sourceFormatLocale = sourceFormatLocale,
                semanticHint = semanticHint,
            )
}

internal object SpeechCorpusFixtures {
    private const val RESOURCE = "/tts/speech-verbalization-corpus.json"

    val cases: List<SpeechCorpusCase> by lazy { load() }

    fun case(id: String): SpeechCorpusCase =
        cases.singleOrNull { it.id == id }
            ?: error("no verbalization corpus case with id '$id'")

    fun families(vararg families: String): List<SpeechCorpusCase> =
        cases.filter { it.family in families }.also {
            check(it.isNotEmpty()) { "no verbalization corpus cases in ${families.toList()}" }
        }

    private fun load(): List<SpeechCorpusCase> {
        val json =
            checkNotNull(SpeechCorpusFixtures::class.java.getResourceAsStream(RESOURCE)) {
                "missing test resource $RESOURCE"
            }.use { it.readBytes().decodeToString() }
        val cases = JSONObject(json).getJSONArray("cases")
        val parsed = (0 until cases.length()).map { index -> cases.getJSONObject(index).toCase() }
        check(parsed.map(SpeechCorpusCase::id).distinct().size == parsed.size) {
            "verbalization corpus ids must be unique"
        }
        return parsed
    }

    private fun JSONObject.toCase(): SpeechCorpusCase {
        val case =
            SpeechCorpusCase(
                id = getString("id"),
                family = getString("family"),
                input = getString("input"),
                voiceLocale = Locale.forLanguageTag(getString("voice_locale")),
                sourceFormatLocale = optLanguageTag("source_format_locale"),
                semanticHint = optHint("semantic_hint"),
                expectedSpokenCore = if (has("expected_spoken_core")) getString("expected_spoken_core") else null,
                expectedContains = optStrings("expected_contains"),
                expectedContainsAny = optStringLists("expected_contains_any"),
                expectedAbsent = optStrings("expected_absent"),
                expectedLiteral = optBoolean("expected_literal", false),
            )
        check(case.hasAnyExpectation()) { "corpus case '${case.id}' states no expectation" }
        return case
    }

    private fun SpeechCorpusCase.hasAnyExpectation(): Boolean =
        expectedSpokenCore != null ||
            expectedLiteral ||
            expectedContains.isNotEmpty() ||
            expectedContainsAny.isNotEmpty() ||
            expectedAbsent.isNotEmpty()

    private fun JSONObject.optLanguageTag(name: String): Locale? =
        if (has(name) &&
            !isNull(name)
        ) {
            Locale.forLanguageTag(getString(name))
        } else {
            null
        }

    private fun JSONObject.optHint(name: String): SpeechSemanticHint =
        if (has(name) && !isNull(name)) speechSemanticHint(getString(name)) else SpeechSemanticHint.None

    private fun JSONObject.optStrings(name: String): List<String> = optJSONArray(name)?.strings().orEmpty()

    private fun JSONObject.optStringLists(name: String): List<List<String>> {
        if (!has(name)) return emptyList()
        val outer = getJSONArray(name)
        return (0 until outer.length()).map { index -> outer.getJSONArray(index).strings() }
    }

    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
}

/** Maps the corpus hint vocabulary onto the pure-adapter hint input. */
internal fun speechSemanticHint(name: String): SpeechSemanticHint =
    when (name) {
        "year" -> SpeechSemanticHint.Year
        "year_month" -> SpeechSemanticHint.YearMonth
        "ordinal_date" -> SpeechSemanticHint.OrdinalDate
        "clock" -> SpeechSemanticHint.Clock
        "duration" -> SpeechSemanticHint.Duration
        "fraction" -> SpeechSemanticHint.Fraction
        "number" -> SpeechSemanticHint.Number
        "accounting_amount" -> SpeechSemanticHint.AccountingAmount
        "unknown" -> SpeechSemanticHint.Unknown
        else -> error("unknown corpus semantic hint '$name'")
    }

/**
 * The corpus spoken-core contract: compare exact lexical content, ignoring only
 * outer whitespace and one added terminal sentence mark. Any other punctuation
 * or case difference is a golden-review change, not something a test normalizes
 * away.
 */
internal fun spokenCoreOf(spoken: String): String = spoken.trim().trimEnd('.', '!', '?')
