package dev.ipf.whitenoise.android.audio.tts.speech

import dev.ipf.whitenoise.android.audio.tts.TtsSpokenTextSpan
import dev.ipf.whitenoise.android.audio.tts.TtsTextRange
import dev.ipf.whitenoise.android.audio.tts.TtsVisibleTextSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Narrow guards for regressions that would otherwise look harmless in review:
 * restoring the equal-length carrier, collapsing an SI prefix, rounding a
 * currency, losing a one-word sentence, or dropping an identity check.
 */
class SpeechMutationGuardTest {
    @Test
    fun aReplacementCarrierNoLongerRequiresEqualSpokenAndVisibleLengths() {
        val span =
            TtsSpokenTextSpan(
                spoken = TtsTextRange(0, "twelve dollars and fifty cents".length),
                visible = TtsVisibleTextSpan(PRIMARY_LEAF_ID, 4, 10),
                kind = SpeechMappingKind.Replacement,
            )

        assertEquals(SpeechMappingKind.Replacement, span.kind)
        assertEquals(6, span.visible.end - span.visible.start)
    }

    @Test(expected = IllegalArgumentException::class)
    fun anIdentityCarrierStillValidatesEqualLengths() {
        TtsSpokenTextSpan(
            spoken = TtsTextRange(0, 30),
            visible = TtsVisibleTextSpan(PRIMARY_LEAF_ID, 4, 10),
            kind = SpeechMappingKind.Identity,
        )
    }

    @Test
    fun theCarrierDefaultsToIdentitySoUnmigratedCallersKeepTheirValidation() {
        val span =
            TtsSpokenTextSpan(
                spoken = TtsTextRange(0, 6),
                visible = TtsVisibleTextSpan(PRIMARY_LEAF_ID, 4, 10),
            )

        assertEquals(SpeechMappingKind.Identity, span.kind)
    }

    @Test
    fun milligramsNeverCollapseIntoGrams() {
        val milli = verbalize("0.5 mg")
        val mega = verbalize("1 Mg")
        val gram = verbalize("1 g")

        assertTrue(milli.contains("milligrams"))
        assertTrue(mega.contains("megagram"))
        assertEquals("one gram", spokenCoreOf(gram))
        assertFalse("mg must not read as grams", spokenCoreOf(milli) == "zero point five grams")
    }

    @Test
    fun currencyPrecisionBeyondMinorUnitsIsReadNotRounded() {
        val spoken = verbalize("\$12.505")

        assertTrue("all source digits must survive", spoken.contains("five zero five") || spoken.contains("zero five"))
        assertFalse(spoken.contains("fifty-one cents"))
        assertFalse(spoken.contains("fifty cents and"))
    }

    @Test
    fun exactMinorUnitsAreNotReplacedByADecimalReading() {
        assertEquals("twelve dollars and fifty cents", spokenCoreOf(verbalize("\$12.50")))
    }

    @Test
    fun noOneWordSentenceIsEverDropped() {
        val source = "Yes. No! OK. Done."

        val message = prepareMessage(proseRun(source))

        assertEquals(4, message.sentences.size)
        assertTrue(
            "every one-word sentence must carry audible lexical content",
            message.sentences.all { it.utterance.engineText.any(Char::isLetterOrDigit) },
        )
    }

    @Test
    fun aSingleWordMessagePreparesExactlyOneAudibleSentence() {
        listOf("Yes.", "No!", "OK", "I.", "A.", "7", "Done").forEach { source ->
            val message = prepareMessage(proseRun(source))

            assertEquals("'$source' must prepare one sentence", 1, message.sentences.size)
            assertTrue(
                message.sentences
                    .single()
                    .utterance.spokenWords
                    .isNotEmpty(),
            )
        }
    }

    @Test
    fun aPreparedMessageIsRejectedWhenItsSpeechIdentityNoLongerMatches() {
        val context = SpeechContext(voiceLocale = Locale.US)
        val message = prepareMessage(proseRun("2.5 kg"), context = context, revisionId = "rev-1")

        assertTrue(message.matchesSpeechIdentity(context, "rev-1"))
        assertFalse(
            "a newer source revision must invalidate the prepared table",
            message.matchesSpeechIdentity(context, "rev-2"),
        )
        assertFalse(
            "a different effective voice language must invalidate the prepared table",
            message.matchesSpeechIdentity(
                SpeechContext(voiceLocale = Locale.forLanguageTag("fr-FR")),
                "rev-1",
            ),
        )
    }

    @Test
    fun aStaleVerbalizerVersionInvalidatesThePreparedTable() {
        val context = SpeechContext(voiceLocale = Locale.US)
        val message = prepareMessage(proseRun("2.5 kg"), context = context)

        assertFalse(
            message.matchesSpeechIdentity(
                context.copy(verbalizerVersion = SPEECH_VERBALIZER_VERSION + 1),
                "rev-1",
            ),
        )
    }

    private fun verbalize(source: String): String =
        SpokenForms
            .verbalize(
                source = source,
                leafId = PRIMARY_LEAF_ID,
                context = SpeechContext(voiceLocale = Locale.US),
            ).text
}
