package dev.ipf.darkmatter.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for the thumbhash decoder via the byte-level entry
 * point (`Thumbhash.decodeRgba`) — no Android runtime dependencies.
 *
 * These cover the structural cases (short input, header parsing,
 * landscape/portrait dimension derivation, alpha channel). Wire-format
 * golden-vector coverage lives in [ThumbhashGoldenVectorTest], which
 * locks the encoder's bytes for real photographic inputs.
 */
class ThumbhashTest {
    @Test
    fun decodeRgba_returnsNullOnShortInput() {
        assertNull(Thumbhash.decodeRgba(ByteArray(0)))
        assertNull(Thumbhash.decodeRgba(byteArrayOf(0x00, 0x01, 0x02, 0x03)))
    }

    @Test
    fun decodeRgba_returnsNullOnOversizedInput() {
        // Defends against an attacker-supplied imeta `thumbhash` tag that's
        // megabytes long. Real hashes top out around 25 bytes.
        assertNull(Thumbhash.decodeRgba(ByteArray(65)))
        assertNull(Thumbhash.decodeRgba(ByteArray(4096)))
    }

    @Test
    fun decodeRgba_neutralGrayPortraitHashProducesGrayPixels() {
        // Hand-constructed hash: portrait, no alpha, mid-gray DC, zero AC scale.
        // - lDc=32/63≈0.508, pDc≈0, qDc≈0  (mid gray)
        // - lScale=0, pScale=0, qScale=0   (AC contribution collapses to 0)
        // - lMaxBits=5, hasAlpha=0, isLandscape=0 → lx=5, ly=7
        // header24 = 32 | (32<<6) | (32<<12) = 0x020820  (LE: 20 08 02)
        // header16 = 5                                    (LE: 05 00)
        val bytes =
            byteArrayOf(
                0x20,
                0x08,
                0x02,
                0x05,
                0x00,
                // 16 bytes (32 nibbles) of AC; values irrelevant at scale=0.
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
            )
        val decoded = Thumbhash.decodeRgba(bytes)
        assertNotNull("decoder rejected a well-formed portrait hash", decoded)
        decoded!!
        // Portrait → height pinned at 32, width = round(32 * lx/ly) = round(32*5/7) = 23.
        assertEquals(23, decoded.width)
        assertEquals(32, decoded.height)
        // Sample the center pixel — DC term dominates with zero AC scale, so
        // every pixel should be near (127, 127, 127). Allow ±8 for rounding
        // and float drift in the cosine basis.
        val center = decoded.pixels[(decoded.height / 2) * decoded.width + decoded.width / 2]
        val a = (center ushr 24) and 0xFF
        val r = (center ushr 16) and 0xFF
        val g = (center ushr 8) and 0xFF
        val b = center and 0xFF
        assertEquals("alpha", 255, a)
        assertTrue("red near gray, got $r", kotlin.math.abs(r - 127) <= 8)
        assertTrue("green near gray, got $g", kotlin.math.abs(g - 127) <= 8)
        assertTrue("blue near gray, got $b", kotlin.math.abs(b - 127) <= 8)
    }

    @Test
    fun decodeRgba_whiteHashProducesNearWhite() {
        // lDc=63/63=1.0 (full luminance), pDc=qDc≈0, AC scales = 0.
        // lMaxBits=3 → lx=3, ly=7. Output: 14x32 portrait of ~white.
        // header24 = 63 | (32<<6) | (32<<12) = 0x02083F  (LE: 3F 08 02)
        // header16 = 3                                    (LE: 03 00)
        val bytes =
            byteArrayOf(
                0x3F,
                0x08,
                0x02,
                0x03,
                0x00,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
            )
        val decoded = Thumbhash.decodeRgba(bytes)
        assertNotNull(decoded)
        decoded!!
        val center = decoded.pixels[(decoded.height / 2) * decoded.width + decoded.width / 2]
        val r = (center ushr 16) and 0xFF
        val g = (center ushr 8) and 0xFF
        val b = center and 0xFF
        // Bias for white but tolerate clamp + float drift.
        assertTrue("red high, got $r", r >= 240)
        assertTrue("green high, got $g", g >= 240)
        assertTrue("blue high, got $b", b >= 240)
    }

    @Test
    fun decodeRgba_landscapeHashProducesLandscapeBitmap() {
        // isLandscape=1 → lx = 7 (no alpha), ly = lMaxBits (= 4 here).
        // header24: lDc=32, pDc=32, qDc=32, lScale=0, hasAlpha=0 → 0x020820
        // header16: lMaxBits=4, pScale=0, qScale=0, isLandscape=1
        //   = 4 | (1 << 15) = 0x8004  (LE: 04 80)
        val bytes =
            byteArrayOf(
                0x20,
                0x08,
                0x02,
                0x04,
                0x80.toByte(),
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
            )
        val decoded = Thumbhash.decodeRgba(bytes)
        assertNotNull(decoded)
        decoded!!
        // Landscape: width pinned at 32, height = round(32 * ly/lx) = round(32*4/7) = 18.
        assertEquals(32, decoded.width)
        assertEquals(18, decoded.height)
        assertTrue("landscape ratio holds", decoded.width > decoded.height)
    }

    @Test
    fun decodeRgba_pixelArrayCoversFullBitmap() {
        val bytes =
            byteArrayOf(
                0x20,
                0x08,
                0x02,
                0x05,
                0x00,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
            )
        val decoded = Thumbhash.decodeRgba(bytes)!!
        assertEquals(decoded.width * decoded.height, decoded.pixels.size)
        // No transparent pixels — alpha was forced to 1.0 by hasAlpha=0.
        for (px in decoded.pixels) {
            assertEquals(255, (px ushr 24) and 0xFF)
        }
    }
}
