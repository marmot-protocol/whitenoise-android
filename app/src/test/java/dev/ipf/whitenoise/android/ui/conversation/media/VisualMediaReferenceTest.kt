package dev.ipf.whitenoise.android.ui.conversation.media

import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualMediaReferenceTest {
    @Test
    fun incomingEpochZeroReferenceIsUpgradedBeforeTransfer() =
        runBlocking {
            val fallback = reference(sourceEpoch = 0uL)
            val authoritative = reference(sourceEpoch = 7uL)
            var resolverCalled = false

            val resolved =
                authoritativeVisualMediaReference(fallback, mine = false) {
                    resolverCalled = true
                    authoritative
                }

            assertTrue(resolverCalled)
            assertEquals(authoritative, resolved)
        }

    @Test
    fun authoritativeAndOwnReferencesBypassResolution() =
        runBlocking {
            var resolverCalled = false
            val incoming = reference(sourceEpoch = 7uL)
            val ownOptimistic = reference(sourceEpoch = 0uL)
            val resolver: suspend () -> MediaAttachmentReferenceFfi = {
                resolverCalled = true
                reference(sourceEpoch = 9uL)
            }

            assertEquals(incoming, authoritativeVisualMediaReference(incoming, mine = false, resolver))
            assertEquals(ownOptimistic, authoritativeVisualMediaReference(ownOptimistic, mine = true, resolver))
            assertFalse(resolverCalled)
        }

    private fun reference(sourceEpoch: ULong) =
        MediaAttachmentReferenceFfi(
            locators = emptyList(),
            ciphertextSha256 = "aa".repeat(32),
            plaintextSha256 = "bb".repeat(32),
            nonceHex = "cc".repeat(12),
            fileName = "media.bin",
            mediaType = "application/octet-stream",
            version = EncryptedMediaVersionFfi.V1,
            sourceEpoch = sourceEpoch,
            dim = null,
            thumbhash = null,
        )
}
