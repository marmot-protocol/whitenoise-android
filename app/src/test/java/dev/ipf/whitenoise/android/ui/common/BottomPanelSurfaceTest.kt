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

    /** A small display corner that ends below the panel's own curve leaves the design radius in place. */
    @Test
    fun smallDisplayCornerKeepsDesignRadius() {
        assertEquals(28.dp, bottomPanelBottomCornerRadius(displayCornerRadius = 20.dp, bottomGap = 24.dp))
    }

    /**
     * A display corner whose smooth curve reaches above the panel's side wall pushes the panel's bend higher
     * than the reported radius alone, so the two curves do not meet where the wall starts to bend.
     */
    @Test
    fun largeDisplayCornerClearsItsWholeCurve() {
        assertEquals(52.5f, bottomPanelBottomCornerRadius(displayCornerRadius = 50.dp, bottomGap = 24.dp).value, 0.01f)
    }

    /** Edge to edge with no bottom inset, the panel clears the full display curve. */
    @Test
    fun flushPanelClearsDisplayCurve() {
        assertEquals(91.8f, bottomPanelBottomCornerRadius(displayCornerRadius = 60.dp, bottomGap = 0.dp).value, 0.01f)
    }

    /** A panel tall enough for both keeps both radii. */
    @Test
    fun tallPanelKeepsBothRadii() {
        assertEquals(
            28f to 40f,
            bottomPanelCornerRadiiPx(width = 400f, height = 300f, topRadius = 28f, bottomRadius = 40f),
        )
    }

    /** A short bar keeps its bottom radius and gives up top radius instead of scaling every corner down. */
    @Test
    fun shortPanelKeepsBottomRadius() {
        assertEquals(
            16f to 40f,
            bottomPanelCornerRadiiPx(width = 400f, height = 56f, topRadius = 28f, bottomRadius = 40f),
        )
    }

    /** A bottom radius taller than the panel is capped by the height. */
    @Test
    fun bottomRadiusCappedByHeight() {
        assertEquals(
            0f to 56f,
            bottomPanelCornerRadiiPx(width = 400f, height = 56f, topRadius = 28f, bottomRadius = 90f),
        )
    }
}
