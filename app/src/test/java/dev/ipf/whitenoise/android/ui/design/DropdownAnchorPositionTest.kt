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
        assertEquals(IntOffset(0, 155), position(IntRect(-150, 100, -100, 150)))
    }

    private fun position(
        anchor: IntRect,
        direction: LayoutDirection = LayoutDirection.Ltr,
    ) = provider.calculatePosition(anchor, IntSize(400, 700), direction, IntSize(120, 200))
}
