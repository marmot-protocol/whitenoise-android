package dev.ipf.whitenoise.android.ui.design

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class DropdownAnchorPositionTest {
    private val provider = DropdownAnchorPositionProvider(10, 5, 48)

    @Test fun normalAnchorUsesLogicalStartAndBelow() {
        assertEquals(IntOffset(110, 155), position(IntRect(100, 100, 200, 150)))
        assertEquals(IntOffset(70, 155), position(IntRect(100, 100, 200, 150), LayoutDirection.Rtl))
    }

    @Test fun bottomRightAnchorFallsAboveAndUsesEnd() {
        assertEquals(IntOffset(250, 405), position(IntRect(300, 600, 360, 640)))
    }

    @Test fun clippedAnchorUsesWindowEdgeCandidate() {
        assertEquals(IntOffset(10, 155), position(IntRect(-150, 100, -100, 150)))
    }

    @Test fun overflowingCenteredMenuUsesTheNearestWindowEdgeInBothDirections() {
        val centered = DropdownAnchorPositionProvider(0, 0, 48)
        for (direction in LayoutDirection.entries) {
            assertEquals(
                IntOffset(80, 150),
                centered.calculatePosition(
                    IntRect(148, 100, 212, 150),
                    IntSize(360, 700),
                    direction,
                    IntSize(280, 200),
                ),
            )
            assertEquals(
                IntOffset(0, 150),
                centered.calculatePosition(
                    IntRect(138, 100, 202, 150),
                    IntSize(360, 700),
                    direction,
                    IntSize(280, 200),
                ),
            )
        }
    }

    @Test fun mirroredHalfAnchorsUsePhysicalEdgesWithLogicalOffsets() {
        val offset = DropdownAnchorPositionProvider(10, 5, 48)
        for (direction in LayoutDirection.entries) {
            val expectedLeft = if (direction == LayoutDirection.Ltr) 10 else 0
            val expectedRight = if (direction == LayoutDirection.Ltr) 80 else 70
            assertEquals(
                IntOffset(expectedLeft, 155),
                offset.calculatePosition(
                    IntRect(138, 100, 202, 150),
                    IntSize(360, 700),
                    direction,
                    IntSize(280, 200),
                ),
            )
            assertEquals(
                IntOffset(expectedRight, 155),
                offset.calculatePosition(
                    IntRect(158, 100, 222, 150),
                    IntSize(360, 700),
                    direction,
                    IntSize(280, 200),
                ),
            )
        }
    }

    @Test fun oversizedMenuCentersInWindowWithoutMarginHeightCap() {
        val zero = DropdownAnchorPositionProvider(0, 0, 48)
        for (direction in LayoutDirection.entries) {
            assertEquals(
                IntOffset(-20, 0),
                zero.calculatePosition(
                    IntRect(148, 100, 212, 150),
                    IntSize(360, 480),
                    direction,
                    IntSize(400, 480),
                ),
            )
            assertEquals(
                IntOffset(148, 40),
                zero.calculatePosition(
                    IntRect(148, 100, 212, 150),
                    IntSize(360, 480),
                    direction,
                    IntSize(64, 400),
                ),
            )
        }
    }

    @Test fun belowCandidateIsValidatedBeforeClampingToMargin() {
        assertEquals(
            IntOffset(110, 48),
            provider.calculatePosition(
                IntRect(100, -100, 200, -50),
                IntSize(400, 700),
                LayoutDirection.Ltr,
                IntSize(120, 200),
            ),
        )
    }

    private fun position(
        anchor: IntRect,
        direction: LayoutDirection = LayoutDirection.Ltr,
    ) = provider.calculatePosition(anchor, IntSize(400, 700), direction, IntSize(120, 200))
}
