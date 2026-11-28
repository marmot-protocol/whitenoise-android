package dev.ipf.whitenoise.android.media.pdf

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/** Same-length zlib rewriting for compressed metadata and object streams (#3377). */
class PdfFlateTest {
    @Test
    fun encodesExactlyTheRequestedLengthAndDecodesToPrefixPaddingAndSuffix() {
        val prefix = "1 0 2 15 << /Author () >> << /Producer () >>".toByteArray()
        val suffix = "<?xpacket end=\"w\"?>".toByteArray()
        listOf(80, 200, 4_000, 70_000, 200_000).forEach { length ->
            val encoded = checkNotNull(PdfFlate.encodeExactly(prefix, suffix, length)) { "$length" }

            assertEquals(length, encoded.size)
            val decoded = InflaterInputStream(encoded.inputStream()).readBytes()
            assertArrayEquals(prefix, decoded.copyOf(prefix.size))
            assertArrayEquals(suffix, decoded.copyOfRange(decoded.size - suffix.size, decoded.size))
            assertEquals(' '.code.toByte(), decoded[prefix.size])
            assertArrayEquals(decoded, PdfFlate.inflate(encoded, decoded.size))
        }
    }

    @Test
    fun refusesALengthTooShortForTheContent() {
        assertNull(PdfFlate.encodeExactly(ByteArray(4_000) { it.toByte() }, ByteArray(0), 100))
    }

    @Test
    fun standardEmptyZlibMemberInflatesAtZeroLimit() {
        val compressed = ByteArrayOutputStream().apply { DeflaterOutputStream(this).close() }.toByteArray()
        assertArrayEquals(ByteArray(0), PdfFlate.inflate(compressed, 0))
    }

    @Test
    fun inflationStopsAtItsLimitAndOnBrokenData() {
        val encoded = checkNotNull(PdfFlate.encodeExactly(ByteArray(10_000), ByteArray(0), 10_100))

        assertNull(PdfFlate.inflate(encoded, 5_000))
        assertNull(PdfFlate.inflate(encoded.copyOf(encoded.size / 2), Int.MAX_VALUE))
        assertNull(PdfFlate.inflate("not zlib".toByteArray(), Int.MAX_VALUE))
    }
}
