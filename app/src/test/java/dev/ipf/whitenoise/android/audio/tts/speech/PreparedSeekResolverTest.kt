package dev.ipf.whitenoise.android.audio.tts.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Inverse resolution: a tap on displayed text reaches the intended logical
 * sentence, including expanded, repeated and reused source atoms.
 */
class PreparedSeekResolverTest {
    @Test
    fun aTapInsideAnExpansionReachesTheOwningSentence() {
        val source = "Ship it. Pay $12.50."
        val message = prepareMessage(proseRun(source))
        val expected = message.sentenceSpeaking("twelve dollars").sentenceId

        val target = resolve(message, source, source.indexOf("12.50") + 1)

        assertEquals(PreparedSeekTarget.Sentence(expected, ordinal = 1), target)
    }

    @Test
    fun eachRepeatedAmountReachesItsOwnSentence() {
        val source = "Pay $5.00. Then $7.00."
        val message = prepareMessage(proseRun(source))

        val first = resolve(message, source, source.indexOf("$5.00"))
        val second = resolve(message, source, source.indexOf("$7.00"))

        assertEquals(0, (first as PreparedSeekTarget.Sentence).ordinal)
        assertEquals(1, (second as PreparedSeekTarget.Sentence).ordinal)
        assertNotEquals(first.sentenceId, second.sentenceId)
    }

    @Test
    fun repeatedIdenticalSentencesRemainDistinguishableByPosition() {
        val source = "Done. Done."
        val message = prepareMessage(proseRun(source))

        val second = resolve(message, source, source.lastIndexOf("Done"))

        assertEquals(1, (second as PreparedSeekTarget.Sentence).ordinal)
    }

    @Test
    fun punctuationInsideASourceUnitInheritsThatUnitsOwner() {
        val source = "Pay $12.50."
        val message = prepareMessage(proseRun(source))
        val insideAmount = resolve(message, source, source.indexOf('.', source.indexOf("12")))
        val onAmount = resolve(message, source, source.indexOf("$12.50"))

        assertEquals(onAmount, insideAmount)
    }

    @Test
    fun aGapBetweenUnitsResolvesToTheFollowingEligibleUnit() {
        val source = "Yes.   Next."
        val message = prepareMessage(proseRun(source))

        val target = resolve(message, source, source.indexOf("   ") + 1)

        assertEquals(1, (target as PreparedSeekTarget.Sentence).ordinal)
    }

    @Test
    fun aGapAtLeafEndFallsBackToThePrecedingUnit() {
        val source = "Yes. Next.   "
        val message = prepareMessage(proseRun(source))

        val target = resolve(message, source, source.length - 1)

        assertEquals(1, (target as PreparedSeekTarget.Sentence).ordinal)
    }

    @Test
    fun anOmittedLinkStaysNonSeekableInsteadOfBorrowingASentence() {
        val source = "See https://example.com now."
        val message = prepareMessage(proseRun(source))

        val target = resolve(message, source, source.indexOf("example"))

        assertEquals(PreparedSeekTarget.NotSeekable, target)
    }

    @Test
    fun anOffsetOutsideTheRenderedLeafIsNotSeekable() {
        val source = "Yes."
        val message = prepareMessage(proseRun(source))

        assertEquals(PreparedSeekTarget.NotSeekable, resolve(message, source, source.length + 5))
        assertEquals(PreparedSeekTarget.NotSeekable, resolve(message, "Different rendered text", 3))
    }

    @Test
    fun aSharedDiagramNodeKeepsEveryForwardReferenceButOneCanonicalSeekOwner() {
        val source = "Client -> Relay -> Receiver"
        val message = prepareMessage(sourceRun(source, role = SpeechRole.Diagram))
        val relayOffset = source.indexOf("Relay")

        val forward = message.forwardSentenceIdsForSource(PRIMARY_LEAF_ID, relayOffset)
        val canonical = message.canonicalSentenceIdForSource(PRIMARY_LEAF_ID, relayOffset)

        assertEquals("Relay is narrated in both relations", 2, forward.size)
        assertEquals("the canonical owner is the earliest narration", forward.first(), canonical)
        assertEquals(
            PreparedSeekTarget.Sentence(message.sentences.first().sentenceId, ordinal = 0),
            resolve(message, source, relayOffset),
        )
    }

    @Test
    fun aRepeatedTableHeaderAlwaysSeeksItsEarliestNarration() {
        val message = tableMessage()
        val headerLeaf = "b0/h1"

        val forward = message.forwardSentenceIdsForSource(headerLeaf, 0)
        val canonical = message.canonicalSentenceIdForSource(headerLeaf, 0)

        assertTrue("the Mass header is read with every row", forward.size >= 2)
        assertEquals(forward.first(), canonical)
    }

    @Test
    fun aTableCellSeeksItsOwnRowRatherThanTheHeader() {
        val message = tableMessage()
        val secondRowLeaf = "b0/r1/c1"

        val target = message.canonicalSentenceIdForSource(secondRowLeaf, 0)
        val headerOwner = message.canonicalSentenceIdForSource("b0/h1", 0)

        assertNotEquals(headerOwner, target)
        assertEquals(message.sentenceSpeaking("one kilogram").sentenceId, target)
    }

    @Test
    fun canonicalOwnershipDoesNotDependOnIterationOrPlaybackOrder() {
        val source = "Client -> Relay -> Receiver"
        val message = prepareMessage(sourceRun(source, role = SpeechRole.Diagram))
        val relayOffset = source.indexOf("Relay")

        val before = message.canonicalSentenceIdForSource(PRIMARY_LEAF_ID, relayOffset)
        val reversed =
            message
                .copy(sentences = message.sentences.reversed())
                .canonicalSentenceIdForSource(PRIMARY_LEAF_ID, relayOffset)

        assertEquals(before, reversed)
    }

    private fun tableMessage(): PreparedSpeechMessage =
        prepareMessage(
            SpeechSourceRun("b0/h0", "Item", SpeechRole.Prose, structure = SpeechStructure.TableHeader(0)),
            SpeechSourceRun("b0/h1", "Mass", SpeechRole.Prose, structure = SpeechStructure.TableHeader(1)),
            SpeechSourceRun("b0/r0/c0", "Rice", SpeechRole.Prose, structure = SpeechStructure.TableCell(0, 0)),
            SpeechSourceRun("b0/r0/c1", "2 kg", SpeechRole.Prose, structure = SpeechStructure.TableCell(0, 1)),
            SpeechSourceRun("b0/r1/c0", "Flour", SpeechRole.Prose, structure = SpeechStructure.TableCell(1, 0)),
            SpeechSourceRun("b0/r1/c1", "1 kg", SpeechRole.Prose, structure = SpeechStructure.TableCell(1, 1)),
        )

    private fun resolve(
        message: PreparedSpeechMessage,
        renderedText: String,
        renderedOffset: Int,
        leafId: String = PRIMARY_LEAF_ID,
    ): PreparedSeekTarget =
        PreparedSeekResolver.resolve(
            message = message,
            hit = PreparedRenderedHit(leafId = leafId, renderedText = renderedText, renderedOffset = renderedOffset),
        )
}
