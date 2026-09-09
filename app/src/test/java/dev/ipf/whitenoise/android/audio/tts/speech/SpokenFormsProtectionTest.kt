package dev.ipf.whitenoise.android.audio.tts.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Near-match and invalid structured tokens must survive as complete protected
 * tokens, and no fixture-only hint may leak into the runtime path.
 */
class SpokenFormsProtectionTest {
    @Test
    fun invalidDatesStayLiteralInsteadOfBeingHalfRewritten() {
        assertCorpusCases(SpeechCorpusFixtures.families("date_negative"))
    }

    @Test
    fun invalidClocksAndZonesStayLiteral() {
        assertCorpusCases(SpeechCorpusFixtures.families("time_negative"))
    }

    @Test
    fun ambiguousAmountsAndSymbolsDoNotAcquireSemantics() {
        assertCorpusCases(SpeechCorpusFixtures.families("money_negative", "weight_negative"))
    }

    @Test
    fun identifiersAndWordsResemblingUnitsStayProtected() {
        assertCorpusCases(SpeechCorpusFixtures.families("negative"))
    }

    @Test
    fun boundedNumericWorkRejectsOversizedTokensWithoutExpanding() {
        assertCorpusCases(SpeechCorpusFixtures.families("limits"))
    }

    @Test
    fun unsupportedVoiceLocalesKeepTheirLiteralContent() {
        assertCorpusCases(SpeechCorpusFixtures.families("locale"))
    }

    @Test
    fun anUnsupportedLocaleReportsExplicitlyThatItWasNotVerbalized() {
        val verbalized =
            SpokenForms.verbalize(
                source = "2,5 kg",
                leafId = CORPUS_LEAF_ID,
                context = SpeechContext(voiceLocale = Locale.forLanguageTag("fr-FR")),
            )

        assertEquals(SpeechLocaleSupport.Literal, verbalized.localeSupport)
        assertTrue(verbalized.runs.all { it.kind == SpeechMappingKind.Identity })
    }

    @Test
    fun aSupportedLocaleReportsValidatedVerbalization() {
        val verbalized =
            SpokenForms.verbalize(
                source = "2.5 kg",
                leafId = CORPUS_LEAF_ID,
                context = SpeechContext(voiceLocale = Locale.US),
            )

        assertEquals(SpeechLocaleSupport.Verbalized, verbalized.localeSupport)
    }

    @Test
    fun aFixtureOnlyHintNeverArrivesFromTheVoiceLocale() {
        val hintless = SpeechContext(voiceLocale = Locale.US)

        assertEquals(SpeechSemanticHint.None, hintless.semanticHint)
        assertEquals(null, hintless.sourceFormatLocale)
    }

    @Test
    fun generatedOutputIsNotReinterpretedByTheRecognizers() {
        // A second pass over the already expanded "forty-five dollars per
        // month" must not rediscover "45" or "mo" inside the verbalizer's own
        // output.
        val once =
            SpokenForms.verbalize(
                source = "\$45/mo",
                leafId = CORPUS_LEAF_ID,
                context = SpeechContext(voiceLocale = Locale.US),
            )
        val twice =
            SpokenForms.verbalize(
                source = once.text,
                leafId = CORPUS_LEAF_ID,
                context = SpeechContext(voiceLocale = Locale.US),
            )

        assertEquals(once.text, twice.text)
    }

    @Test
    fun mostSpecificCompleteFormWinsOverLaterGenericRules() {
        val verbalized =
            SpokenForms.verbalize(
                source = "2026-09-08",
                leafId = CORPUS_LEAF_ID,
                context = SpeechContext(voiceLocale = Locale.US),
            )

        assertFalse("a date must not be read as a subtraction", verbalized.text.contains("minus"))
        assertEquals(
            listOf(SpeechSourceSpan(CORPUS_LEAF_ID, 0, "2026-09-08".length)),
            verbalized.replacementSourceSpans(CORPUS_LEAF_ID),
        )
    }
}
