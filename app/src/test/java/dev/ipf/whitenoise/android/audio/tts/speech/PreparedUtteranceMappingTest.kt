package dev.ipf.whitenoise.android.audio.tts.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The shared prepared utterance is the only mapping authority: every audible
 * lexical span is proven source or explicitly synthetic, and no consumer needs
 * to search the spoken string.
 */
class PreparedUtteranceMappingTest {
    @Test
    fun identityRunsMapCharacterWise() {
        val source = "Hello there."

        val utterance = prepareMessage(proseRun(source)).sentences.single().utterance

        assertEquals(
            listOf(sourceSpan(source, "there")),
            utterance.spansFor("there"),
        )
    }

    @Test
    fun anExpansionLongerThanItsSourceMapsToTheCompleteSourceAtom() {
        val source = "Pay \$12.50."
        val amount = sourceSpan(source, "\$12.50")

        val utterance = prepareMessage(proseRun(source)).sentences.single().utterance

        assertEquals(listOf(amount), utterance.spansFor("twelve dollars"))
        assertEquals(listOf(amount), utterance.spansFor("fifty cents"))
    }

    @Test
    fun aReplacementShorterThanItsSourceStillOwnsTheWholeSourceAtom() {
        val source = "Meet at 12:00 p.m."

        val utterance = prepareMessage(proseRun(source)).sentences.single().utterance

        assertEquals(listOf(sourceSpan(source, "12:00 p.m.")), utterance.spansFor("noon"))
    }

    @Test
    fun repeatedAmountsMapToTheirOwnOccurrenceOnly() {
        val source = "Pay \$5.00, then \$5.00."
        val first = sourceSpan(source, "\$5.00", occurrence = 0)
        val second = sourceSpan(source, "\$5.00", occurrence = 1)

        val utterance = prepareMessage(proseRun(source)).sentences.single().utterance
        val expansions = utterance.originRuns.filter { it.kind == SpeechMappingKind.Replacement }

        assertEquals(2, expansions.size)
        assertEquals(listOf(listOf(first), listOf(second)), expansions.map(SpokenOriginRun::sources))
        assertNotEquals(expansions[0].spoken, expansions[1].spoken)
    }

    @Test
    fun oneQuantitySplitAcrossEmphasisLeavesKeepsBothLeafSpans() {
        val message =
            prepareMessage(
                proseRun("2.5", leafId = PRIMARY_LEAF_ID),
                proseRun(" kg", leafId = SECOND_LEAF_ID),
            )

        val utterance = message.sentences.single().utterance

        assertEquals(
            listOf(
                SpeechSourceSpan(PRIMARY_LEAF_ID, 0, 3),
                SpeechSourceSpan(SECOND_LEAF_ID, 1, 3),
            ),
            utterance.spansFor("two point five kilograms"),
        )
    }

    @Test
    fun discontiguousSourcesStayDistinctSpansInsteadOfOneMergedSpan() {
        val message =
            prepareMessage(
                proseRun("2.5", leafId = PRIMARY_LEAF_ID),
                proseRun(" kg", leafId = SECOND_LEAF_ID),
            )

        val spans =
            message.sentences
                .single()
                .utterance
                .spansFor("two point five kilograms")

        assertEquals(2, spans.size)
        assertEquals(2, spans.map(SpeechSourceSpan::leafId).distinct().size)
    }

    @Test
    fun aSyntheticSenderPrefixHasNoSourceAndNoInventedVisibleWord() {
        val message = prepareMessage(proseRun("Yes."), senderAnnouncement = "Alice")

        val utterance = message.sentences.first().utterance
        val prefix = requireNotNull(utterance.senderPrefix)
        val prefixRuns = utterance.originRuns.filter { it.spoken.start < prefix.end }

        assertTrue(prefixRuns.isNotEmpty())
        assertEquals(listOf(SpeechMappingKind.Synthetic), prefixRuns.map(SpokenOriginRun::kind).distinct())
        assertEquals(emptyList<SpeechSourceSpan>(), prefixRuns.flatMap(SpokenOriginRun::sources))
        assertEquals(emptyList<SpeechSourceSpan>(), utterance.sourceSpansForSpoken(prefix))
    }

    @Test
    fun syntheticConnectiveWordsBelongToASentenceButOwnNoVisibleWord() {
        val message = prepareMessage(sourceRun("Client -> Relay", role = SpeechRole.Diagram))

        val synthetic =
            message.sentences
                .flatMap { it.utterance.originRuns }
                .filter { it.kind == SpeechMappingKind.Synthetic }

        assertTrue("generated narration needs synthetic connectives", synthetic.isNotEmpty())
        assertEquals(emptyList<SpeechSourceSpan>(), synthetic.flatMap(SpokenOriginRun::sources))
    }

    @Test
    fun contractionsStayOneIdentityWord() {
        val source = "I don't know."

        val utterance = prepareMessage(proseRun(source)).sentences.single().utterance

        assertEquals(listOf(sourceSpan(source, "don't")), utterance.spansFor("don't"))
    }

    @Test
    fun surrogatePairsAndZwjSequencesAreNeverSplitByAMapping() {
        val source = "Café costs €2. 👩‍💻 Done."

        val message = prepareMessage(proseRun(source))

        message.sentences.flatMap { it.utterance.originRuns }.flatMap(SpokenOriginRun::sources).forEach { span ->
            assertTrue("span $span splits a surrogate pair", source.isUtf16Boundary(span.start))
            assertTrue("span $span splits a surrogate pair", source.isUtf16Boundary(span.end))
        }
        assertTrue(message.sentences.any { it.utterance.engineText.contains("Done") })
    }

    @Test
    fun spokenWordRangesAlignToWholeGraphemes() {
        val source = "Résumé ready."

        val utterance = prepareMessage(proseRun(source)).sentences.single().utterance

        utterance.spokenWords.forEach { word ->
            assertTrue(word.end > word.start)
            assertTrue(utterance.engineText.isUtf16Boundary(word.start))
            assertTrue(utterance.engineText.isUtf16Boundary(word.end))
        }
    }

    @Test
    fun rtlSourceKeepsLeafLocalOffsetsWithoutBridgingBidiGaps() {
        val source = "السعر 12 USD"

        val utterance = prepareMessage(proseRun(source)).sentences.single().utterance
        val amountSpans = utterance.spansFor("US dollars")

        assertEquals(listOf(sourceSpan(source, "12 USD")), amountSpans)
        assertTrue(
            "leaf offsets must stay inside the leaf's own UTF-16 text",
            amountSpans.all { it.start >= 0 && it.end <= source.length },
        )
    }

    @Test
    fun identityProvenanceStaysMonotonicInSourceOrder() {
        val source = "First 2.5 kg then 3 kg finally done."

        val utterance = prepareMessage(proseRun(source)).sentences.single().utterance
        val starts =
            utterance.originRuns
                .filter { it.kind != SpeechMappingKind.Synthetic }
                .flatMap(SpokenOriginRun::sources)
                .filter { it.leafId == PRIMARY_LEAF_ID }
                .map(SpeechSourceSpan::start)

        assertEquals(starts.sorted(), starts)
    }

    @Test
    fun forwardMappingAndReverseSentenceResolutionAgree() {
        val source = "Pay \$5.00. Then ship 2.5 kg."

        val message = prepareMessage(proseRun(source))

        message.sentences.forEach { sentence ->
            sentence.utterance.originRuns
                .filter { it.kind != SpeechMappingKind.Synthetic }
                .flatMap(SpokenOriginRun::sources)
                .forEach { span ->
                    assertTrue(
                        "source $span narrated by ${sentence.sentenceId} must resolve back to it",
                        sentence.sentenceId in message.forwardSentenceIdsForSource(span.leafId, span.start),
                    )
                }
        }
    }

    @Test
    fun provenanceCarriesEveryVersionedIdentityComponent() {
        val message = prepareMessage(proseRun("Yes."), revisionId = "rev-7")

        val provenance = message.provenance
        assertEquals(SPEECH_PREPARATION_VERSION, provenance.preparationVersion)
        assertEquals(SPEECH_VERBALIZER_VERSION, provenance.verbalizerVersion)
        assertEquals("rev-7", provenance.sourceRevisionId)
        assertEquals(Locale.US.toLanguageTag(), provenance.effectiveLanguageTag)
        assertEquals(SpeechMode.Default, provenance.mode)
        assertEquals(
            provenance,
            message.sentences
                .single()
                .utterance.provenance,
        )
    }

    @Test
    fun aDifferentEffectiveLanguageProducesADifferentSpeechIdentity() {
        val english = prepareMessage(proseRun("2.5 kg"))
        val french =
            prepareMessage(
                proseRun("2.5 kg"),
                context = SpeechContext(voiceLocale = Locale.forLanguageTag("fr-FR")),
            )

        assertNotEquals(english.provenance, french.provenance)
    }

    @Test
    fun theDisplayPreviewKeepsTheOriginalTextRatherThanTheExpansion() {
        val source = "Pay \$12.50."

        val message = prepareMessage(proseRun(source))

        assertEquals(source, message.displayPreview)
        assertTrue(
            message.sentences
                .single()
                .utterance.engineText
                .contains("twelve dollars"),
        )
    }

    private fun String.isUtf16Boundary(index: Int): Boolean =
        index == 0 ||
            index == length ||
            !(this[index - 1].isHighSurrogate() && this[index].isLowSurrogate())
}
