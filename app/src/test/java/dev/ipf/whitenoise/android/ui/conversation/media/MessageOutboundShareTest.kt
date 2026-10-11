package dev.ipf.whitenoise.android.ui.conversation.media

import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaLocatorFfi
import dev.ipf.whitenoise.android.FileProviderStrategyCacheRule
import dev.ipf.whitenoise.android.media.AttachmentPlaintext
import dev.ipf.whitenoise.android.media.DiskByteCacheLease
import dev.ipf.whitenoise.android.media.MediaCacheDirs
import dev.ipf.whitenoise.android.state.PendingAttachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MessageOutboundShareTest {
    @get:Rule
    val fileProviderStrategyCacheRule = FileProviderStrategyCacheRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** Text, captions and attachments each make a message shareable; an incomplete attachment set does not. */
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

    /** A pending file-backed item has no in-memory copy, so its message is shareable only once confirmed. */
    @Test
    fun pendingFileBackedItemIsShareableOnlyAfterConfirmation() {
        val directory = File(context.cacheDir, "share-gate-${System.nanoTime()}")
        val read = readStagedDocument(directory, 4) { java.io.ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)) }
        val source = (read as StagedDocumentRead.Success).source
        try {
            val fileBacked = PendingAttachment(ByteArray(0), "application/pdf", "large.pdf", sourceFile = source)
            assertFalse(messageHasShareablePayload("caption", emptyList(), listOf(fileBacked)))
            assertFalse(
                messageHasShareablePayload(null, emptyList(), listOf(pending("a.jpg", "image/jpeg"), fileBacked)),
            )
            assertTrue(
                messageHasShareablePayload(null, listOf(reference("large.pdf", "application/pdf")), listOf(fileBacked)),
            )
        } finally {
            source.close()
            directory.deleteRecursively()
        }
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

    /** Staging sanitizes each name and publishes a readable FileProvider stream for every source. */
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

            val staged = stageMessageShareStreams(context, sources) { plaintext(expected.getValue(it.attachmentIndex)) }
            val streams = staged.streams

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
            // The receiving app sees the sanitized original name, never the
            // collision-safe cache name or anything the sanitizer removed.
            assertEquals(listOf("release.pdf", "notesprivate.txt"), streams.map { displayName(it.uri) })
        }

    /** Recipients read each attachment's own name while the cache files behind equal names stay distinct. */
    @Test
    fun recipientsSeeTheAttachmentsOwnNamesForConfirmedRetainedAndDuplicateFiles() =
        runTest {
            clearSharedFiles()
            val confirmed =
                messageShareAttachmentSources(
                    references =
                        listOf(
                            reference("story.pdf", "application/pdf"),
                            reference("story.pdf", "application/pdf"),
                        ),
                    retained = emptyList(),
                )
            val retained =
                messageShareAttachmentSources(
                    references = emptyList(),
                    retained = listOf(pending("voice note.m4a", "audio/mp4")),
                )

            val confirmedStreams =
                stageMessageShareStreams(context, confirmed) { source ->
                    plaintext("bytes ${source.attachmentIndex}".encodeToByteArray())
                }.streams
            val retainedStreams =
                stageMessageShareStreams(context, retained) { plaintext("voice".encodeToByteArray()) }.streams

            assertEquals(listOf("story.pdf", "story.pdf"), confirmedStreams.map { displayName(it.uri) })
            assertEquals(listOf("voice note.m4a"), retainedStreams.map { displayName(it.uri) })
            assertEquals(2, confirmedStreams.map { it.uri }.distinct().size)
            val cacheNames = sharedFiles().map(File::getName)
            assertEquals(3, cacheNames.distinct().size)
            assertTrue(cacheNames.all { it.startsWith("message_") })
            (confirmedStreams + retainedStreams).forEach { stream ->
                assertFalse(displayName(stream.uri).startsWith("message_"))
            }
        }

    /** A source that fails to resolve deletes everything already staged for the set. */
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
                            plaintext("one".encodeToByteArray())
                        } else {
                            throw IOException("stale attachment")
                        }
                    }
                }.exceptionOrNull()

            assertTrue(failure is IOException)
            assertTrue(sharedFiles().isEmpty())
        }

    /** Cancellation while resolving propagates and deletes everything already staged for the set. */
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
                        if (it.attachmentIndex == 0) plaintext("one".encodeToByteArray()) else throw cancellation
                    }
                }.exceptionOrNull()

            assertTrue(observed === cancellation)
            assertTrue(sharedFiles().isEmpty())
        }

    /** A chooser that fails to launch deletes the completed staged set. */
    @Test
    fun launchFailureDeletesCompletedPlaintextSet() =
        runTest {
            clearSharedFiles()
            val staged =
                stageMessageShareStreams(
                    context,
                    messageShareAttachmentSources(
                        references = listOf(reference("one.txt", "text/plain")),
                        retained = emptyList(),
                    ),
                ) { plaintext("one".encodeToByteArray()) }

            val failure =
                runCatching {
                    launchStagedMessageShare(staged) { Result.failure(IOException("chooser launch failed")) }
                }.exceptionOrNull()

            assertTrue(failure is IOException)
            assertTrue(sharedFiles().isEmpty())
        }

    /** Cancellation while launching the chooser deletes the completed staged set. */
    @Test
    fun launchCancellationDeletesCompletedPlaintextSet() =
        runTest {
            clearSharedFiles()
            val staged =
                stageMessageShareStreams(
                    context,
                    messageShareAttachmentSources(
                        references = listOf(reference("one.txt", "text/plain")),
                        retained = emptyList(),
                    ),
                ) { plaintext("one".encodeToByteArray()) }
            val cancellation = CancellationException("screen closed")

            val observed =
                runCatching {
                    launchStagedMessageShare(staged) { throw cancellation }
                }.exceptionOrNull()

            assertTrue(observed === cancellation)
            assertTrue(sharedFiles().isEmpty())
        }

    /**
     * A large attachment's private file lease stages complete under the raised share ceiling, streamed or moved
     * rather than read into memory, and the lease is released.
     */
    @Test
    fun aLargeLeaseStagesCompleteAndIsReleased() =
        runTest {
            clearSharedFiles()
            val leaseDirectory = File(context.cacheDir, MediaCacheDirs.NATIVE_ATTACHMENT_LEASES).apply { mkdirs() }
            val lease = File(leaseDirectory, "video.lease")
            val videoBytes = 129L * 1024L * 1024L
            RandomAccessFile(lease, "rw").use { it.setLength(videoBytes) }
            val sources =
                messageShareAttachmentSources(
                    references = listOf(reference("clip.mp4", "video/mp4")),
                    retained = emptyList(),
                )

            val staged =
                stageMessageShareStreams(context, sources) { AttachmentPlaintext.Lease(DiskByteCacheLease(lease)) }

            assertEquals(videoBytes, sharedFiles().single().length())
            assertEquals("clip.mp4", displayName(staged.streams.single().uri))
            assertFalse(lease.exists())
            staged.deletePlaintext()
        }

    /** A lease on the shared cache's volume is moved into the share, not copied, so a large file is written once. */
    @Test
    fun aLeaseIsMovedIntoTheShareRatherThanCopied() =
        runTest {
            clearSharedFiles()
            val leaseDirectory = File(context.cacheDir, MediaCacheDirs.NATIVE_ATTACHMENT_LEASES).apply { mkdirs() }
            val lease = File(leaseDirectory, "document.lease").apply { writeBytes("pdf".encodeToByteArray()) }
            val inode = runCatching { Files.getAttribute(lease.toPath(), "unix:ino") }.getOrNull()
            assumeTrue(inode != null)
            val sources =
                messageShareAttachmentSources(
                    references = listOf(reference("report.pdf", "application/pdf")),
                    retained = emptyList(),
                )

            val staged =
                stageMessageShareStreams(context, sources) { AttachmentPlaintext.Lease(DiskByteCacheLease(lease)) }

            assertEquals(inode, Files.getAttribute(sharedFiles().single().toPath(), "unix:ino"))
            staged.deletePlaintext()
        }

    /** A cancelled Share stops copying a large source at its next chunk and leaves nothing staged. */
    @Test
    fun aCancelledShareStopsCopyingAndLeavesNothingStaged() =
        runBlocking {
            clearSharedFiles()
            val sources =
                messageShareAttachmentSources(
                    references = listOf(reference("clip.mp4", "video/mp4")),
                    retained = emptyList(),
                )
            val firstChunk = CompletableDeferred<Unit>()
            var chunks = 0
            val chunk = ByteArray(64 * 1024)
            val endless =
                object : AttachmentPlaintext {
                    override val size: Long = 64L * 1024L * 1024L

                    override fun copyTo(output: OutputStream) {
                        repeat((size / chunk.size).toInt()) {
                            output.write(chunk)
                            chunks++
                            firstChunk.complete(Unit)
                            Thread.sleep(1L)
                        }
                    }

                    override fun close() = Unit
                }

            val share = launch(Dispatchers.Default) { stageMessageShareStreams(context, sources) { endless } }
            firstChunk.await()
            share.cancelAndJoin()

            assertTrue(chunks < 1024)
            assertTrue(sharedFiles().isEmpty())
        }

    /** Every resolved source is closed, including those staged before a later one fails. */
    @Test
    fun everyResolvedSourceIsClosedWhenALaterOneFails() =
        runTest {
            clearSharedFiles()
            val sources =
                messageShareAttachmentSources(
                    references = listOf(reference("one.txt", "text/plain"), reference("two.txt", "text/plain")),
                    retained = emptyList(),
                )
            val closed = mutableListOf<Int>()

            val failure =
                runCatching {
                    stageMessageShareStreams(context, sources) { source ->
                        object : AttachmentPlaintext {
                            override val size: Long = if (source.attachmentIndex == 0) 3L else 4L

                            override fun copyTo(output: OutputStream) {
                                if (source.attachmentIndex == 1) throw IOException("provider closed")
                                output.write("one".encodeToByteArray())
                            }

                            override fun close() {
                                closed += source.attachmentIndex
                            }
                        }
                    }
                }.exceptionOrNull()

            assertTrue(failure is IOException)
            assertEquals(listOf(0, 1), closed)
            assertTrue(sharedFiles().isEmpty())
        }

    @Test
    fun aggregateShareSizeIsBoundedBeforePublication() {
        val almostFull = OUTBOUND_SHARE_MAX_BYTES - 8L
        assertEquals(OUTBOUND_SHARE_MAX_BYTES, boundedShareTotal(almostFull, 8L))
        assertTrue(runCatching { boundedShareTotal(almostFull, 9L) }.exceptionOrNull() is IOException)
    }

    /** A share source held in memory, as a small attachment's is. */
    private fun plaintext(bytes: ByteArray): AttachmentPlaintext = AttachmentPlaintext.Bytes(bytes)

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

    /** What a receiving app is told the shared file is called. */
    private fun displayName(uri: android.net.Uri): String =
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)!!
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            }

    private fun clearSharedFiles() {
        sharedFiles().forEach(File::delete)
    }

    private fun sharedFiles(): List<File> =
        File(context.cacheDir, MediaCacheDirs.SHARED)
            .listFiles()
            ?.filter(File::isFile)
            .orEmpty()
}
