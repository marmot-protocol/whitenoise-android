package dev.ipf.whitenoise.android.ui.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class QrFrameDecoderTest {
    @Test
    fun decodesQrPayloadsWithRotationAndInvertedLuminance() {
        val payload = "nostr:npub1test-" + "0123456789abcdef".repeat(24)
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 640, 480, mapOf(EncodeHintType.MARGIN to 4))
        val normal = ByteArray(matrix.width * matrix.height) { index ->
            if (matrix[index % matrix.width, index / matrix.width]) 0 else 255.toByte()
        }
        for (rotation in listOf(0, 90, 180, 270)) {
            assertEquals(payload, QrFrameDecoder().decode(normal, matrix.width, matrix.height, rotation))
            val inverted = ByteArray(normal.size) { index -> (255 - (normal[index].toInt() and 255)).toByte() }
            assertEquals(payload, QrFrameDecoder().decode(inverted, matrix.width, matrix.height, rotation))
        }
    }

    @Test
    fun copiesCroppedPaddedPixelsWithoutMovingCameraBufferPosition() {
        val data = ByteArray(44) { it.toByte() }
        val buffer = ByteBuffer.wrap(data).apply { position(2) }
        val cropped = copyQrLuminance(buffer, 12, 2, QrLuminanceCrop(1, 1, 3, 2))
        assertArrayEquals(byteArrayOf(16, 18, 20, 28, 30, 32), cropped)
        assertEquals(2, buffer.position())
    }

    @Test
    fun rejectsTruncatedOrOverflowingFramesAndUnsupportedRotation() {
        assertThrows(IllegalArgumentException::class.java) {
            copyQrLuminance(ByteBuffer.allocate(8), 8, 1, QrLuminanceCrop(0, 1, 8, 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            copyQrLuminance(ByteBuffer.allocate(8), Int.MAX_VALUE, Int.MAX_VALUE, QrLuminanceCrop(1, 1, 2, 2))
        }
        assertThrows(IllegalArgumentException::class.java) {
            QrFrameDecoder().decode(ByteArray(4), 2, 2, 45)
        }
        assertNull(QrFrameDecoder().decode(ByteArray(256) { 255.toByte() }, 16, 16, 0))
    }
}
