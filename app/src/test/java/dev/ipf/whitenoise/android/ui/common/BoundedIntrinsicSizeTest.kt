package dev.ipf.whitenoise.android.ui.common

import androidx.compose.ui.unit.Constraints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the packed dimension boundary independently of fonts and device density. */
class BoundedIntrinsicSizeTest {
    /** The release's exact width fails before an ordinary parent can constrain it. */
    @Test
    fun reportedWidthIsClampedBeforePacking() {
        assertThrows(IllegalArgumentException::class.java) { Constraints.fixedWidth(1_276_071) }
        val parent = Constraints(maxWidth = 360)
        assertEquals(Constraints.fixedWidth(360), boundedIntrinsicWidthConstraints(parent, 1_276_071))
    }

    /** Both axes retain their natural size and incoming minima for ordinary content. */
    @Test
    fun ordinarySizesRespectTheParentRange() {
        val parent = Constraints(minWidth = 40, maxWidth = 360, minHeight = 20, maxHeight = 800)
        for (natural in listOf(Int.MIN_VALUE, -1, 0, 25, 80, 300, 1_276_071, Int.MAX_VALUE)) {
            val width = boundedIntrinsicWidthConstraints(parent, natural)
            val height = boundedIntrinsicHeightConstraints(parent, natural)
            assertEquals(natural.coerceIn(40, 360), width.minWidth)
            assertEquals(width.minWidth, width.maxWidth)
            assertEquals(parent.minHeight, width.minHeight)
            assertEquals(parent.maxHeight, width.maxHeight)
            assertEquals(natural.coerceIn(20, 800), height.minHeight)
            assertEquals(height.minHeight, height.maxHeight)
            assertEquals(parent.minWidth, height.minWidth)
            assertEquals(parent.maxWidth, height.maxWidth)
        }
    }

    /** Infinity returned by a child is a desired size, never an unbounded fixed dimension. */
    @Test
    fun unboundedParentsStillProduceFiniteFixedIntrinsicDimensions() {
        for (natural in listOf(1_276_071, Int.MAX_VALUE - 1, Int.MAX_VALUE)) {
            val width = boundedIntrinsicWidthConstraints(Constraints(), natural)
            val height = boundedIntrinsicHeightConstraints(Constraints(), natural)
            assertTrue(width.hasFixedWidth && width.hasBoundedWidth)
            assertTrue(height.hasFixedHeight && height.hasBoundedHeight)
            assertTrue(width.maxWidth in 1 until natural)
            assertEquals(width.maxWidth, height.maxHeight)
            assertEquals(Constraints.Infinity, width.maxHeight)
            assertEquals(Constraints.Infinity, height.maxWidth)
        }
    }

    /** A large orthogonal dimension leaves fewer packed bits for the intrinsic dimension. */
    @Test
    fun competingDimensionsRemainRepresentable() {
        for (other in listOf(0, 8_190, 8_191, 32_766, 32_767, 65_534, 65_535, 200_000)) {
            val width = boundedIntrinsicWidthConstraints(Constraints(maxHeight = other), Int.MAX_VALUE)
            val height = boundedIntrinsicHeightConstraints(Constraints(maxWidth = other), Int.MAX_VALUE)
            assertEquals(other, width.maxHeight)
            assertEquals(other, height.maxWidth)
            assertTrue(width.hasFixedWidth && width.hasBoundedWidth)
            assertTrue(height.hasFixedHeight && height.hasBoundedHeight)
            assertEquals(width.maxWidth, height.maxHeight)
        }
    }
}
