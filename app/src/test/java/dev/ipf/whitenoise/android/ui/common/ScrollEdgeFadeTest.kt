package dev.ipf.whitenoise.android.ui.common

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Physical edges and tiny viewport readability, independent of theme and scroll-container type. */
class ScrollEdgeFadeTest {
    /** Reverse layout changes physical edges, not merely the name of a logical scroll direction. */
    @Test
    fun reverseLayoutMapsLogicalDirectionsToPhysicalEdges() {
        assertEquals(ScrollEdgeFadeState(false, true), scrollEdgeFadeState(false, true))
        assertEquals(ScrollEdgeFadeState(true, false), scrollEdgeFadeState(false, true, true))
        assertEquals(ScrollEdgeFadeState(false, true), scrollEdgeFadeState(true, false, true))
        assertEquals(ScrollEdgeFadeState(true, true), scrollEdgeFadeState(true, true, true))
        assertEquals(ScrollEdgeFadeState(false, false), scrollEdgeFadeState(false, false, true))
    }

    /** Even a tiny dialog/editor keeps at least its middle half fully opaque. */
    @Test
    fun smallViewportLimitsBothFadeBands() {
        assertEquals(
            listOf(0f to Color.Transparent, 0.25f to Color.Black, 0.75f to Color.Black, 1f to Color.Transparent),
            verticalEdgeFadeStops(40f, 28f, 28f, maxBandFraction = 0.25f),
        )
    }

    /** A fitting viewport leaves every pixel untouched. */
    @Test
    fun fittingAndUnmeasuredContentHaveNoMask() {
        assertNull(verticalEdgeFadeStops(0f, 28f, 28f, maxBandFraction = 0.25f))
        assertNull(verticalEdgeFadeStops(400f, 0f, 0f, maxBandFraction = 0.25f))
    }

    /** The measured composer keeps its original proportional overlap geometry. */
    @Test
    fun defaultPolicyPreservesUnequalLegacyBands() {
        val stops = verticalEdgeFadeStops(100f, 1000f, 100f)!!
        assertEquals(1000f / 1100f, stops[1].first, 0.00001f)
        assertEquals(1000f / 1100f, stops[2].first, 0.00001f)
    }
}
