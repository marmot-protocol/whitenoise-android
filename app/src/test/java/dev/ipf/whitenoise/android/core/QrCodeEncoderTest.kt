package dev.ipf.whitenoise.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeEncoderTest {
    private val sampleNpub = "npub1" + "a".repeat(58)

    @Test
    fun rejectsOversizeDimensionBeforeAllocating() {
        // #169: a size whose square overflows Int (50_000² ≈ 2.5e9 > Int.MAX)
        // must be rejected up front, not turned into a negative IntArray length.
        assertThrows(IllegalArgumentException::class.java) {
            QrCodeEncoder.pixels(content = "npub1abc", size = 50_000, onColor = 1, offColor = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            QrCodeEncoder.matrix("npub1abc", size = QrCodeEncoder.MAX_QR_DIMENSION + 1)
        }
    }

    @Test
    fun createsSquareQrMatricesForProfileLinks() {
        val matrix = QrCodeEncoder.matrix(ProfileLink(sampleNpub).uri, size = 128)

        assertEquals(128, matrix.width)
        assertEquals(128, matrix.height)
        assertTrue((0 until matrix.width).any { x -> (0 until matrix.height).any { y -> matrix[x, y] } })
    }

    @Test
    fun createsPackedQrPixelsForBitmapRendering() {
        val pixels =
            QrCodeEncoder.pixels(
                content = ProfileLink(sampleNpub).uri,
                size = 128,
                onColor = 1,
                offColor = 0,
            )

        assertEquals(128 * 128, pixels.size)
        assertTrue(pixels.any { it == 1 })
        assertTrue(pixels.any { it == 0 })
    }
}
