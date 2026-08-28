package dev.ipf.whitenoise.android.audio.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsRangeCapabilityProbeTest {
    @Test
    fun verdictIsUnknownUntilEvidenceAccumulates() {
        val probe = TtsRangeCapabilityProbe(charsToConclude = 100)

        probe.onUtteranceStart()
        assertFalse(probe.onUtteranceDone(answerableLength = 60))

        assertNull(probe.reportsRanges)
    }

    @Test
    fun aRunOfShortUtterancesIsAsConclusiveAsOneLongOne() {
        val probe = TtsRangeCapabilityProbe(charsToConclude = 100)

        probe.onUtteranceStart()
        assertFalse(probe.onUtteranceDone(answerableLength = 60))
        probe.onUtteranceStart()
        assertTrue(probe.onUtteranceDone(answerableLength = 60))

        assertEquals(false, probe.reportsRanges)
    }

    @Test
    fun aRangeCallbackProvesTheEngineCapableImmediately() {
        val probe = TtsRangeCapabilityProbe(charsToConclude = 100)

        probe.onUtteranceStart()
        probe.onRangeStart()

        assertEquals(true, probe.reportsRanges)
        // Later silent utterances cannot demote a proven engine.
        probe.onUtteranceStart()
        assertFalse(probe.onUtteranceDone(answerableLength = 500))
        assertEquals(true, probe.reportsRanges)
    }

    @Test
    fun anUtteranceThatSawARangeDoesNotCountAsSilence() {
        val probe = TtsRangeCapabilityProbe(charsToConclude = 100)

        probe.onUtteranceStart()
        probe.onRangeStart()
        assertFalse(probe.onUtteranceDone(answerableLength = 500))

        assertEquals(true, probe.reportsRanges)
    }

    @Test
    fun aSettledVerdictIsReportedExactlyOnceForPersistence() {
        val probe = TtsRangeCapabilityProbe(charsToConclude = 50)

        probe.onUtteranceStart()
        assertTrue(probe.onUtteranceDone(answerableLength = 60))
        probe.onUtteranceStart()
        assertFalse(probe.onUtteranceDone(answerableLength = 60))
    }

    @Test
    fun restoreSeedsThePersistedVerdict() {
        val probe = TtsRangeCapabilityProbe()

        probe.restore(false)
        assertEquals(false, probe.reportsRanges)

        probe.restore(null)
        assertNull(probe.reportsRanges)
    }

    @Test
    fun negativeSpokenLengthIsIgnored() {
        val probe = TtsRangeCapabilityProbe(charsToConclude = 10)

        probe.onUtteranceStart()
        assertFalse(probe.onUtteranceDone(answerableLength = -100))
        assertNull(probe.reportsRanges)
    }

    @Test
    fun aRestoredCapableVerdictIsProvisionalUntilThisSessionSeesARange() {
        val probe = TtsRangeCapabilityProbe()
        probe.restore(true)

        assertFalse(probe.isConfirmed)
        // Below the overturn threshold the stored verdict stands.
        probe.onUtteranceStart()
        assertFalse(probe.onUtteranceDone(150))
        assertEquals(true, probe.reportsRanges)

        probe.onUtteranceStart()
        assertTrue(probe.onUtteranceDone(50))
        assertEquals(false, probe.reportsRanges)
    }

    @Test
    fun aRestoredCapableVerdictConfirmedByARangeIsNeverOverturned() {
        val probe = TtsRangeCapabilityProbe()
        probe.restore(true)

        probe.onUtteranceStart()
        probe.onRangeStart()
        assertTrue(probe.isConfirmed)

        probe.onUtteranceStart()
        assertFalse(probe.onUtteranceDone(10_000))
        assertEquals(true, probe.reportsRanges)
    }

    @Test
    fun overturningAStoredVerdictNeedsMoreEvidenceThanConcludingFromNothing() {
        val fromNothing = TtsRangeCapabilityProbe()
        fromNothing.onUtteranceStart()
        assertTrue(fromNothing.onUtteranceDone(96))

        val stored = TtsRangeCapabilityProbe()
        stored.restore(true)
        stored.onUtteranceStart()
        assertFalse(stored.onUtteranceDone(96))
        assertEquals(true, stored.reportsRanges)
    }

    @Test
    fun unanswerableUtterancesNeverConclude() {
        val probe = TtsRangeCapabilityProbe()
        repeat(50) {
            probe.onUtteranceStart()
            assertFalse(probe.onUtteranceDone(0))
        }
        assertEquals(null, probe.reportsRanges)
    }

    @Test
    fun aVerdictThatDidNotChangeIsNotWorthPersisting() {
        val probe = TtsRangeCapabilityProbe()
        probe.restore(false)

        probe.onUtteranceStart()
        assertFalse(probe.onUtteranceDone(500))
        assertEquals(false, probe.reportsRanges)
    }
}
