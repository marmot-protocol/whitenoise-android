package dev.ipf.darkmatter.state

import dev.ipf.darkmatter.media.MediaPipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaQualityTest {
    // ---- preference round-trip ----------------------------------------------

    @Test
    fun fromPreference_roundTripsEveryLevel() {
        MediaQuality.entries.forEach { quality ->
            assertEquals(quality, MediaQuality.fromPreference(quality.preferenceValue))
        }
    }

    @Test
    fun fromPreference_defaultsToStandardForNullOrUnknown() {
        assertEquals(MediaQuality.Standard, MediaQuality.DEFAULT)
        assertEquals(MediaQuality.Standard, MediaQuality.fromPreference(null))
        assertEquals(MediaQuality.Standard, MediaQuality.fromPreference("garbage"))
        assertEquals(MediaQuality.Standard, MediaQuality.fromPreference(""))
    }

    // ---- knob shape ---------------------------------------------------------

    @Test
    fun imageEdgeAndQualityAndAudioBitrate_matchTheIssueTable() {
        assertEquals(1024, MediaQuality.Low.imageMaxEdgePx)
        assertEquals(70, MediaQuality.Low.imageJpegQuality)
        assertEquals(32_000, MediaQuality.Low.audioBitrateBps)

        assertEquals(2048, MediaQuality.Standard.imageMaxEdgePx)
        assertEquals(85, MediaQuality.Standard.imageJpegQuality)
        assertEquals(64_000, MediaQuality.Standard.audioBitrateBps)

        assertEquals(4096, MediaQuality.High.imageMaxEdgePx)
        assertEquals(92, MediaQuality.High.imageJpegQuality)
        assertEquals(96_000, MediaQuality.High.audioBitrateBps)
    }

    @Test
    fun knobsAreMonotonicAcrossTheLoToHiLevels() {
        // Low < Standard < High on edge, JPEG quality, and audio bitrate. The
        // levels are ordered intentionally so "higher" always means "bigger".
        val ordered = listOf(MediaQuality.Low, MediaQuality.Standard, MediaQuality.High)
        ordered.zipWithNext { lower, higher ->
            assertTrue(higher.imageMaxEdgePx > lower.imageMaxEdgePx)
            assertTrue(higher.imageJpegQuality > lower.imageJpegQuality)
            assertTrue(higher.audioBitrateBps >= lower.audioBitrateBps)
        }
    }

    // ---- Original is a ceiling, not an upscale ------------------------------

    @Test
    fun originalLevel_neverDownscales_evenForHugeSources() {
        // Original's primary path preserves metadata-stripped source bytes. The
        // fallback JPEG path still carries an effectively unbounded edge ceiling
        // so targetDimensions returns the source dimensions unchanged.
        val edge = MediaQuality.Original.imageMaxEdgePx
        assertEquals(8000 to 6000, MediaPipeline.targetDimensions(8000, 6000, edge))
        assertEquals(12_000 to 9000, MediaPipeline.targetDimensions(12_000, 9000, edge))
    }

    @Test
    fun lowerLevels_downscaleOversizeSourcesToTheirCeiling() {
        // A 4000px-wide landscape source is capped to the level's longest edge.
        val (lowW, _) = MediaPipeline.targetDimensions(4000, 3000, MediaQuality.Low.imageMaxEdgePx)
        assertEquals(1024, lowW)
        val (stdW, _) = MediaPipeline.targetDimensions(4000, 3000, MediaQuality.Standard.imageMaxEdgePx)
        assertEquals(2048, stdW)
        // High's ceiling (4096) exceeds this source's longest edge, so it's a
        // no-op: the ceiling never upscales.
        assertEquals(4000 to 3000, MediaPipeline.targetDimensions(4000, 3000, MediaQuality.High.imageMaxEdgePx))
    }

    @Test
    fun originalLevel_usesHighestJpegQualityAndAudioBitrate() {
        assertTrue(MediaQuality.Original.preservesOriginalImageBytes)
        assertTrue(!MediaQuality.Low.preservesOriginalImageBytes)
        assertEquals(100, MediaQuality.Original.imageJpegQuality)
        assertEquals(96_000, MediaQuality.Original.audioBitrateBps)
    }
}
