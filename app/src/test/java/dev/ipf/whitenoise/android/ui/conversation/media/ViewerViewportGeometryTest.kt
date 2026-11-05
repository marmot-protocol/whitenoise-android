package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.ui.geometry.Offset
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.media.sandbox.MediaDecodePolicy
import dev.ipf.whitenoise.android.media.sandbox.MediaImageRegion
import dev.ipf.whitenoise.android.media.sandbox.targetSize
import dev.ipf.whitenoise.android.ui.common.ViewerGestureConsumption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerViewportGeometryTest {
    /** Gesture ownership begins after slop and remains with the viewer for the rest of the gesture. */
    @Test
    fun singleFingerConsumptionWaitsForSlopAndStaysOwnedAfterCrossing() {
        val gesture = ViewerGestureConsumption(10f)
        assertFalse(gesture.shouldConsume(1, Offset(3f, 0f)))
        assertFalse(gesture.shouldConsume(1, Offset(3f, 0f)))
        assertFalse(gesture.shouldConsume(1, Offset(4f, 0f)))
        assertTrue(gesture.shouldConsume(1, Offset(1f, 0f)))
        assertTrue(gesture.shouldConsume(1, Offset.Zero))
        assertTrue(gesture.shouldConsume(1, Offset(-11f, 0f)))
        assertFalse(ViewerGestureConsumption(10f).shouldConsume(1, Offset.Zero))
        val pinch = ViewerGestureConsumption(10f)
        assertTrue(pinch.shouldConsume(2, Offset.Zero))
        assertTrue(pinch.shouldConsume(1, Offset.Zero))
    }

    /** Malformed dimensions, scale and offsets never cross the codec admission boundary. */
    @Test
    fun invalidGeometryNeverRequestsRasterOrVectorDecoding() {
        val base = ViewerViewportGeometry(400, 400, 4000, 4000, 10f, Offset.Zero)
        listOf(
            base.copy(scale = Float.NaN),
            base.copy(scale = Float.POSITIVE_INFINITY),
            base.copy(scale = 1025f),
            base.copy(offset = Offset(Float.NaN, 0f)),
            base.copy(offset = Offset(0f, Float.POSITIVE_INFINITY)),
            base.copy(sourceWidth = Int.MAX_VALUE),
            base.copy(width = 0),
        ).forEach { value ->
            assertNull(value.detailRequest(false))
            assertNull(value.detailRequest(true))
        }
    }

    /** Deep vector zoom retains fractional source coordinates instead of rounding away the visible crop. */
    @Test
    fun aTinySvgStillRequestsAFractionalViewportAtDeepZoom() {
        val geometry = ViewerViewportGeometry(400, 400, 24, 24, 1024f, Offset.Zero)
        val region = requireNotNull(geometry.vectorDetailRegion())
        assertTrue(region.width < 0.1 && region.height < 0.1)
        assertTrue(region.validFor(24, 24))
        val target = geometry.sourceRectOnScreen(requireNotNull(geometry.detailRequest(true)).rectangle)
        assertTrue(target.left <= 0 && target.top <= 0 && target.right >= 400 && target.bottom >= 400)
    }

    /** Source bounds map to their true transformed location while the halo encloses every visible pixel. */
    @Test
    fun sourceRectIsPositionedAtItsActualLocationInsteadOfFittingTheWholeViewport() {
        val geometry = ViewerViewportGeometry(400, 400, 4000, 4000, 10f, Offset.Zero)
        val rectangle = geometry.sourceRectOnScreen(MediaImageRegion(1800, 1800, 2200, 2200))
        assertEquals(0f, rectangle.left, 0.001f)
        assertEquals(0f, rectangle.top, 0.001f)
        assertEquals(400f, rectangle.right, 0.001f)
        assertEquals(400f, rectangle.bottom, 0.001f)
        val visible = requireNotNull(geometry.detailRegion())
        assertTrue(visible.left <= 1800 && visible.top <= 1800)
        assertTrue(visible.right >= 2200 && visible.bottom >= 2200)
        assertTrue(visible.validFor(4000, 4000))
    }

    /** Quantization suppresses tiny pan work and fit scale leaves the sampled base alone. */
    @Test
    fun tinyPansReuseAQuantizedRegionAndFitDoesNotDecodeDetail() {
        val geometry = ViewerViewportGeometry(400, 400, 4000, 4000, 10f, Offset(0.1f, 0.1f))
        assertEquals(geometry.detailRegion(), geometry.copy(offset = Offset(0.2f, 0.2f)).detailRegion())
        assertNull(geometry.copy(scale = 1f).detailRegion())
        assertNull(geometry.copy(width = 0).detailRegion())
    }

    /** The whole-source fallback is admitted only when its bounded decode can improve the base. */
    @Test
    fun fallbackOnlyImprovesSourcesInsideItsEdgeAndPixelBudget() {
        assertEquals(4096, viewerFallbackEdge(16000, 1000))
        assertNull(viewerFallbackEdge(8000, 8000))
        assertNull(viewerFallbackEdge(1920, 1080))
        assertNull(viewerFallbackEdge(0, 1000))
    }

    /** Phone-sized tiles include their quantization halo when requesting actual display pixels. */
    @Test
    fun vectorDetailUsesPhonePixelDemandAndIncludesResolutionInItsKey() {
        for ((width, height) in listOf(1080 to 2424, 2424 to 1080)) {
            val geometry = ViewerViewportGeometry(width, height, 24, 24, 1024f, Offset.Zero)
            val request = geometry.detailRequest(true) as ViewerDetailRegion.Vector
            val screen = geometry.sourceRectOnScreen(request.rectangle)
            assertTrue(request.edge > 2048)
            assertTrue(request.edge >= maxOf(screen.width, screen.height))
            val target = requireNotNull(request.region.targetSize(request.edge))
            assertTrue(target.width >= screen.width)
            assertTrue(target.height >= screen.height)
            assertTrue(target.capacity <= 16 * 1024 * 1024)
        }
        val geometry = ViewerViewportGeometry(400, 400, 24, 24, 1024f, Offset.Zero)
        val small = geometry.detailRequest(true) as ViewerDetailRegion.Vector
        val large = geometry.copy(width = 800, height = 800).detailRequest(true) as ViewerDetailRegion.Vector
        assertEquals(small.region, large.region)
        assertTrue(large.edge > small.edge)
        assertFalse(small == large)
    }

    /** Phone-sized halo crops request display pixels and improve the base in either device orientation. */
    @Test
    fun rasterDetailUsesPhonePixelDemandInsteadOfTheOldUndersampledEdge() {
        val geometries =
            listOf(
                ViewerViewportGeometry(1080, 2424, 8000, 12000, 2f, Offset.Zero),
                ViewerViewportGeometry(2424, 1080, 12000, 8000, 2f, Offset.Zero),
            )
        geometries.forEach { geometry ->
            val request = geometry.detailRequest(false) as ViewerDetailRegion.Raster
            val screen = geometry.sourceRectOnScreen(request.rectangle)
            val target = MediaPipeline.targetDimensions(request.region.width, request.region.height, request.edge)
            val old = MediaPipeline.targetDimensions(request.region.width, request.region.height, VIEWER_DETAIL_EDGE)
            assertTrue(request.edge > VIEWER_DETAIL_EDGE)
            assertTrue(request.edge <= kotlin.math.ceil(maxOf(screen.width, screen.height)).toInt())
            assertTrue(MediaDecodePolicy.regionSampleSize(request.region, request.edge) != null)
            assertTrue(
                rasterDetailImprovesPreview(
                    geometry.sourceWidth,
                    geometry.sourceHeight,
                    request.region,
                    target.first,
                    target.second,
                ),
            )
            assertFalse(
                rasterDetailImprovesPreview(
                    geometry.sourceWidth,
                    geometry.sourceHeight,
                    request.region,
                    old.first,
                    old.second,
                ),
            )
        }
    }

    /** The decode key changes with pixel demand even when quantized source bounds stay identical. */
    @Test
    fun rasterRequestKeyTracksResolutionBeforeNativeSamplingIsExhausted() {
        val geometry = ViewerViewportGeometry(400, 400, 12000, 12000, 10f, Offset.Zero)
        assertTrue(MediaDecodePolicy.sourceAllowed(geometry.sourceWidth, geometry.sourceHeight))
        val small = geometry.detailRequest(false) as ViewerDetailRegion.Raster
        val large = geometry.copy(width = 800, height = 800).detailRequest(false) as ViewerDetailRegion.Raster
        assertEquals(small.region, large.region)
        assertTrue(large.edge > small.edge)
        assertFalse(small == large)
    }

    /** Native-sized sources and moderate zooms keep the base when a display-sized crop would add no detail. */
    @Test
    fun rasterRequestsSkipTilesThatCannotImproveTheBase() {
        val phone = ViewerViewportGeometry(1080, 2424, 8000, 12000, 1.5f, Offset.Zero)
        assertTrue(phone.detailRegion() != null)
        assertNull(phone.detailRequest(false))
        assertNull(phone.copy(scale = 1f).detailRequest(false))
        assertNull(ViewerViewportGeometry(400, 400, 100, 100, 10f, Offset.Zero).detailRequest(false))
    }

    /** Neither output storage nor the codec's power-of-two intermediate exceeds existing raster policy. */
    @Test
    fun rasterPhoneDemandStillUsesTheExistingCodecCeilings() {
        val geometry = ViewerViewportGeometry(10000, 24000, 8000, 12000, 2f, Offset.Zero)
        val request = geometry.detailRequest(false) as ViewerDetailRegion.Raster
        assertTrue(request.edge <= MediaDecodePolicy.MAX_EDGE)
        val wide = ViewerViewportGeometry(20000, 1000, 16000, 1000, 2f, Offset.Zero)
        assertEquals(MediaDecodePolicy.MAX_EDGE, (wide.detailRequest(false) as ViewerDetailRegion.Raster).edge)
        assertTrue(requireNotNull(MediaDecodePolicy.outputCapacity(request.edge)) <= MediaDecodePolicy.MAX_OUTPUT_BYTES)
        val sample = requireNotNull(MediaDecodePolicy.regionSampleSize(request.region, request.edge))
        val width = (request.region.width.toLong() + sample - 1) / sample
        val height = (request.region.height.toLong() + sample - 1) / sample
        assertTrue(width * height <= MediaDecodePolicy.MAX_REGION_DECODE_PIXELS)
    }

    /** Quality cannot improve one axis by making the other softer, and native thin rows need no upscaling. */
    @Test
    fun rasterDensityPreservesBothBaseAxesAndImprovesAtLeastOne() {
        val full = MediaImageRegion(0, 0, 16000, 1000)
        assertFalse(rasterDetailImprovesPreview(16000, 1000, full, 2560, 160))
        assertTrue(rasterDetailImprovesPreview(16000, 1000, full, 4096, 256))
        assertFalse(rasterDetailImprovesPreview(16000, 1000, full, 4096, 159))
        assertFalse(rasterDetailImprovesPreview(16000, 1000, full, 2559, 256))
        val thin = MediaImageRegion(0, 0, 4000, 1)
        assertTrue(rasterDetailImprovesPreview(16000, 1, thin, 4000, 1))
        assertFalse(rasterDetailImprovesPreview(16000, 1, thin, 640, 1))
    }
}
