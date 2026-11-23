package dev.ipf.whitenoise.android.ui.conversation.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The composer's voice phase is a pure reading of recorder and review flags. */
class VoiceComposerPhaseTest {
    @Test
    fun phaseTableCoversEveryRecorderAndReviewCombination() {
        val cases =
            listOf(
                VoiceComposerSignals() to VoiceComposerPhase.Idle,
                VoiceComposerSignals(recording = true) to VoiceComposerPhase.Holding,
                VoiceComposerSignals(recording = true, locked = true) to VoiceComposerPhase.Locked,
                VoiceComposerSignals(recording = true, locked = true, paused = true) to VoiceComposerPhase.Paused,
                // An in-app sound can pause an unlocked hold too.
                VoiceComposerSignals(recording = true, paused = true) to VoiceComposerPhase.Paused,
                VoiceComposerSignals(closing = true) to VoiceComposerPhase.Closing,
                VoiceComposerSignals(reviewing = true) to VoiceComposerPhase.Review,
                VoiceComposerSignals(reviewing = true, sending = true) to VoiceComposerPhase.Sending,
                // The review is offered just before the encoder's last cleanup clears Closing.
                VoiceComposerSignals(reviewing = true, closing = true) to VoiceComposerPhase.Review,
                // A quick restart records over an older review until that review is discarded.
                VoiceComposerSignals(recording = true, reviewing = true) to VoiceComposerPhase.Holding,
                VoiceComposerSignals(recording = true, closing = true) to VoiceComposerPhase.Holding,
            )
        cases.forEach { (signals, expected) -> assertEquals(signals.toString(), expected, voiceComposerPhase(signals)) }
    }

    @Test
    fun derivedFlagsSeparateCaptureStripAndReview() {
        val capturing = VoiceComposerPhase.entries.filter { it.capturing }.toSet()
        assertEquals(setOf(VoiceComposerPhase.Holding, VoiceComposerPhase.Locked, VoiceComposerPhase.Paused), capturing)
        assertTrue(VoiceComposerPhase.Closing.showsStrip)
        assertFalse(VoiceComposerPhase.Closing.capturing)
        assertEquals(
            setOf(VoiceComposerPhase.Review, VoiceComposerPhase.Sending),
            VoiceComposerPhase.entries.filter { it.showsReview }.toSet(),
        )
        assertFalse(VoiceComposerPhase.Idle.showsStrip || VoiceComposerPhase.Idle.showsReview)
    }
}
