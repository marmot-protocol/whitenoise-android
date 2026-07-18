package dev.ipf.whitenoise.android.ui.common

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerStateTest {
    @Test
    fun clampViewerPageIndexCoercesBelowLowerBound() {
        assertEquals(0, clampViewerPageIndex(-3, pageCount = 5))
    }

    @Test
    fun clampViewerPageIndexCoercesAboveUpperBound() {
        assertEquals(4, clampViewerPageIndex(9, pageCount = 5))
    }

    @Test
    fun clampViewerPageIndexPreservesInRangeIndex() {
        assertEquals(2, clampViewerPageIndex(2, pageCount = 5))
    }

    @Test
    fun clampViewerPageIndexReturnsZeroWhenAlbumEmpty() {
        assertEquals(0, clampViewerPageIndex(3, pageCount = 0))
    }

    @Test
    fun applyViewerPinchZoomClampsAtMinimumScale() {
        assertEquals(VIEWER_MIN_SCALE, applyViewerPinchZoom(1f, zoomFactor = 0.5f))
    }

    @Test
    fun applyViewerPinchZoomClampsAtMaximumScale() {
        assertEquals(VIEWER_MAX_SCALE, applyViewerPinchZoom(4f, zoomFactor = 2f))
    }

    @Test
    fun applyViewerPinchZoomPreservesMidRangeScale() {
        assertEquals(2.5f, applyViewerPinchZoom(2f, zoomFactor = 1.25f))
    }

    @Test
    fun viewerPagerScrollEnabledOnlyAtIdentityScale() {
        assertTrue(viewerPagerScrollEnabled(VIEWER_MIN_SCALE))
        assertFalse(viewerPagerScrollEnabled(VIEWER_MIN_SCALE + 0.01f))
    }

    @Test
    fun resetViewerTransformReturnsIdentityScaleAndZeroOffset() {
        val reset = resetViewerTransform()
        assertEquals(VIEWER_MIN_SCALE, reset.scale)
        assertEquals(Offset.Zero, reset.offset)
    }

    @Test
    fun applyViewerTransformGestureClampsPanWithinZoomExtents() {
        val result =
            applyViewerTransformGesture(
                current = ViewerTransform(scale = 2f, offset = Offset(40f, 0f)),
                zoomFactor = 1f,
                panDelta = Offset(500f, 0f),
                viewportWidth = 360f,
                viewportHeight = 780f,
                imageWidth = 1200,
                imageHeight = 800,
            )
        // Landscape 1200×800 in 360×780 at 2×: width-limited fit → maxX=180, maxY=0.
        assertEquals(180f, result.offset.x, 0.001f)
        assertEquals(0f, result.offset.y, 0.001f)
        assertEquals(2f, result.scale)
    }

    @Test
    fun applyViewerTransformGestureClampsVerticalPanWithinZoomExtents() {
        val result =
            applyViewerTransformGesture(
                current = ViewerTransform(scale = 2f, offset = Offset(0f, 40f)),
                zoomFactor = 1f,
                panDelta = Offset(0f, 500f),
                viewportWidth = 360f,
                viewportHeight = 780f,
                imageWidth = 300,
                imageHeight = 2000,
            )
        // Portrait 300×2000 in 360×780 at 2×: height-limited fit → maxX=0, maxY=390.
        assertEquals(0f, result.offset.x, 0.001f)
        assertEquals(390f, result.offset.y, 0.001f)
        assertEquals(2f, result.scale)
    }

    @Test
    fun applyViewerTransformGestureClearsOffsetWhenZoomReturnsToIdentity() {
        val result =
            applyViewerTransformGesture(
                current = ViewerTransform(scale = 1.1f, offset = Offset(12f, -8f)),
                zoomFactor = 0.5f,
                panDelta = Offset.Zero,
                viewportWidth = 360f,
                viewportHeight = 780f,
                imageWidth = 1200,
                imageHeight = 800,
            )
        assertEquals(VIEWER_MIN_SCALE, result.scale)
        assertEquals(Offset.Zero, result.offset)
    }

    @Test
    fun applyAvatarDownwardDragTracksBelowDismissThreshold() {
        val result =
            applyAvatarDownwardDrag(
                scale = VIEWER_MIN_SCALE,
                state = AvatarDragDismissState(),
                dragAmount = 40f,
                dismissThresholdPx = 96f,
            )
        assertTrue(result is AvatarDragDismissResult.Tracking)
        assertEquals(40f, (result as AvatarDragDismissResult.Tracking).state.draggedDownPx)
    }

    @Test
    fun applyAvatarDownwardDragDismissesAtThreshold() {
        val result =
            applyAvatarDownwardDrag(
                scale = VIEWER_MIN_SCALE,
                state = AvatarDragDismissState(draggedDownPx = 80f),
                dragAmount = 16f,
                dismissThresholdPx = 96f,
            )
        assertTrue(result is AvatarDragDismissResult.Dismiss)
        assertEquals(0f, (result as AvatarDragDismissResult.Dismiss).state.draggedDownPx)
    }

    @Test
    fun applyAvatarDownwardDragIgnoresWhileZoomed() {
        val result =
            applyAvatarDownwardDrag(
                scale = 2f,
                state = AvatarDragDismissState(),
                dragAmount = 200f,
                dismissThresholdPx = 96f,
            )
        assertEquals(AvatarDragDismissResult.Ignored, result)
    }

    @Test
    fun applyAvatarDownwardDragIgnoresUpwardMotionUntilDragAlreadyStarted() {
        val result =
            applyAvatarDownwardDrag(
                scale = VIEWER_MIN_SCALE,
                state = AvatarDragDismissState(),
                dragAmount = -20f,
                dismissThresholdPx = 96f,
            )
        assertEquals(AvatarDragDismissResult.Ignored, result)
    }
}
