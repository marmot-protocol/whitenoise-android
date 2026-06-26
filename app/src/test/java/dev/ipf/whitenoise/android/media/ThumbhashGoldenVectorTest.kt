package dev.ipf.whitenoise.android.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-canonical golden-vector tests for the thumbhash encoder + decoder.
 *
 * Each fixture is a synthetic but non-trivial RGBA bitmap (uniform color,
 * vertical gradient, diagonal gradient) chosen to exercise:
 *  - the DC term (color baseline)
 *  - the AC term in one axis (vertical gradient → non-zero ly coefficients)
 *  - the AC term on both axes (diagonal gradient → non-zero lx/ly cross
 *    coefficients)
 *
 * Header bytes (the first five bytes of each hash) lock the packed DC terms,
 * scale fields, and dimension flags — the parts of the wire format that must
 * stay stable across platforms. AC nibbles can flip by one step at
 * quantization boundaries when `cos` differs slightly between CPU
 * architectures, so full-byte snapshots are intentionally avoided here.
 * Instead, each fixture is encoded and decoded back on the host JVM; the
 * decoded output must approximate the input within perceptual tolerance.
 */
class ThumbhashGoldenVectorTest {
    // -- Fixture A: solid orange 32x32 ----------------------------------------

    @Test
    fun encode_solidOrange_matchesWireHeader() {
        val hash = encodeSolidOrange()
        assertWireHeader(hash, SOLID_ORANGE_HEADER)
    }

    @Test
    fun decode_solidOrange_matchesInputCenter() {
        val decoded = Thumbhash.decodeRgba(encodeSolidOrange())
        assertNotNull(decoded)
        decoded!!
        val (r, g, b) = centerRgb(decoded)
        // Orange #FF8A00: R=255, G=138, B=0. Wide tolerance because
        // thumbhash is lossy by design.
        assertWithin("R", 255, r, 25)
        assertWithin("G", 138, g, 25)
        assertWithin("B", 0, b, 25)
    }

    // -- Fixture B: vertical gradient (dark top → bright bottom) 32x32 --------

    @Test
    fun encode_verticalGradient_matchesWireHeader() {
        val hash = encodeVerticalGradient()
        assertWireHeader(hash, VERTICAL_GRADIENT_HEADER)
    }

    @Test
    fun decode_verticalGradient_preservesAxis() {
        val decoded = Thumbhash.decodeRgba(encodeVerticalGradient())
        assertNotNull(decoded)
        decoded!!
        // Top should be darker than bottom — that's the ENTIRE point of
        // testing a vertical gradient. A decoder that loses ly AC terms
        // would render a flat gray here.
        // Sample edges, not interior, for the strongest ly contrast.
        val topLum = lumAt(decoded, decoded.width / 2, 0)
        val botLum = lumAt(decoded, decoded.width / 2, decoded.height - 1)
        // Thumbhash quantizes AC terms to 4 bits — a 0→255 input ramp
        // decodes to a softer ramp. Anything >40 luminance-sum delta
        // proves the ly axis information survived.
        assertTrue(
            "vertical gradient lost: top=$topLum bottom=$botLum",
            botLum - topLum > 40,
        )
    }

    // -- Fixture C: diagonal gradient (cyan TL → magenta BR) 32x32 ------------
    // Exercises non-trivial AC content on BOTH lx and ly axes plus chroma
    // (P and Q channels carry information, not just L).

    @Test
    fun encode_diagonalGradient_matchesWireHeader() {
        val hash = encodeDiagonalGradient()
        assertWireHeader(hash, DIAGONAL_GRADIENT_HEADER)
    }

    @Test
    fun decode_diagonalGradient_preservesDirection() {
        val decoded = Thumbhash.decodeRgba(encodeDiagonalGradient())
        assertNotNull(decoded)
        decoded!!
        // Top-left should read cyan-ish (low R), bottom-right magenta-ish
        // (high R). A decoder that loses cross-axis AC terms flattens the
        // diagonal into a single color and this fails.
        val (tlR, _, _) = pixelRgb(decoded, 0, 0)
        val (brR, _, _) = pixelRgb(decoded, decoded.width - 1, decoded.height - 1)
        // After 4-bit AC quantization a clean cyan→magenta diagonal
        // still preserves >30 R-channel delta. The point of the test is
        // direction, not amplitude.
        assertTrue(
            "diagonal direction lost: TL.r=$tlR BR.r=$brR",
            brR - tlR > 30,
        )
    }

    // -- Round-trip integrity -------------------------------------------------

    @Test
    fun encodeDecode_roundTrip_preservesAverageColor() {
        // A natural-content stand-in: a horizontal sunset stripe.
        val pixels =
            IntArray(32 * 32) { i ->
                val y = i / 32
                val r = (50 + y * 5).coerceAtMost(255)
                val g = (30 + y * 4).coerceAtMost(255)
                val b = (100 - y * 2).coerceAtLeast(0)
                argb(255, r, g, b)
            }
        val hash = Thumbhash.encodeFromRgba(32, 32, pixels)!!
        val decoded = Thumbhash.decodeRgba(hash)!!
        val (avgR, avgG, avgB) = averageRgb(decoded)
        val (srcR, srcG, srcB) = averageRgb(32, 32, pixels)
        assertWithin("avg R", srcR, avgR, 25)
        assertWithin("avg G", srcG, avgG, 25)
        assertWithin("avg B", srcB, avgB, 25)
    }

    // -------------------------------------------------------------------------
    // Header bytes — stable across platforms for each fixture. AC payload bytes
    // are verified indirectly via the decode assertions above.
    private companion object {
        val SOLID_ORANGE_HEADER =
            byteArrayOf(
                0x20,
                0xee.toByte(),
                0x02,
                0x07,
                0x00,
            )

        val VERTICAL_GRADIENT_HEADER =
            byteArrayOf(
                0x1f,
                0x08,
                0x1a,
                0x07,
                0x00,
            )

        val DIAGONAL_GRADIENT_HEADER =
            byteArrayOf(
                0x2a,
                0x04,
                0x02,
                0x07,
                0x1a,
            )
    }
}

private fun encodeSolidOrange(): ByteArray {
    val pixels = solidFill(32, 32, 0xFFFF8A00.toInt())
    return Thumbhash.encodeFromRgba(32, 32, pixels)!!
}

private fun encodeVerticalGradient(): ByteArray {
    val pixels =
        IntArray(32 * 32) { i ->
            val y = i / 32
            val v = (y * 255 / 31)
            argb(255, v, v, v)
        }
    return Thumbhash.encodeFromRgba(32, 32, pixels)!!
}

private fun encodeDiagonalGradient(): ByteArray {
    val pixels =
        IntArray(32 * 32) { i ->
            val x = i % 32
            val y = i / 32
            val t = ((x + y).toDouble() / 62.0).coerceIn(0.0, 1.0)
            val r = (0 + t * 255).toInt()
            val g = (255 + t * (0 - 255)).toInt()
            val b = 255
            argb(255, r, g, b)
        }
    return Thumbhash.encodeFromRgba(32, 32, pixels)!!
}

private fun assertWireHeader(
    hash: ByteArray,
    expectedHeader: ByteArray,
) {
    assertTrue("hash too short for header", hash.size >= expectedHeader.size)
    assertArrayEquals(expectedHeader, hash.copyOfRange(0, expectedHeader.size))
}

private fun solidFill(
    w: Int,
    h: Int,
    color: Int,
): IntArray = IntArray(w * h) { color }

private fun argb(
    a: Int,
    r: Int,
    g: Int,
    b: Int,
): Int = (a shl 24) or (r shl 16) or (g shl 8) or b

private fun centerRgb(d: Thumbhash.DecodedRgba): Triple<Int, Int, Int> = pixelRgb(d, d.width / 2, d.height / 2)

private fun pixelRgb(
    d: Thumbhash.DecodedRgba,
    x: Int,
    y: Int,
): Triple<Int, Int, Int> {
    val px = d.pixels[y * d.width + x]
    return Triple((px shr 16) and 0xFF, (px shr 8) and 0xFF, px and 0xFF)
}

private fun lumAt(
    d: Thumbhash.DecodedRgba,
    x: Int,
    y: Int,
): Int {
    val (r, g, b) = pixelRgb(d, x, y)
    return r + g + b
}

private fun averageRgb(d: Thumbhash.DecodedRgba): Triple<Int, Int, Int> {
    var sr = 0L
    var sg = 0L
    var sb = 0L
    for (px in d.pixels) {
        sr += (px shr 16) and 0xFF
        sg += (px shr 8) and 0xFF
        sb += px and 0xFF
    }
    val n = d.pixels.size
    return Triple((sr / n).toInt(), (sg / n).toInt(), (sb / n).toInt())
}

private fun averageRgb(
    w: Int,
    h: Int,
    pixels: IntArray,
): Triple<Int, Int, Int> {
    var sr = 0L
    var sg = 0L
    var sb = 0L
    for (px in pixels) {
        sr += (px shr 16) and 0xFF
        sg += (px shr 8) and 0xFF
        sb += px and 0xFF
    }
    val n = w * h
    return Triple((sr / n).toInt(), (sg / n).toInt(), (sb / n).toInt())
}

private fun assertWithin(
    label: String,
    expected: Int,
    actual: Int,
    tol: Int,
) {
    val diff = kotlin.math.abs(expected - actual)
    assertTrue("$label: expected ~$expected got $actual (diff=$diff, tol=$tol)", diff <= tol)
}
