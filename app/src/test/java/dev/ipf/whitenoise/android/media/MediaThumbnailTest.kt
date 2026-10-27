package dev.ipf.whitenoise.android.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Decode admission uses physical geometry and a finite set of bounded cache resolutions. */
class MediaThumbnailTest {
    /** Small tiles use smaller buckets; a denser rendering admits enough physical pixels. */
    @Test
    fun physicalPixelsSelectAppropriateBuckets() {
        assertEquals(128, thumbnailDecodeMaxEdge(96, 72, 4f / 3f))
        assertEquals(256, thumbnailDecodeMaxEdge(192, 144, 4f / 3f))
        assertEquals(384, thumbnailDecodeMaxEdge(288, 216, 4f / 3f))
        assertEquals(512, thumbnailDecodeMaxEdge(385, 385, 1f))
        assertEquals(768, thumbnailDecodeMaxEdge(600, 600, 1f))
    }

    /** Cropping a wide or tall source requires pixels outside the tile's visible long edge. */
    @Test
    fun croppedSourcesRetainEnoughPixelsInBothOrientations() {
        assertEquals(1024, thumbnailDecodeMaxEdge(250, 250, 4f))
        assertEquals(1024, thumbnailDecodeMaxEdge(250, 250, 0.25f))
        assertEquals(256, thumbnailDecodeMaxEdge(256, 192, 4f / 3f))
        assertEquals(256, thumbnailDecodeMaxEdge(192, 256, 3f / 4f))
    }

    /** Unmeasured content does not decode, and missing/untrusted metadata preserves the previous safety ceiling. */
    @Test
    fun missingGeometryCannotStartAnUnboundedDecode() {
        assertEquals(0, thumbnailDecodeMaxEdge(0, 100, 1f))
        assertEquals(0, thumbnailDecodeMaxEdge(100, -1, 1f))
        listOf(null, Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f).forEach { ratio ->
            assertEquals(1280, thumbnailDecodeMaxEdge(100, 100, ratio))
        }
        assertEquals(1280, thumbnailDecodeMaxEdge(Int.MAX_VALUE, Int.MAX_VALUE, Float.MIN_VALUE))
    }

    /** Layout jitter cannot create arbitrary decode sizes or bypass the existing maximum. */
    @Test
    fun allSizesCollapseToSevenBuckets() {
        val bounds = (1..2000).map { thumbnailDecodeMaxEdge(it, it, 1f) }.toSet()
        assertEquals(setOf(128, 256, 384, 512, 768, 1024, 1280), bounds)
        assertTrue(bounds.all { it <= MediaPipeline.THUMBNAIL_MAX_EDGE_PX })
    }
}
