package dev.ipf.darkmatter.media

import org.junit.Assert.assertEquals
import org.junit.Test

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
}
