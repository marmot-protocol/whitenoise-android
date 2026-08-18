package dev.ipf.whitenoise.android.fuzz

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ByteArrayFuzzedDataProviderTest {
    @Test
    fun consumeInt_matchesNativeFullRangeWithShortInput() {
        val provider = ByteArrayFuzzedDataProvider(byteArrayOf(0))

        assertEquals(0, provider.consumeInt())
    }

    @Test
    fun consumeLong_validatesRangeAndSamplesInclusiveBounds() {
        val minProvider = ByteArrayFuzzedDataProvider(byteArrayOf(0))
        assertEquals(7L, minProvider.consumeLong(7L, 7L))
        assertEquals(1, minProvider.remainingBytes())

        val maxProvider = ByteArrayFuzzedDataProvider(byteArrayOf(0x09))
        assertEquals(9L, maxProvider.consumeLong(7L, 9L))

        val fullRangeProvider = ByteArrayFuzzedDataProvider(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        assertEquals(0x0807060504030201UL.toLong(), fullRangeProvider.consumeLong(Long.MIN_VALUE, Long.MAX_VALUE))

        val nearMaxProvider = ByteArrayFuzzedDataProvider(byteArrayOf(0xFF.toByte()))
        assertEquals(Long.MAX_VALUE, nearMaxProvider.consumeLong(Long.MAX_VALUE - 1, Long.MAX_VALUE))

        assertThrows(IllegalArgumentException::class.java) {
            ByteArrayFuzzedDataProvider(byteArrayOf(0)).consumeLong(1, 0)
        }
    }

    @Test
    fun consumeAsciiString_mapsEveryByteToSevenBitAscii() {
        val bytes =
            byteArrayOf(
                'a'.code.toByte(),
                0xC3.toByte(),
                0xA9.toByte(),
                'b'.code.toByte(),
            )
        val provider = ByteArrayFuzzedDataProvider(bytes)
        assertEquals("aC)b", provider.consumeAsciiString(4))
        assertEquals(0, provider.remainingBytes())
    }

    @Test
    fun consumeRemainingAsAsciiString_mapsEveryByteToSevenBitAscii() {
        val bytes =
            byteArrayOf(
                'x'.code.toByte(),
                0x80.toByte(),
                'y'.code.toByte(),
            )
        val provider = ByteArrayFuzzedDataProvider(bytes)
        assertEquals("x\u0000y", provider.consumeRemainingAsAsciiString())
        assertEquals(0, provider.remainingBytes())
    }
}
