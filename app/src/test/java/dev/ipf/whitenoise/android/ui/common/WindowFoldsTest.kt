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

/** Which folds and hinges shape the layout (#3685). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WindowFoldsTest {
    private val density = Density(2f)

    /** A book-style foldable held half open reports its hinge in dp from the left. */
    @Test
    fun halfOpenBookFoldIsUpright() {
        val folds = folds(upright(800, 800, separating = true))

        assertEquals(listOf(WindowFold(400.dp, 400.dp, separating = true)), folds.upright)
        assertNull(folds.tabletop)
    }

    /** A flat fold is kept, unseparated, so the panes may line up with its crease. */
    @Test
    fun flatBookFoldMarksTheCrease() {
        assertEquals(listOf(WindowFold(400.dp, 400.dp, separating = false)), folds(upright(800, 800, false)).upright)
    }

    /** A hinge with a physical gap keeps both edges so neither pane is drawn under it. */
    @Test
    fun occludingHingeKeepsItsWidth() {
        assertEquals(listOf(WindowFold(390.dp, 410.dp, separating = true)), folds(upright(780, 820, true)).upright)
    }

    /** A tri-fold's two hinges are both reported, left to right. */
    @Test
    fun triFoldReportsBothHinges() {
        val folds = folds(upright(1300, 1300, true), upright(650, 650, false), width = 1950)

        assertEquals(listOf(325.dp, 650.dp), folds.upright.map { it.start })
    }

    /** A flip phone held half open is in the tabletop posture. */
    @Test
    fun halfOpenFlipPhoneIsTabletop() {
        val folds = folds(horizontal(900, 900, separating = true))

        assertEquals(WindowFold(450.dp, 450.dp, separating = true), folds.tabletop)
        assertEquals(emptyList<WindowFold>(), folds.upright)
    }

    /** Opened flat, a flip phone is one tall screen. */
    @Test
    fun flatFlipPhoneHasNoFolds() {
        assertEquals(WindowFolds.None, folds(horizontal(900, 900, separating = false)))
    }

    /** A fold too close to an edge leaves no usable pane, so the regular layout stays. */
    @Test
    fun foldsNearAnEdgeAreIgnored() {
        val narrow = MinWindowFoldSideWidth.value.toInt() * 2 - 2
        val short = MinTabletopHalfHeight.value.toInt() * 2 - 2

        assertEquals(WindowFolds.None, folds(upright(narrow, narrow, true)))
        assertEquals(WindowFolds.None, folds(upright(WIDTH - narrow, WIDTH - narrow, true)))
        assertEquals(WindowFolds.None, folds(horizontal(short, short, true)))
        assertEquals(WindowFolds.None, folds(horizontal(HEIGHT - short, HEIGHT - short, true)))
    }

    private fun folds(
        vararg features: FoldingFeature,
        width: Int = WIDTH,
    ) = windowFolds(features.toList(), windowWidthPx = width, windowHeightPx = HEIGHT, density = density)

    private fun upright(
        left: Int,
        right: Int,
        separating: Boolean,
    ) = feature(Rect(left, 0, right, HEIGHT), separating, FoldingFeature.Orientation.VERTICAL)

    private fun horizontal(
        top: Int,
        bottom: Int,
        separating: Boolean,
    ) = feature(Rect(0, top, WIDTH, bottom), separating, FoldingFeature.Orientation.HORIZONTAL)

    private fun feature(
        rect: Rect,
        separating: Boolean,
        foldOrientation: FoldingFeature.Orientation,
    ): FoldingFeature =
        object : FoldingFeature {
            override val bounds = rect
            override val isSeparating = separating
            override val occlusionType =
                if (rect.width() > 0 && rect.height() > 0) {
                    FoldingFeature.OcclusionType.FULL
                } else {
                    FoldingFeature.OcclusionType.NONE
                }
            override val orientation = foldOrientation
            override val state = if (separating) FoldingFeature.State.HALF_OPENED else FoldingFeature.State.FLAT
        }

    private companion object {
        const val WIDTH = 1600
        const val HEIGHT = 1800
    }
}
