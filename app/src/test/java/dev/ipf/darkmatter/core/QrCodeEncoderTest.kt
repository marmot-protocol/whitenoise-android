package dev.ipf.darkmatter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeEncoderTest {
    @Test
    fun createsSquareQrMatricesForProfileLinks() {
        val matrix = QrCodeEncoder.matrix(ProfileLink("npub1abc").uri, size = 128)

        assertEquals(128, matrix.width)
        assertEquals(128, matrix.height)
        assertTrue((0 until matrix.width).any { x -> (0 until matrix.height).any { y -> matrix[x, y] } })
    }
}
