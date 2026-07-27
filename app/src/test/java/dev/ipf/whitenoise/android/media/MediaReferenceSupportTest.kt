package dev.ipf.whitenoise.android.media

import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaLocatorFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class MediaReferenceSupportTest {
    @Test
    fun mimeClassificationStaysAndroidLocal() {
        assertTrue(MediaReferenceSupport.isImageMedia(reference(mediaType = "IMAGE/HEIC")))
        assertTrue(MediaReferenceSupport.isAudioMedia(reference(mediaType = "audio/mp4")))
        assertTrue(MediaReferenceSupport.isVideoMedia(reference(mediaType = "video/mp4")))
        assertFalse(MediaReferenceSupport.isImageMedia(reference(mediaType = "application/pdf")))
    }

    @Test
    fun safeDownloadReferenceAllowsPublicHttps() {
        val safe =
            MediaReferenceSupport.safeDownloadReference(reference()) {
                listOf(addr(93, 184, 216, 34))
            }

        assertNotNull(safe)
    }

    @Test
    fun safeDownloadReferenceBlocksDnsRebindingToPrivateAddress() {
        val safe =
            MediaReferenceSupport.safeDownloadReference(reference()) {
                listOf(addr(10, 0, 0, 5))
            }

        assertNull(safe)
    }

    @Test
    fun safeDownloadReferenceRejectsMixedPublicAndPrivateDnsAnswers() {
        // A rebinding-capable resolver can pad a private answer behind a public
        // one — any private address in the answer set must reject the download.
        val safe =
            MediaReferenceSupport.safeDownloadReference(reference()) {
                listOf(addr(93, 184, 216, 34), addr(10, 0, 0, 5))
            }

        assertNull(safe)
    }

    @Test
    fun safeDownloadReferenceBlocksLiteralPrivateHostWithoutDns() {
        var resolverCalled = false
        val safe =
            MediaReferenceSupport.safeDownloadReference(
                reference(locatorUrl = "https://127.0.0.1/blob"),
            ) {
                resolverCalled = true
                listOf(addr(8, 8, 8, 8))
            }

        assertNull(safe)
        assertFalse(resolverCalled)
    }

    @Test
    fun safeDownloadReferenceFailsClosedWhenDnsFails() {
        assertNull(MediaReferenceSupport.safeDownloadReference(reference()) { null })
    }

    @Test
    fun safeDownloadReferenceRejectsCleartextAndNonDefaultPorts() {
        var resolverCalled = false
        listOf(
            "http://media.example/blob",
            "https://media.example:6379/blob",
        ).forEach { locator ->
            assertNull(
                MediaReferenceSupport.safeDownloadReference(reference(locatorUrl = locator)) {
                    resolverCalled = true
                    listOf(addr(93, 184, 216, 34))
                },
            )
        }
        assertFalse(resolverCalled)
    }

    @Test
    fun safeDownloadReferenceCanonicalizesAuthorityBeforeNativeFetch() {
        val safe =
            MediaReferenceSupport.safeDownloadReference(
                reference(locatorUrl = " HTTPS://MEDIA.EXAMPLE:443/blob?token=abc#client "),
            ) {
                listOf(addr(93, 184, 216, 34))
            }

        assertEquals("https://media.example/blob?token=abc#client", safe?.locators?.single()?.value)
    }

    @Test
    fun safeDownloadReferencePreservesUnsupportedLocators() {
        val unsupported = MediaLocatorFfi(kind = "ipfs-v1", value = "https://127.0.0.1/blob")
        val safe =
            MediaReferenceSupport.safeDownloadReference(
                reference(locators = listOf(unsupported)),
            ) {
                error("unsupported locators must not resolve")
            }

        assertEquals(listOf(unsupported), safe?.locators)
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

    private fun addr(vararg octets: Int): InetAddress = InetAddress.getByAddress(octets.map(Int::toByte).toByteArray())
}
