package dev.ipf.whitenoise.android.audio.tts.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Deterministic bounded English verbalization for the supported structured
 * matrix. Every expectation comes from the checked-in corpus resource, never
 * from the verbalizer under test.
 */
class SpokenFormsTest {
    @Test
    fun datesReadAsCalendarDatesOnlyWhenTheFormIsProven() {
        assertCorpusCases(SpeechCorpusFixtures.families("date"))
    }

    @Test
    fun timesAndTimestampsPreserveClockSecondsAndZone() {
        assertCorpusCases(SpeechCorpusFixtures.families("time"))
    }

    @Test
    fun durationsReadAsElapsedTimeOnlyInDurationContext() {
        assertCorpusCases(SpeechCorpusFixtures.families("duration"))
    }

    @Test
    fun moneyReadsCurrencyNamesAndExactMinorUnits() {
        assertCorpusCases(SpeechCorpusFixtures.families("money"))
    }

    @Test
    fun cryptoAmountsKeepTheirExactDecimalScale() {
        assertCorpusCases(SpeechCorpusFixtures.families("crypto"))
    }

    @Test
    fun weightUnitsRespectSiPrefixCaseAndCompoundValues() {
        assertCorpusCases(SpeechCorpusFixtures.families("weight"))
    }

    @Test
    fun otherMeasurementsReadFromTheBoundedUnitRegistry() {
        assertCorpusCases(SpeechCorpusFixtures.families("measure"))
    }

    @Test
    fun numbersPreserveSourcePrecisionAndScale() {
        assertCorpusCases(SpeechCorpusFixtures.families("number"))
    }

    @Test
    fun invalidRatioFragmentsRemainLiteralInsteadOfThrowing() {
        val source = "Ratio: 2:x"
        val verbalized =
            SpokenForms.verbalize(
                source = source,
                leafId = CORPUS_LEAF_ID,
                context = SpeechContext(voiceLocale = Locale.US),
            )

        assertFalse(verbalized.text.contains("two to"))
    }

    @Test
    fun twoPartYearsSpeakASingleDigitRemainderWithOh() {
        assertEquals("nineteen oh five", EnglishNumbers.year(1905))
    }

    @Test
    fun everyCorpusCaseIsCoveredByAFamilyTest() {
        val covered =
            setOf(
                "date",
                "time",
                "duration",
                "money",
                "crypto",
                "weight",
                "measure",
                "number",
                "date_negative",
                "time_negative",
                "money_negative",
                "weight_negative",
                "negative",
                "limits",
                "locale",
            )
        assertEquals(
            emptyList<String>(),
            SpeechCorpusFixtures.cases
                .map(SpeechCorpusCase::family)
                .distinct()
                .filterNot { it in covered },
        )
    }

    @Test
    fun aQuantityMapsToItsCompleteSourceSpanAndNotToTheSurroundingProse() {
        val source = "Ship 2.5 kg today."
        val quantityStart = source.indexOf("2.5")
        val quantityEnd = source.indexOf("kg") + "kg".length

        val verbalized =
            SpokenForms.verbalize(
                source = source,
                leafId = CORPUS_LEAF_ID,
                context = SpeechContext(voiceLocale = Locale.US),
            )

        assertTrue(verbalized.text.contains("two point five kilograms"))
        assertEquals(
            listOf(SpeechSourceSpan(CORPUS_LEAF_ID, quantityStart, quantityEnd)),
            verbalized.replacementSourceSpans(CORPUS_LEAF_ID),
        )
    }

    @Test
    fun surroundingProseKeepsCharacterWiseIdentityProvenance() {
        val source = "Ship 2.5 kg today."

        val verbalized =
            SpokenForms.verbalize(
                source = source,
                leafId = CORPUS_LEAF_ID,
                context = SpeechContext(voiceLocale = Locale.US),
            )

        val identity = verbalized.identitySourceOffsets(CORPUS_LEAF_ID)
        assertTrue(identity.containsAll((0 until "Ship".length).toList()))
        assertTrue(identity.containsAll((source.indexOf("today") until source.indexOf("today") + 5).toList()))
        assertTrue(
            "the expanded quantity must not also claim identity provenance",
            identity.none { it in source.indexOf("2.5") until source.indexOf("kg") + 2 },
        )
    }

    @Test
    fun theVerbalizerVersionIsPartOfEveryContext() {
        val context = SpeechContext(voiceLocale = Locale.US)

        assertEquals(SPEECH_VERBALIZER_VERSION, context.verbalizerVersion)
    }
}
