package dev.ipf.whitenoise.android.ui.common

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Panels docked above the navigation bar or keyboard keep the 28 dp design radius, and grow their bottom
 * corners only when a rounded display corner would otherwise cut into them.
 */
class BottomPanelSurfaceTest {
    /** Without a reported display corner the panel keeps its design radius. */
    @Test
    fun squareDisplayKeepsDesignRadius() {
        assertEquals(28.dp, bottomPanelBottomCornerRadius(displayCornerRadius = 0.dp, bottomGap = 0.dp))
    }

    /** A navigation bar taller than the remaining corner curve leaves the design radius in place. */
    @Test
    fun gestureBarAboveCornerCurveKeepsDesignRadius() {
        assertEquals(28.dp, bottomPanelBottomCornerRadius(displayCornerRadius = 50.dp, bottomGap = 24.dp))
    }

    /** Edge to edge with no bottom inset, the panel follows the full display corner. */
    @Test
    fun flushPanelFollowsDisplayCorner() {
        assertEquals(60.dp, bottomPanelBottomCornerRadius(displayCornerRadius = 60.dp, bottomGap = 0.dp))
    }

    /** A short gap leaves only part of the display curve to clear. */
    @Test
    fun shortGapClearsRemainingCurve() {
        assertEquals(40.dp, bottomPanelBottomCornerRadius(displayCornerRadius = 50.dp, bottomGap = 10.dp))
    }
}
