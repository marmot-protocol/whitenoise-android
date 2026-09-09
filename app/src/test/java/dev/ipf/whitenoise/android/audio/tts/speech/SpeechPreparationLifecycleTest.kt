package dev.ipf.whitenoise.android.audio.tts.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Preparation is bounded, cancellable and explicit: it never returns a
 * successful-looking truncated result, and a continuation resumes instead of
 * restarting.
 */
class SpeechPreparationLifecycleTest {
    @Test
    fun aBlankSourceIsExplicitlyUnavailable() {
        val result = SpeechPreparation.prepare(preparationRequest(proseRun("   ")))

        assertEquals(
            SpeechPreparationResult.Unavailable(SpeechUnavailableReason.BlankSource),
            result,
        )
    }

    @Test
    fun cancellationReturnsAnExplicitUnavailableResultRatherThanPartialSpeech() {
        val result =
            SpeechPreparation.prepare(
                preparationRequest(proseRun("Yes. No. Maybe. Later.")),
                isCancelled = { true },
            )

        assertEquals(
            SpeechPreparationResult.Unavailable(SpeechUnavailableReason.Cancelled),
            result,
        )
    }

    @Test
    fun cancellationIsCheckedWhilePreparingNotOnlyAtTheEnd() {
        var checks = 0

        SpeechPreparation.prepare(
            preparationRequest(proseRun("Yes. No. Maybe. Later.")),
            isCancelled = {
                checks += 1
                false
            },
        )

        assertTrue("preparation must poll cancellation, saw $checks checks", checks > 0)
    }

    @Test
    fun anOutputBudgetSmallerThanTheSourceYieldsAContinuationNotATruncatedSuccess() {
        val source = "Alpha sentence. Beta sentence. Gamma sentence. Delta sentence."

        val result =
            SpeechPreparation.prepare(
                preparationRequest(proseRun(source), maxOutputChars = 20),
            )

        assertTrue("expected a continuation, got $result", result is SpeechPreparationResult.Continuation)
    }

    @Test
    fun continuationCoversTheWholeSourceExactlyOnceWithoutRestarting() {
        val source = "Alpha sentence. Beta sentence. Gamma sentence. Delta sentence."
        val covered = mutableListOf<Int>()
        var cursor: SpeechPreparationCursor? = null
        var previousOffset = -1
        var batches = 0

        do {
            val result =
                SpeechPreparation.prepare(
                    preparationRequest(proseRun(source), maxOutputChars = 20, cursor = cursor),
                )
            batches += 1
            val message = result.messageOrFail()
            covered += message.sentences.flatMap { it.utterance.sourceCoverage(PRIMARY_LEAF_ID) }
            cursor = (result as? SpeechPreparationResult.Continuation)?.cursor
            cursor?.let {
                assertTrue("continuation cursors must advance", it.sourceOffset > previousOffset)
                previousOffset = it.sourceOffset
            }
            assertTrue("bounded continuation must terminate", batches < 20)
        } while (cursor != null)

        assertEquals("no source offset may be prepared twice", covered.distinct().size, covered.size)
        assertEquals(
            source.indices.filterNot { source[it].isWhitespace() }.toSet(),
            covered.toSet(),
        )
    }

    @Test
    fun aCursorFromAnotherRevisionIsRejectedInsteadOfMisapplied() {
        val stale =
            SpeechPreparationCursor(
                sourceRevisionId = "rev-old",
                sourceOffset = 3,
                nextSentenceOrdinal = 1,
                leafPath = PRIMARY_LEAF_ID,
            )

        val result =
            SpeechPreparation.prepare(
                preparationRequest(proseRun("Yes. No."), revisionId = "rev-new", cursor = stale),
            )

        assertEquals(
            SpeechPreparationResult.Unavailable(SpeechUnavailableReason.IncompatibleCursor),
            result,
        )
    }

    @Test
    fun aGraphemeLargerThanTheEngineBoundIsRecoverablyUnsupported() {
        val result =
            SpeechPreparation.prepare(
                preparationRequest(proseRun("👩‍💻"), maxOutputChars = 2),
            )

        assertEquals(
            SpeechPreparationResult.Unavailable(SpeechUnavailableReason.UnsupportedInput),
            result,
        )
    }

    @Test
    fun theExistingResourceBoundsAreUnchanged() {
        assertEquals(32_000, SPEECH_PREPARATION_MAX_OUTPUT_CHARS)
        assertEquals(4_096, SPEECH_PREPARATION_MAX_NODES)
    }

    @Test
    fun sourceExhaustionIsDistinctFromOutputBudgetExhaustion() {
        val complete = SpeechPreparation.prepare(preparationRequest(proseRun("Yes."), maxOutputChars = 4_000))
        val budgeted =
            SpeechPreparation.prepare(
                preparationRequest(proseRun("Alpha sentence. Beta sentence."), maxOutputChars = 18),
            )

        assertTrue(complete is SpeechPreparationResult.Complete)
        assertTrue(budgeted is SpeechPreparationResult.Continuation)
    }

    @Test
    fun preparationUnderADifferentEffectiveVoiceIsNotInterchangeable() {
        val english = prepareMessage(proseRun("2.5 kg"))
        val french =
            prepareMessage(
                proseRun("2.5 kg"),
                context = SpeechContext(voiceLocale = Locale.forLanguageTag("fr-FR")),
            )

        assertNotEquals(english.provenance.effectiveLanguageTag, french.provenance.effectiveLanguageTag)
        assertNotEquals(
            english.sentences
                .single()
                .utterance.engineText,
            french.sentences
                .single()
                .utterance.engineText,
        )
    }

    private fun SpeechPreparationResult.messageOrFail(): PreparedSpeechMessage =
        when (this) {
            is SpeechPreparationResult.Complete -> message
            is SpeechPreparationResult.Continuation -> message
            is SpeechPreparationResult.Unavailable -> error("unexpected unavailable preparation: $reason")
        }

    private fun PreparedUtterance.sourceCoverage(leafId: String): List<Int> =
        originRuns
            .flatMap(SpokenOriginRun::sources)
            .filter { it.leafId == leafId }
            .flatMap { it.start until it.end }
            .distinct()
}
