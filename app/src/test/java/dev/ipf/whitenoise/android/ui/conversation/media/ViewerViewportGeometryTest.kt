package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.ui.geometry.Offset
import dev.ipf.whitenoise.android.media.sandbox.MediaImageRegion
import dev.ipf.whitenoise.android.ui.common.ViewerGestureConsumption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerViewportGeometryTest {
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

    @Test
    fun aTinySvgStillRequestsAFractionalViewportAtDeepZoom() {
        val geometry = ViewerViewportGeometry(400, 400, 24, 24, 1024f, Offset.Zero)
        val region = requireNotNull(geometry.vectorDetailRegion())
        assertTrue(region.width < 0.1 && region.height < 0.1)
        assertTrue(region.validFor(24, 24))
        val target = geometry.sourceRectOnScreen(ViewerDetailRegion.Vector(region).rectangle)
        assertTrue(target.left <= 0 && target.top <= 0 && target.right >= 400 && target.bottom >= 400)
    }

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

    @Test
    fun tinyPansReuseAQuantizedRegionAndFitDoesNotDecodeDetail() {
        val geometry = ViewerViewportGeometry(400, 400, 4000, 4000, 10f, Offset(0.1f, 0.1f))
        assertEquals(geometry.detailRegion(), geometry.copy(offset = Offset(0.2f, 0.2f)).detailRegion())
        assertNull(geometry.copy(scale = 1f).detailRegion())
        assertNull(geometry.copy(width = 0).detailRegion())
    }

    @Test
    fun fallbackOnlyImprovesSourcesInsideItsEdgeAndPixelBudget() {
        assertEquals(4096, viewerFallbackEdge(16000, 1000))
        assertNull(viewerFallbackEdge(8000, 8000))
        assertNull(viewerFallbackEdge(1920, 1080))
        assertNull(viewerFallbackEdge(0, 1000))
    }
}
