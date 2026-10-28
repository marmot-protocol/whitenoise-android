package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun squareThumbnailCoversTheWidePhotoThroughoutEntryAndReturn() {
        assertThumbnailCoversTravel(Size(104f, 104f), Rect(24f, 150f, 128f, 254f), 16f / 9f)
    }

    @Test fun wideThumbnailCoversThePortraitPhotoThroughoutEntryAndReturn() {
        assertThumbnailCoversTravel(Size(144f, 104f), Rect(24f, 150f, 168f, 254f), .5f)
    }

    private fun assertThumbnailCoversTravel(
        thumbnail: Size,
        source: Rect,
        photoAspect: Float,
    ) {
        val body = Rect(0f, 64f, 360f, 720f)
        // Return traverses these same bounds in reverse; both directions must remain covered.
        listOf(0f, .25f, .5f, .75f, .95f, 1f).forEach { progress ->
            val bounds = mediaViewerMotionBounds(source, Size(360f, 780f), progress, photoAspect, body)
            val scale = mediaViewerThumbnailScale(thumbnail, bounds)
            val paintedWidth = thumbnail.width * scale
            val paintedHeight = thumbnail.height * scale
            assertTrue("the moving copy covers the clip width", paintedWidth >= bounds.width - .001f)
            assertTrue("the moving copy covers the clip height", paintedHeight >= bounds.height - .001f)
            assertEquals(
                "one crop axis fits exactly, so the copy does not grow beyond coverage",
                0f,
                minOf(paintedWidth - bounds.width, paintedHeight - bounds.height),
                .001f,
            )
        }
    }

    @Test fun unknownOrInvalidDimensionsKeepTheDirectFallback() {
        listOf(null, "", "0x20", "30x0", "bad", "999999999999x1").forEach { assertNull(aspectRatioFromDim(it)) }
        assertEquals(16f / 9f, aspectRatioFromDim("1600x900")!!, .001f)
    }
}
