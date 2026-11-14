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

    /** The end group beside Send is exactly as wide as its actions, leaving no gap before Send. */
    @Test
    fun expandedEndGroupHugsSendWithOnlyItsOwnColumns() {
        assertEquals(72f, composerExpandedTrailingWidth(300f, 2, 2), 0f)
        assertEquals(36f, composerExpandedTrailingWidth(300f, 0, 1), 0f)
        assertEquals(0f, composerExpandedTrailingWidth(300f, 3, 0), 0f)
    }

    /** A crowded end group wraps on whole columns, so no partial-column remainder sits before Send. */
    @Test
    fun expandedEndGroupWrapsOnWholeColumnsWithinItsShare() {
        val width = composerExpandedTrailingWidth(200f, 4, 6)
        assertEquals(108f, width, 0f)
        assertEquals(0f, width % 36f, 0f)
        assertEquals(36f, composerExpandedTrailingWidth(40f, 1, 3), 0f)
    }
}
