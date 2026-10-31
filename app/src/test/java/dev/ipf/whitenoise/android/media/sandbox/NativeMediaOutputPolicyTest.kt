package dev.ipf.whitenoise.android.media.sandbox

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NativeMediaOutputPolicyTest {
    @Test
    fun scalarMetadataAcceptsAudioAndRotatedVideoWithoutIntegerTruncation() {
        assertEquals(2, NativeMediaOutputPolicy.info(longArrayOf(0, 0, 1000000, 0, 48000, 2)).channels)
        assertEquals(90, NativeMediaOutputPolicy.info(longArrayOf(1920, 1080, 1000000, 90, 48000, 2)).rotationDegrees)
        val malformed =
            listOf(
                longArrayOf(1L shl 32, 1, 0, 0, 0, 0),
                longArrayOf(1920, 0, 0, 0, 0, 0),
                longArrayOf(32768, 32768, 0, 0, 0, 0),
                longArrayOf(1920, 1080, -1, 0, 0, 0),
                longArrayOf(1, 1, 1, 45, 0, 0),
                longArrayOf(0, 0, 1, 0, 192000, 2),
                longArrayOf(0, 0, 1, 0, 48000, 0),
            )
        malformed.forEach { values ->
            assertThrows(IllegalArgumentException::class.java) { NativeMediaOutputPolicy.info(values) }
        }
    }

    @Test
    fun decodedBuffersRequireExactPixelsAndWholeInterleavedPcmFrames() {
        assertEquals(16, NativeMediaOutputPolicy.frame(longArrayOf(1, 2, 2, 0, 0, 0, 16), 2).bytes)
        assertEquals(4, NativeMediaOutputPolicy.frame(longArrayOf(2, 0, 0, 48000, 2, 0, 4), 1).bytes)
        val malformed =
            listOf(
                longArrayOf(1, 2, 2, 0, 0, 0, 15),
                longArrayOf(1, 4097, 1, 0, 0, 0, 16388),
                longArrayOf(2, 0, 0, 48000, 2, 0, 3),
                longArrayOf(2, 0, 0, 48000, 2, 0, NativeMediaOutputPolicy.PCM_CAPACITY.toLong() + 4),
                longArrayOf(0, 0, 0, 0, 0, 0, 4),
            )
        malformed.forEach { values ->
            assertThrows(IllegalArgumentException::class.java) { NativeMediaOutputPolicy.frame(values, 2) }
        }
    }

    @Test
    fun eofIrrelevantFieldsAndPrerollMustMatchTheNativeProtocol() {
        assertEquals(0, NativeMediaOutputPolicy.frame(longArrayOf(0, 0, 0, 0, 0, 0, 0), 1).kind)
        assertEquals(-10000000L, NativeMediaOutputPolicy.frame(longArrayOf(2, 0, 0, 48000, 1, -10000000, 2), 1).ptsUs)
        val malformed =
            listOf(
                longArrayOf(0, 1, 0, 0, 0, 0, 0),
                longArrayOf(0, 0, 0, 0, 0, 1, 0),
                longArrayOf(1, 1, 1, 48000, 1, 0, 4),
                longArrayOf(2, 1, 0, 48000, 1, 0, 2),
                longArrayOf(2, 0, 0, 48000, 1, -10000001, 2),
            )
        malformed.forEach { values ->
            assertThrows(IllegalArgumentException::class.java) { NativeMediaOutputPolicy.frame(values, 1) }
        }
    }

    @Test
    fun rgbaRotationPreservesOpaqueByteOrderAndAllNonSquarePixels() {
        val source = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        repeat(6) { source.putInt(it * 4, it + 1) }
        val rotations =
            listOf(
                90 to intArrayOf(5, 3, 1, 6, 4, 2),
                180 to intArrayOf(6, 5, 4, 3, 2, 1),
                270 to intArrayOf(2, 4, 6, 1, 3, 5),
            )
        for ((degrees, expected) in rotations) {
            val target = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN)
            NativeMediaPixels.rotate(source, target, 2, 3, degrees)
            val result = target.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            assertArrayEquals(expected, IntArray(6) { result.getInt(it * 4) })
        }
    }
}
