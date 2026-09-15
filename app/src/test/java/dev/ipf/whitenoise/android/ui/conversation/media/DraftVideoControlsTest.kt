package dev.ipf.whitenoise.android.ui.conversation.media

import org.junit.Assert.assertEquals
import org.junit.Test

/** The draft player's speed toggle cycles the prototype's rates and labels them without trailing zeros. */
class DraftVideoControlsTest {
    /** Cycles 1x to 1.5x to 2x to 0.5x and back to the start. */
    @Test
    fun speedToggleCyclesThePrototypeRates() {
        assertEquals(1.5f, nextPlaybackSpeed(1f), 0.001f)
        assertEquals(2f, nextPlaybackSpeed(1.5f), 0.001f)
        assertEquals(0.5f, nextPlaybackSpeed(2f), 0.001f)
        assertEquals(1f, nextPlaybackSpeed(0.5f), 0.001f)
    }

    /** An unrecognized rate falls back to normal speed rather than throwing. */
    @Test
    fun unknownSpeedFallsBackToNormal() {
        assertEquals(1f, nextPlaybackSpeed(3.25f), 0.001f)
    }

    /** Whole rates lose their decimal, fractional rates keep exactly one. */
    @Test
    fun speedLabelsDropTrailingZeros() {
        assertEquals("1×", playbackSpeedLabel(1f))
        assertEquals("1.5×", playbackSpeedLabel(1.5f))
        assertEquals("2×", playbackSpeedLabel(2f))
        assertEquals("0.5×", playbackSpeedLabel(0.5f))
    }

    /** The seek buttons step the clip by the prototype's fixed ten seconds. */
    @Test
    fun seekStepMatchesThePrototype() {
        assertEquals(10_000L, DRAFT_VIDEO_SEEK_STEP_MS)
    }
}
