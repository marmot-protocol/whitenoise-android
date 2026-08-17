package dev.ipf.whitenoise.android.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.MarmotAndroid
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.marmotkit.parseMediaImetaTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the packaged native MDK parser with source/destination forward references. */
@RunWith(AndroidJUnit4::class)
class ForwardMediaReferenceFfiIntegrationTest {
    @Test
    fun freshDestinationReferencesRemainDistinctForEveryForwardableMediaKind() {
        MarmotAndroid.initialize(InstrumentationRegistry.getInstrumentation().targetContext)

        listOf(
            "photo.jpg" to "image/jpeg",
            "video.mp4" to "video/mp4",
            "voice.ogg" to "audio/ogg",
            "document.pdf" to "application/pdf",
        ).forEachIndexed { index, (fileName, mediaType) ->
            val source = parseMediaImetaTag(referenceTag(index * 2, fileName, mediaType), 7uL)
            val destination = parseMediaImetaTag(referenceTag(index * 2 + 1, fileName, mediaType), 11uL)

            assertEquals(mediaType, destination.mediaType)
            assertEquals(fileName, destination.fileName)
            assertEquals(11uL, destination.sourceEpoch)
            assertNotEquals(source.ciphertextSha256, destination.ciphertextSha256)
            assertNotEquals(source.plaintextSha256, destination.plaintextSha256)
            assertNotEquals(source.nonceHex, destination.nonceHex)
            assertNotEquals(source.locators, destination.locators)
        }
    }

    private fun referenceTag(
        seed: Int,
        fileName: String,
        mediaType: String,
    ): MessageTagFfi {
        val ciphertextHash = hexByte(seed + 1).repeat(32)
        val plaintextHash = hexByte(seed + 2).repeat(32)
        val nonce = hexByte(seed + 3).repeat(12)
        return MessageTagFfi(
            values =
                listOf(
                    "imeta",
                    "v encrypted-media-v1",
                    "locator blossom-v1 https://media.example/$ciphertextHash.bin",
                    "ciphertext_sha256 $ciphertextHash",
                    "plaintext_sha256 $plaintextHash",
                    "nonce $nonce",
                    "m $mediaType",
                    "filename $fileName",
                ),
        )
    }

    private fun hexByte(value: Int): String = value.toString(16).padStart(2, '0')
}
