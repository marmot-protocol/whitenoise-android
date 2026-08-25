package dev.ipf.whitenoise.android.media

import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaLocatorFfi
import dev.ipf.marmotkit.MessageTagFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaReferenceSupportTest {
    @Test
    fun validParserResultsPreserveInputOrder() {
        val parserCalls = mutableListOf<Pair<String, ULong>>()
        val tags =
            listOf(
                MessageTagFfi(listOf("p", "not-media")),
                MessageTagFfi(listOf("imeta", "first")),
                MessageTagFfi(listOf("imeta", "second")),
            )

        val parsed =
            MediaReferenceSupport.parseAllImetaTags(tags, 42uL) { tag, sourceEpoch ->
                val marker = tag.values[1]
                parserCalls += marker to sourceEpoch
                reference(fileName = "$marker.bin", sourceEpoch = sourceEpoch)
            }

        assertEquals(listOf("first.bin", "second.bin"), parsed.map { it.fileName })
        assertEquals(listOf("first" to 42uL, "second" to 42uL), parserCalls)
    }

    @Test
    fun malformedParserResultsAreOmittedWithoutReorderingValidResults() {
        val tags =
            listOf(
                MessageTagFfi(listOf("imeta", "first")),
                MessageTagFfi(listOf("imeta")),
                MessageTagFfi(listOf("e", "not-media")),
                MessageTagFfi(listOf("imeta", "second")),
            )

        val parsed =
            MediaReferenceSupport.parseAllImetaTags(tags, 7uL) { tag, sourceEpoch ->
                val marker =
                    tag.values.getOrNull(1)
                        ?: throw MarmotKitException.InvalidMediaReference("synthetic malformed tag")
                reference(fileName = "$marker.bin", sourceEpoch = sourceEpoch)
            }

        assertEquals(listOf("first.bin", "second.bin"), parsed.map { it.fileName })
    }

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
        fileName: String = "photo.jpg",
        sourceEpoch: ULong = 99uL,
    ) = MediaAttachmentReferenceFfi(
        locators = locators,
        ciphertextSha256 = "aa".repeat(32),
        plaintextSha256 = "bb".repeat(32),
        nonceHex = "cc".repeat(12),
        fileName = fileName,
        mediaType = mediaType,
        version = EncryptedMediaVersionFfi.V1,
        sourceEpoch = sourceEpoch,
        dim = null,
        thumbhash = null,
    )
}
