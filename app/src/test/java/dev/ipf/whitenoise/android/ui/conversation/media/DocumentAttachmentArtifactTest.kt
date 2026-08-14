package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DocumentAttachmentArtifactTest {
    private val context: android.content.Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        java.io.File(context.cacheDir, dev.ipf.whitenoise.android.media.MediaCacheDirs.SHARED).deleteRecursively()
    }

    @Test
    fun materializationPublishesOnceAndReusesTheCompleteArtifact() =
        runBlocking {
            val payload = ByteArray(1024) { (it % 251).toByte() }
            var loads = 0
            val reference = reference()

            val first =
                materializeDocumentAttachment(context, "ab".repeat(32), 0, reference) {
                    loads += 1
                    payload
                }
            val second =
                materializeDocumentAttachment(context, "ab".repeat(32), 0, reference) {
                    error("a reusable artifact must not resolve the attachment again")
                }

            assertEquals(first, second)
            assertEquals(1, loads)
            assertTrue(first.name.endsWith(".apk"))
            assertArrayEquals(payload, first.readBytes())
        }

    @Test
    fun incompleteLegacyHashesCannotAliasDifferentMessages() =
        runBlocking {
            val reference =
                reference().copy(
                    plaintextSha256 = "",
                    ciphertextSha256 = "",
                )

            val first =
                materializeDocumentAttachment(context, "ab".repeat(32), 0, reference) {
                    byteArrayOf(1)
                }
            val second =
                materializeDocumentAttachment(context, "cd".repeat(32), 0, reference) {
                    byteArrayOf(2)
                }

            assertTrue(first != second)
            assertArrayEquals(byteArrayOf(1), first.readBytes())
            assertArrayEquals(byteArrayOf(2), second.readBytes())
        }

    private fun reference() =
        MediaAttachmentReferenceFfi(
            locators = emptyList(),
            ciphertextSha256 = "12".repeat(32),
            plaintextSha256 = "34".repeat(32),
            nonceHex = "56".repeat(12),
            fileName = "agent-build.apk",
            mediaType = "application/vnd.android.package-archive",
            version = EncryptedMediaVersionFfi.V2,
            sourceEpoch = 7uL,
            dim = null,
            thumbhash = null,
        )
}
