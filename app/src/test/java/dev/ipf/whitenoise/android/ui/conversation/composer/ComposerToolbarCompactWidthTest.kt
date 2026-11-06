package dev.ipf.whitenoise.android.ui.conversation.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerToolbarCompactWidthTest {
    /** Keeps every configured target when the short-height viewport has enough spare width. */
    @Test
    fun wideCompressedViewportsKeepAllDefaultActionsReachable() {
        val toolbar = composerToolbarCompactWidth(780f, 3, 52f, 96f)
        assertEquals(108f, toolbar, 0f)
        assertTrue(toolbar + 52f + 96f + 8f <= 780f)
    }

    /** Reserves readable large text before assigning whole targets to horizontal overflow. */
    @Test
    fun narrowCompressedViewportsUseSpareWidthAndRetainTheLargeTextMinimum() {
        val toolbar = composerToolbarCompactWidth(360f, 13, 52f, 144f)
        assertEquals(144f, toolbar, 0f)
        assertTrue(toolbar + 52f + 144f + 8f <= 360f)
    }

    /** Avoids losing actions during the initial unconstrained measurement. */
    @Test
    fun unboundedInitialMeasurementRetainsEveryConfiguredTarget() {
        assertEquals(468f, composerToolbarCompactWidth(Float.POSITIVE_INFINITY, 13, 48f, 96f), 0f)
    }
}
