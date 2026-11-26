package dev.ipf.whitenoise.android.ui.group

import org.junit.Assert.assertEquals
import org.junit.Test

class DisappearingTimerEditorTest {
    /** A protocol timer past `Long.MAX_VALUE` stays the longest timer, so shortening it still asks first. */
    @Test
    fun shorteningAnOutOfRangeProtocolTimerAsksFirst() {
        val current = ULong.MAX_VALUE.clampedDisappearingSecs()

        assertEquals(DisappearingPick.Confirm, disappearingPickOutcome(currentSecs = current, secs = 300L))
    }

    /** Only picks that prune existing history ask first. */
    @Test
    fun pickOutcomeConfirmsOnlyHistoryPruningChanges() {
        assertEquals(DisappearingPick.Skip, disappearingPickOutcome(currentSecs = 60L, secs = 60L))
        assertEquals(DisappearingPick.Skip, disappearingPickOutcome(currentSecs = 0L, secs = 0L))
        assertEquals(DisappearingPick.Confirm, disappearingPickOutcome(currentSecs = 0L, secs = 60L))
        assertEquals(DisappearingPick.Confirm, disappearingPickOutcome(currentSecs = 3_600L, secs = 60L))
        assertEquals(DisappearingPick.Apply, disappearingPickOutcome(currentSecs = 60L, secs = 3_600L))
        assertEquals(DisappearingPick.Apply, disappearingPickOutcome(currentSecs = 60L, secs = 0L))
    }
}
