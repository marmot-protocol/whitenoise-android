package dev.ipf.darkmatter.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

class MediaPipelineTest {
    // ---- targetDimensions ---------------------------------------------------

    @Test
    fun targetDimensions_withinMax_returnsSource() {
        // Inside the box on both axes — no upscaling.
        assertEquals(800 to 600, MediaPipeline.targetDimensions(800, 600, maxEdgePx = 1920))
    }

    @Test
    fun targetDimensions_squareAtBoundary_returnsSource() {
        // Exact match on longest edge — still no-op.
        assertEquals(1920 to 1920, MediaPipeline.targetDimensions(1920, 1920, maxEdgePx = 1920))
    }

    @Test
    fun targetDimensions_landscape_scalesPreservingAspect() {
        // 4000x3000 (4:3 landscape) → 1920x1440. Width is the long edge.
        assertEquals(1920 to 1440, MediaPipeline.targetDimensions(4000, 3000, maxEdgePx = 1920))
    }

    @Test
    fun targetDimensions_portrait_scalesPreservingAspect() {
        // 3024x4032 iPhone-portrait → height is the long edge.
        val (w, h) = MediaPipeline.targetDimensions(3024, 4032, maxEdgePx = 1920)
        assertEquals(1920, h)
        // 3024 * (1920 / 4032) = 1440
        assertEquals(1440, w)
    }

    @Test
    fun targetDimensions_extremeAspectRatio_coercesShortEdgeToAtLeastOne() {
        // 19200x10 → long edge scaled to 1920, short edge would round to 1.
        val (w, h) = MediaPipeline.targetDimensions(19200, 10, maxEdgePx = 1920)
        assertEquals(1920, w)
        assertEquals(1, h)
    }

    @Test
    fun targetDimensions_zeroOrNegativeInput_returnsZeros() {
        // Sentinel for "give up" — caller treats (0, 0) as decode failure.
        assertEquals(0 to 0, MediaPipeline.targetDimensions(0, 100, maxEdgePx = 1920))
        assertEquals(0 to 0, MediaPipeline.targetDimensions(100, 0, maxEdgePx = 1920))
        assertEquals(0 to 0, MediaPipeline.targetDimensions(-1, 100, maxEdgePx = 1920))
        assertEquals(0 to 0, MediaPipeline.targetDimensions(100, 100, maxEdgePx = 0))
    }

    // ---- computeInSampleSize ------------------------------------------------

    @Test
    fun inSampleSize_neverBelowOne() {
        assertEquals(1, MediaPipeline.computeInSampleSize(800, 600, 1000, 1000))
        assertEquals(1, MediaPipeline.computeInSampleSize(800, 600, 800, 600))
    }

    @Test
    fun inSampleSize_powersOfTwo() {
        // 4000x3000 source, target 1920x1440 → sampleSize = 2 keeps decoded
        // ≥ target on both axes (2000x1500 ≥ 1920x1440); next step would
        // underflow on the height.
        assertEquals(2, MediaPipeline.computeInSampleSize(4000, 3000, 1920, 1440))
        // 8000x6000 → sampleSize = 4 (2000x1500 still ≥ target, 1000x750 < target)
        assertEquals(4, MediaPipeline.computeInSampleSize(8000, 6000, 1920, 1440))
    }

    @Test
    fun inSampleSize_oneWhenTargetNonPositive() {
        // Defensive — caller shouldn't pass zero, but the function shouldn't loop forever if it does.
        assertEquals(1, MediaPipeline.computeInSampleSize(1000, 1000, 0, 0))
    }

    // ---- swapExtensionToJpg -------------------------------------------------

    @Test
    fun swapExtension_replacesExistingExtension() {
        assertEquals("photo.jpg", MediaPipeline.swapExtensionToJpg("photo.png"))
        assertEquals("img.jpg", MediaPipeline.swapExtensionToJpg("img.HEIC"))
        assertEquals("scan.jpg", MediaPipeline.swapExtensionToJpg("scan.webp"))
    }

    @Test
    fun swapExtension_appendsWhenNoExtension() {
        assertEquals("photo.jpg", MediaPipeline.swapExtensionToJpg("photo"))
    }

    @Test
    fun swapExtension_appendsWhenLeadingDot() {
        // ".env"-style names — overwriting would produce ".jpg", a hidden file.
        assertEquals(".env.jpg", MediaPipeline.swapExtensionToJpg(".env"))
    }

    @Test
    fun swapExtension_preservesPathBeforeDot() {
        // Multiple dots — only the last one is the extension.
        assertEquals("my.photo.from.2026.jpg", MediaPipeline.swapExtensionToJpg("my.photo.from.2026.png"))
    }

    @Test
    fun swapExtension_blankInputFallsBackToImageJpg() {
        assertEquals("image.jpg", MediaPipeline.swapExtensionToJpg(""))
        assertEquals("image.jpg", MediaPipeline.swapExtensionToJpg("   "))
    }

    // ---- safeDisplayName (remote-supplied filename hardening) ----------------

    @Test
    fun safeDisplayName_keepsPlainBasename() {
        assertEquals("photo.jpg", MediaPipeline.safeDisplayName("photo.jpg"))
    }

    @Test
    fun safeDisplayName_stripsUnixPathTraversal() {
        // A malicious imeta filename must not escape the target directory.
        assertEquals("evil.jpg", MediaPipeline.safeDisplayName("../../../databases/evil.jpg"))
    }

    @Test
    fun safeDisplayName_stripsWindowsAndMixedSeparators() {
        assertEquals("x.jpg", MediaPipeline.safeDisplayName("..\\..\\x.jpg"))
        assertEquals("x.jpg", MediaPipeline.safeDisplayName("a/b\\c/x.jpg"))
    }

    @Test
    fun safeDisplayName_dotAndDotDotAndBlankFallBack() {
        assertEquals("image.jpg", MediaPipeline.safeDisplayName(""))
        assertEquals("image.jpg", MediaPipeline.safeDisplayName("   "))
        assertEquals("image.jpg", MediaPipeline.safeDisplayName("."))
        assertEquals("image.jpg", MediaPipeline.safeDisplayName(".."))
        assertEquals("image.jpg", MediaPipeline.safeDisplayName("foo/.."))
    }

    @Test
    fun jpegExifOrientation_readsBigEndianOrientationTag() {
        val jpeg = minimalJpegWith(exifOrientationSegment(MediaPipeline.EXIF_ORIENTATION_ROTATE_90))

        assertEquals(MediaPipeline.EXIF_ORIENTATION_ROTATE_90, MediaPipeline.jpegExifOrientation(jpeg))
    }

    @Test
    fun jpegExifOrientation_readsLittleEndianOrientationTag() {
        val jpeg = minimalJpegWith(exifOrientationSegment(MediaPipeline.EXIF_ORIENTATION_ROTATE_270, littleEndian = true))

        assertEquals(MediaPipeline.EXIF_ORIENTATION_ROTATE_270, MediaPipeline.jpegExifOrientation(jpeg))
    }

    @Test
    fun targetDimensionsForExifOrientation_usesDisplayOrientationForRotatedSources() {
        // Android camera captures often store a landscape sensor buffer plus
        // EXIF Orientation=Rotate90. The display/downscale box must treat that
        // as a portrait source or the encoded dimensions and thumbhash drift.
        assertEquals(
            1440 to 1920,
            MediaPipeline.targetDimensionsForExifOrientation(
                srcWidth = 4032,
                srcHeight = 3024,
                maxEdgePx = 1920,
                orientation = MediaPipeline.EXIF_ORIENTATION_ROTATE_90,
            ),
        )
    }

    @Test
    fun exifOrientationRequiresPixelTransform_flagsRotationsAndMirrors() {
        assertFalse(MediaPipeline.exifOrientationRequiresPixelTransform(MediaPipeline.EXIF_ORIENTATION_NORMAL))
        assertTrue(MediaPipeline.exifOrientationRequiresPixelTransform(MediaPipeline.EXIF_ORIENTATION_ROTATE_90))
        assertTrue(MediaPipeline.exifOrientationRequiresPixelTransform(MediaPipeline.EXIF_ORIENTATION_FLIP_HORIZONTAL))
    }

    @Test
    fun originalJpegStrip_dropsMetadataSegmentsButKeepsScanBytes() {
        val scan = byteArrayOf(0x11, 0x22, 0xff.toByte(), 0x00, 0x33, 0xff.toByte(), 0xd9.toByte())
        val jpeg =
            byteArrayOf(0xff.toByte(), 0xd8.toByte()) +
                jpegSegment(0xe0, "JFIF".encodeToByteArray()) +
                jpegSegment(0xe1, "Exif\u0000\u0000gps".encodeToByteArray()) +
                jpegSegment(0xfe, "camera comment".encodeToByteArray()) +
                jpegSegment(0xdb, byteArrayOf(1, 2, 3)) +
                jpegSegment(0xda, byteArrayOf(0, 1, 2, 3)) +
                scan

        val stripped = MediaPipeline.stripOriginalImageMetadata(jpeg)!!

        assertTrue(stripped.startsWithSubsequence(byteArrayOf(0xff.toByte(), 0xd8.toByte())))
        assertFalse(stripped.containsAscii("Exif"))
        assertFalse(stripped.containsAscii("camera comment"))
        assertTrue(stripped.containsAscii("JFIF"))
        assertTrue(stripped.endsWithSubsequence(scan))
    }

    @Test
    fun originalJpegStrip_continuesFilteringAfterScanAndDropsTrailingBytes() {
        val scan1 = byteArrayOf(0x11, 0xff.toByte(), 0x00, 0x22)
        val scan2 = byteArrayOf(0x33, 0xff.toByte(), 0xd9.toByte())
        val trailingMetadata = jpegSegment(0xe1, "trailing gps".encodeToByteArray())
        val jpeg =
            byteArrayOf(0xff.toByte(), 0xd8.toByte()) +
                jpegSegment(0xda, byteArrayOf(0, 1, 2, 3)) +
                scan1 +
                jpegSegment(0xe1, "progressive gps".encodeToByteArray()) +
                jpegSegment(0xda, byteArrayOf(4, 5, 6, 7)) +
                scan2 +
                trailingMetadata

        val stripped = MediaPipeline.stripOriginalImageMetadata(jpeg)!!

        assertFalse(stripped.containsAscii("progressive gps"))
        assertFalse(stripped.containsAscii("trailing gps"))
        assertTrue(stripped.containsSubsequence(scan1))
        assertTrue(stripped.endsWithSubsequence(scan2))
    }

    @Test
    fun originalPngStrip_dropsTextAndExifChunksButKeepsImageData() {
        val idatPayload = byteArrayOf(9, 8, 7, 6)
        val png =
            pngSignature +
                pngChunk("IHDR", ByteArray(13)) +
                pngChunk("tEXt", "GPS=secret".encodeToByteArray()) +
                pngChunk("eXIf", "camera".encodeToByteArray()) +
                pngChunk("IDAT", idatPayload) +
                pngChunk("IEND", ByteArray(0))

        val stripped = MediaPipeline.stripOriginalImageMetadata(png)!!

        assertFalse(stripped.containsAscii("GPS=secret"))
        assertFalse(stripped.containsAscii("camera"))
        assertTrue(stripped.containsAscii("IHDR"))
        assertTrue(stripped.containsAscii("IDAT"))
        assertTrue(stripped.containsSubsequence(idatPayload))
    }

    @Test
    fun originalWebpStrip_dropsExifAndXmpChunksAndClearsVp8xFlags() {
        val vp8xPayload = byteArrayOf(0x0c, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val vp8Payload = byteArrayOf(1, 2, 3, 4)
        val webpPayload =
            "WEBP".encodeToByteArray() +
                webpChunk("VP8X", vp8xPayload) +
                webpChunk("EXIF", "gps".encodeToByteArray()) +
                webpChunk("XMP ", "xmp".encodeToByteArray()) +
                webpChunk("VP8 ", vp8Payload)
        val webp = "RIFF".encodeToByteArray() + u32le(webpPayload.size) + webpPayload

        val stripped = MediaPipeline.stripOriginalImageMetadata(webp)!!

        assertFalse(stripped.containsAscii("gps"))
        assertFalse(stripped.containsAscii("xmp"))
        assertTrue(stripped.containsAscii("VP8 "))
        assertTrue(stripped.containsSubsequence(vp8Payload))
        val vp8xOffset = stripped.indexOfAscii("VP8X")
        assertTrue(vp8xOffset >= 0)
        assertEquals(0, stripped[vp8xOffset + 8].toInt() and 0x0c)
    }

    private fun jpegSegment(
        marker: Int,
        payload: ByteArray,
    ): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(0xff.toByte(), marker.toByte(), (length ushr 8).toByte(), length.toByte()) + payload
    }

    private fun minimalJpegWith(vararg segments: ByteArray): ByteArray =
        byteArrayOf(0xff.toByte(), 0xd8.toByte()) +
            segments.fold(ByteArray(0)) { acc, segment -> acc + segment } +
            jpegSegment(0xda, byteArrayOf(0, 1, 2, 3)) +
            byteArrayOf(0x11, 0x22, 0xff.toByte(), 0xd9.toByte())

    private fun exifOrientationSegment(
        orientation: Int,
        littleEndian: Boolean = false,
    ): ByteArray {
        val tiff =
            if (littleEndian) {
                byteArrayOf(
                    'I'.code.toByte(),
                    'I'.code.toByte(),
                    42,
                    0,
                    8,
                    0,
                    0,
                    0,
                    1,
                    0,
                    0x12,
                    0x01,
                    3,
                    0,
                    1,
                    0,
                    0,
                    0,
                    orientation.toByte(),
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                )
            } else {
                byteArrayOf(
                    'M'.code.toByte(),
                    'M'.code.toByte(),
                    0,
                    42,
                    0,
                    0,
                    0,
                    8,
                    0,
                    1,
                    0x01,
                    0x12,
                    0,
                    3,
                    0,
                    0,
                    0,
                    1,
                    0,
                    orientation.toByte(),
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                )
            }
        return jpegSegment(0xe1, "Exif\u0000\u0000".encodeToByteArray() + tiff)
    }

    private val pngSignature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

    private fun pngChunk(
        type: String,
        payload: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(u32be(payload.size))
        out.write(type.encodeToByteArray())
        out.write(payload)
        val crc = CRC32()
        crc.update(type.encodeToByteArray())
        crc.update(payload)
        out.write(u32be(crc.value.toInt()))
        return out.toByteArray()
    }

    private fun webpChunk(
        type: String,
        payload: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(type.encodeToByteArray())
        out.write(u32le(payload.size))
        out.write(payload)
        if (payload.size % 2 == 1) out.write(0)
        return out.toByteArray()
    }

    private fun u32be(value: Int): ByteArray =
        byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )

    private fun u32le(value: Int): ByteArray =
        byteArrayOf(
            value.toByte(),
            (value ushr 8).toByte(),
            (value ushr 16).toByte(),
            (value ushr 24).toByte(),
        )

    private fun ByteArray.containsAscii(value: String): Boolean = indexOfAscii(value) >= 0

    private fun ByteArray.indexOfAscii(value: String): Int {
        val needle = value.encodeToByteArray()
        if (needle.isEmpty() || needle.size > size) return -1
        for (start in 0..(size - needle.size)) {
            if (needle.indices.all { this[start + it] == needle[it] }) return start
        }
        return -1
    }

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        for (start in 0..(size - needle.size)) {
            if (needle.indices.all { this[start + it] == needle[it] }) return true
        }
        return false
    }

    private fun ByteArray.startsWithSubsequence(needle: ByteArray): Boolean =
        needle.isNotEmpty() && needle.size <= size && needle.indices.all { this[it] == needle[it] }

    private fun ByteArray.endsWithSubsequence(needle: ByteArray): Boolean =
        needle.isNotEmpty() && needle.size <= size && needle.indices.all { this[size - needle.size + it] == needle[it] }
}
