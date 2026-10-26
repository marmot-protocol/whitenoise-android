package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaViewerFitGeometryTest {
    @Test fun wideAttachmentFitsTheMeasuredBodyRatherThanItsSquareThumbnail() {
        val source = Rect(24f, 150f, 128f, 254f)
        val body = Rect(0f, 64f, 360f, 720f)
        val result = mediaViewerMotionBounds(source, Size(360f, 780f), 1f, 16f / 9f, body)
        assertEquals(360f, result.width, 0.001f)
        assertEquals(202.5f, result.height, 0.001f)
        assertEquals(body.center, result.center)
        assertEquals(source, mediaViewerMotionBounds(source, Size(360f, 780f), 0f, 16f / 9f, body))
    }

    @Test fun portraitAttachmentUsesTheBodyHeightAndRetainsItsAspect() {
        val body = Rect(0f, 64f, 360f, 720f)
        val result = mediaViewerMotionBounds(Rect(20f, 20f, 120f, 80f), Size(360f, 780f), 1f, .5f, body)
        assertEquals(body.height, result.height, 0.001f)
        assertEquals(.5f, result.width / result.height, 0.001f)
        assertEquals(body.center, result.center)
    }

    @Test fun unknownOrInvalidDimensionsKeepTheDirectFallback() {
        listOf(null, "", "0x20", "30x0", "bad", "999999999999x1").forEach { assertNull(aspectRatioFromDim(it)) }
        assertEquals(16f / 9f, aspectRatioFromDim("1600x900")!!, .001f)
    }
}
