package dev.ipf.whitenoise.android.ui.conversation.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageEditabilityTest {
    @Test
    fun staticRasterImagesAreEditable() {
        listOf("image/jpeg", "image/png", "image/webp", "image/gif").forEach { mime ->
            val result = imageEditability(mime, isAnimated = false)
            assertTrue(mime, result.canEdit)
            assertFalse(mime, result.isUnsupportedImage)
        }
    }

    @Test
    fun animatedGifAndWebpAreSentUnchanged() {
        listOf("image/gif", "image/webp").forEach { mime ->
            val result = imageEditability(mime, isAnimated = true)
            assertFalse(mime, result.canEdit)
            assertTrue(mime, result.isUnsupportedImage)
        }
    }

    @Test
    fun avifUsesSendUnchangedBecauseFrameDetectionIsNotSafe() {
        val result = imageEditability("image/avif", isAnimated = false)

        assertFalse(result.canEdit)
        assertTrue(result.isUnsupportedImage)
    }

    @Test
    fun videoAndDocumentsDoNotPretendToBeUnsupportedImages() {
        listOf("video/mp4", "application/pdf", "").forEach { mime ->
            val result = imageEditability(mime, isAnimated = false)
            assertFalse(mime, result.canEdit)
            assertFalse(mime, result.isUnsupportedImage)
        }
    }
}
