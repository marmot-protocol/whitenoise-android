package dev.ipf.whitenoise.android.media

import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaLocatorFfi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaReferenceSupportTest {
    @Test
    fun mimeClassificationStaysAndroidLocal() {
        assertTrue(MediaReferenceSupport.isImageMedia(reference(mediaType = "IMAGE/HEIC")))
        assertTrue(MediaReferenceSupport.isAudioMedia(reference(mediaType = "audio/mp4")))
        assertTrue(MediaReferenceSupport.isVideoMedia(reference(mediaType = "video/mp4")))
        assertFalse(MediaReferenceSupport.isImageMedia(reference(mediaType = "application/pdf")))
    }

    private fun reference(
        locatorUrl: String = "https://media.example/blob",
        locators: List<MediaLocatorFfi> =
            listOf(
                MediaLocatorFfi(kind = "blossom-v1", value = locatorUrl),
            ),
        mediaType: String = "image/jpeg",
    ) = MediaAttachmentReferenceFfi(
        locators = locators,
        ciphertextSha256 = "aa".repeat(32),
        plaintextSha256 = "bb".repeat(32),
        nonceHex = "cc".repeat(12),
        fileName = "photo.jpg",
        mediaType = mediaType,
        version = EncryptedMediaVersionFfi.V1,
        sourceEpoch = 99uL,
        dim = null,
        thumbhash = null,
    )
}
