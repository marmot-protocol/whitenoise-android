package dev.ipf.whitenoise.android.ui.conversation.media

import dev.ipf.whitenoise.android.media.ImageAnimationStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageEditabilityTest {
    @Test
    fun staticRasterImagesAreEditable() {
        listOf("image/jpeg", "image/png", "image/webp", "image/gif").forEach { mime ->
            val result = imageEditability(mime, animationStatus = ImageAnimationStatus.STATIC)
            assertTrue(mime, result.canEdit)
            assertFalse(mime, result.isUnsupportedImage)
        }
    }

    @Test
    fun animatedGifAndWebpAreSentUnchanged() {
        listOf("image/gif", "image/webp").forEach { mime ->
            val result = imageEditability(mime, animationStatus = ImageAnimationStatus.ANIMATED)
            assertFalse(mime, result.canEdit)
            assertTrue(mime, result.isUnsupportedImage)
        }
    }

    @Test
    fun indeterminateAnimationStatusFailsClosed() {
        val result = imageEditability("image/png", animationStatus = ImageAnimationStatus.INDETERMINATE)

        assertFalse(result.canEdit)
        assertTrue(result.isUnsupportedImage)
    }

    @Test
    fun avifUsesSendUnchangedBecauseFrameDetectionIsNotSafe() {
        val result = imageEditability("image/avif", animationStatus = ImageAnimationStatus.STATIC)
        val mixedCase = imageEditability("IMAGE/AVIF", animationStatus = ImageAnimationStatus.STATIC)

        assertFalse(result.canEdit)
        assertTrue(result.isUnsupportedImage)
        assertFalse(mixedCase.canEdit)
        assertTrue(mixedCase.isUnsupportedImage)
    }

    @Test
    fun videoAndDocumentsDoNotPretendToBeUnsupportedImages() {
        listOf("video/mp4", "application/pdf", "").forEach { mime ->
            val result = imageEditability(mime, animationStatus = ImageAnimationStatus.STATIC)
            assertFalse(mime, result.canEdit)
            assertFalse(mime, result.isUnsupportedImage)
        }
    }
}
