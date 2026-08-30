package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaLocatorFfi
import dev.ipf.whitenoise.android.media.AttachmentPlaintextCache
import dev.ipf.whitenoise.android.media.MediaCacheDirs
import dev.ipf.whitenoise.android.state.PendingAttachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MessageOutboundShareTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun clearProviderStrategyBeforeTest() = clearFileProviderStrategyCache()

    @After
    fun clearProviderStrategyAfterTest() = clearFileProviderStrategyCache()

    @Test
    fun payloadDecisionCoversTextFileCaptionAndAttachmentOnly() {
        assertTrue(messageHasShareablePayload("hello", emptyList(), emptyList()))
        assertTrue(messageHasShareablePayload(null, listOf(reference("photo.jpg", "image/jpeg")), emptyList()))
        assertTrue(messageHasShareablePayload("caption", emptyList(), listOf(pending("clip.mp4", "video/mp4"))))
        assertFalse(messageHasShareablePayload("  ", emptyList(), emptyList()))
        assertFalse(
            messageHasShareablePayload(
                text = "caption must not hide a dropped attachment",
                references = listOf(reference("one.pdf", "application/pdf")),
                retained = emptyList(),
                protocolAttachmentCount = 2,
            ),
        )
    }

    @Test
    fun confirmedProtocolIdentitiesReplaceRetainedOptimisticDescriptors() {
        val confirmed = listOf(reference("current.pdf", "application/pdf"))
        val sources = messageShareAttachmentSources(confirmed, listOf(pending("stale.pdf", "application/pdf")))

        assertEquals(1, sources.size)
        assertTrue(sources.single() is MessageShareAttachmentSource.Confirmed)
        assertEquals("current.pdf", sources.single().fileName)
    }

    @Test
    fun retainedOptimisticAttachmentsRemainOrderedUntilProjectionArrives() {
        val sources =
            messageShareAttachmentSources(
                references = emptyList(),
                retained = listOf(pending("one.txt", "text/plain"), pending("two.pdf", "application/pdf")),
            )

        assertEquals(listOf(0, 1), sources.map(MessageShareAttachmentSource::attachmentIndex))
        assertEquals(listOf("one.txt", "two.pdf"), sources.map(MessageShareAttachmentSource::fileName))
    }

    @Test
    fun stagingSanitizesNamesAndPublishesEveryReadableFileProviderStream() =
        runTest {
            clearSharedFiles()
            val sources =
                messageShareAttachmentSources(
                    references =
                        listOf(
                            reference("../../release.pdf", "application/pdf"),
                            reference("notes\n\u202Eprivate.txt", "text/plain"),
                        ),
                    retained = emptyList(),
                )
            val expected = mapOf(0 to "pdf bytes".encodeToByteArray(), 1 to "notes".encodeToByteArray())

            val streams = stageMessageShareStreams(context, sources) { expected.getValue(it.attachmentIndex) }

            assertEquals(2, streams.size)
            streams.forEachIndexed { index, stream ->
                assertEquals("content", stream.uri.scheme)
                assertEquals("${context.packageName}.fileprovider", stream.uri.authority)
                val bytes = context.contentResolver.openInputStream(stream.uri)!!.use { it.readBytes() }
                assertArrayEquals(expected.getValue(index), bytes)
                assertFalse(stream.uri.toString().contains(".."))
                assertFalse(stream.uri.toString().contains("%0A", ignoreCase = true))
                assertFalse(stream.uri.toString().contains("%E2%80%AE", ignoreCase = true))
            }
        }

    @Test
    fun materializationFailureDeletesPartialPlaintextSet() =
        runTest {
            clearSharedFiles()
            val sources =
                messageShareAttachmentSources(
                    references = listOf(reference("one.txt", "text/plain"), reference("missing.txt", "text/plain")),
                    retained = emptyList(),
                )

            val failure =
                runCatching {
                    stageMessageShareStreams(context, sources) {
                        if (it.attachmentIndex == 0) {
                            "one".encodeToByteArray()
                        } else {
                            throw IOException("stale attachment")
                        }
                    }
                }.exceptionOrNull()

            assertTrue(failure is IOException)
            assertTrue(sharedFiles().isEmpty())
        }

    @Test
    fun cancellationPropagatesAndDeletesPartialPlaintextSet() =
        runTest {
            clearSharedFiles()
            val sources =
                messageShareAttachmentSources(
                    references = listOf(reference("one.txt", "text/plain"), reference("two.txt", "text/plain")),
                    retained = emptyList(),
                )
            val cancellation = CancellationException("screen closed")

            val observed =
                runCatching {
                    stageMessageShareStreams(context, sources) {
                        if (it.attachmentIndex == 0) "one".encodeToByteArray() else throw cancellation
                    }
                }.exceptionOrNull()

            assertTrue(observed === cancellation)
            assertTrue(sharedFiles().isEmpty())
        }

    @Test
    fun aggregateShareSizeIsBoundedBeforePublication() {
        val almostFull = AttachmentPlaintextCache.SHARED_MAX_DIRECTORY_BYTES - 8L
        assertEquals(AttachmentPlaintextCache.SHARED_MAX_DIRECTORY_BYTES, boundedShareTotal(almostFull, 8L))
        assertTrue(runCatching { boundedShareTotal(almostFull, 9L) }.exceptionOrNull() is IOException)
    }

    private fun reference(
        fileName: String,
        mediaType: String,
    ) = MediaAttachmentReferenceFfi(
        locators = listOf(MediaLocatorFfi(kind = "blossom-v1", value = "https://cdn.example.test/blob")),
        ciphertextSha256 = "a".repeat(64),
        plaintextSha256 = "b".repeat(64),
        nonceHex = "c".repeat(48),
        fileName = fileName,
        mediaType = mediaType,
        version = EncryptedMediaVersionFfi.V1,
        sourceEpoch = 1uL,
        dim = null,
        thumbhash = null,
    )

    private fun pending(
        fileName: String,
        mediaType: String,
    ) = PendingAttachment("pending".encodeToByteArray(), mediaType, fileName)

    private fun clearSharedFiles() {
        sharedFiles().forEach(File::delete)
    }

    private fun sharedFiles(): List<File> =
        File(context.cacheDir, MediaCacheDirs.SHARED)
            .listFiles()
            ?.filter(File::isFile)
            .orEmpty()

    private fun clearFileProviderStrategyCache() {
        val cacheField = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (cacheField.get(null) as MutableMap<String, *>).clear()
    }
}
