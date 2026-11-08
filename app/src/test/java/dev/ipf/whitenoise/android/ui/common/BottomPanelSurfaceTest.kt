package dev.ipf.whitenoise.android.ui.common

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Panels docked above the navigation bar or keyboard keep composer-sized bottom corners, and move in from
 * the screen sides only when a rounded display corner would otherwise cut into them.
 */
class BottomPanelSurfaceTest {
    /** The bottom corners match the composer pill. */
    @Test
    fun bottomRadiusMatchesComposer() {
        assertEquals(24.dp, BottomPanelBottomRadius)
    }

    /** Without a reported display corner the panel stays full width. */
    @Test
    fun squareDisplayKeepsFullWidth() {
        assertEquals(0.dp, bottomPanelHorizontalInset(displayCornerRadius = 0.dp, bottomGap = 0.dp))
    }

    /** A panel above a three-button navigation bar sits above the display curve and stays full width. */
    @Test
    fun panelAboveDisplayCurveKeepsFullWidth() {
        assertEquals(0.dp, bottomPanelHorizontalInset(displayCornerRadius = 40.dp, bottomGap = 48.dp))
    }

    /** Over a gesture bar a large display corner pulls the panel in just enough for its corners to fit. */
    @Test
    fun gestureBarPanelMovesInsideDisplayCurve() {
        assertEquals(8.41f, bottomPanelHorizontalInset(displayCornerRadius = 50.dp, bottomGap = 24.dp).value, 0.01f)
    }

    /** Edge to edge with no bottom inset, the panel corner sits fully inside the display curve. */
    @Test
    fun flushPanelMovesInsideDisplayCurve() {
        assertEquals(52.5f, bottomPanelHorizontalInset(displayCornerRadius = 50.dp, bottomGap = 0.dp).value, 0.01f)
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
