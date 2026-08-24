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
        assertFalse(probe.onUtteranceDone(spokenLength = 60))

        assertNull(probe.reportsRanges)
    }

    @Test
    fun aRunOfShortUtterancesIsAsConclusiveAsOneLongOne() {
        val probe = TtsRangeCapabilityProbe(charsToConclude = 100)

        probe.onUtteranceStart()
        assertFalse(probe.onUtteranceDone(spokenLength = 60))
        probe.onUtteranceStart()
        assertTrue(probe.onUtteranceDone(spokenLength = 60))

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
        assertFalse(probe.onUtteranceDone(spokenLength = 500))
        assertEquals(true, probe.reportsRanges)
    }

    @Test
    fun anUtteranceThatSawARangeDoesNotCountAsSilence() {
        val probe = TtsRangeCapabilityProbe(charsToConclude = 100)

        probe.onUtteranceStart()
        probe.onRangeStart()
        assertFalse(probe.onUtteranceDone(spokenLength = 500))

        assertEquals(true, probe.reportsRanges)
    }

    @Test
    fun aSettledVerdictIsReportedExactlyOnceForPersistence() {
        val probe = TtsRangeCapabilityProbe(charsToConclude = 50)

        probe.onUtteranceStart()
        assertTrue(probe.onUtteranceDone(spokenLength = 60))
        probe.onUtteranceStart()
        assertFalse(probe.onUtteranceDone(spokenLength = 60))
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
        assertFalse(probe.onUtteranceDone(spokenLength = -100))
        assertNull(probe.reportsRanges)
    }
}
