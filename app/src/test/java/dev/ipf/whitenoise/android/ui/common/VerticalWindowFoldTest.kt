package dev.ipf.whitenoise.android.ui.common

import android.graphics.Rect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Which folds split the window into two panes (#3685). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VerticalWindowFoldTest {
    private val density = Density(2f)

    /** A book-style foldable held half open splits the window at the hinge, in dp from the left. */
    @Test
    fun halfOpenVerticalFoldSplitsTheWindow() {
        val fold = fold(left = 800, right = 800, separating = true)

        assertEquals(VerticalWindowFold(400.dp, 400.dp), separatingVerticalFold(listOf(fold), WINDOW_PX, density))
    }

    /** A hinge with a physical gap keeps both edges so neither pane is drawn under it. */
    @Test
    fun occludingHingeKeepsItsWidth() {
        val hinge = fold(left = 780, right = 820, separating = true)

        assertEquals(VerticalWindowFold(390.dp, 410.dp), separatingVerticalFold(listOf(hinge), WINDOW_PX, density))
    }

    /** Opened flat, a foldable is one screen and keeps the regular layout. */
    @Test
    fun flatFoldIsIgnored() {
        assertNull(separatingVerticalFold(listOf(fold(800, 800, separating = false)), WINDOW_PX, density))
    }

    /** A horizontal fold (tabletop) does not split the width. */
    @Test
    fun horizontalFoldIsIgnored() {
        val tabletop = fold(0, WINDOW_PX, separating = true, foldOrientation = FoldingFeature.Orientation.HORIZONTAL)

        assertNull(separatingVerticalFold(listOf(tabletop), WINDOW_PX, density))
    }

    /** A side narrower than a usable pane keeps the regular layout instead of a cramped pane. */
    @Test
    fun foldNearAnEdgeIsIgnored() {
        val narrowStart = MinWindowFoldSideWidth.value.toInt() * 2 - 2

        assertNull(separatingVerticalFold(listOf(fold(narrowStart, narrowStart, true)), WINDOW_PX, density))
        val narrowEnd = WINDOW_PX - narrowStart
        assertNull(separatingVerticalFold(listOf(fold(narrowEnd, narrowEnd, true)), WINDOW_PX, density))
    }

    private fun fold(
        left: Int,
        right: Int,
        separating: Boolean,
        foldOrientation: FoldingFeature.Orientation = FoldingFeature.Orientation.VERTICAL,
    ): FoldingFeature =
        object : FoldingFeature {
            override val bounds = Rect(left, 0, right, WINDOW_HEIGHT_PX)
            override val isSeparating = separating
            override val occlusionType =
                if (right > left) FoldingFeature.OcclusionType.FULL else FoldingFeature.OcclusionType.NONE
            override val orientation = foldOrientation
            override val state =
                if (separating) FoldingFeature.State.HALF_OPENED else FoldingFeature.State.FLAT
        }

    private companion object {
        const val WINDOW_PX = 1600
        const val WINDOW_HEIGHT_PX = 1400
    }
}
