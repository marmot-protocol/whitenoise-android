package dev.ipf.whitenoise.android.share

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.ConversationAttachmentReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ImportedShareDocumentBoundaryTest {
    @Test
    fun sourceImageAboveSendBudgetStillUsesTheOrdinaryCompressionPath() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val source = java.io.File.createTempFile("large-share", ".png", context.cacheDir)
            val bitmap = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888)
            source.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            // PNG permits bytes after IEND; a sparse tail supplies a bounded large raw provider source.
            java.io.RandomAccessFile(source, "rw").use { it.setLength(33L * 1024 * 1024) }
            val files = PrivateShareFiles(context)
            val request =
                ShareRequest(
                    SharePayload(null, listOf(Uri.parse("content://external/large-image")), "image/png"),
                    null,
                    "large-image-boundary",
                )
            try {
                for (mime in listOf("image/png", "application/octet-stream")) {
                    val imported =
                        ShareFileImporter(
                            files,
                            { _, _ -> ShareSourceMetadata("large.png", mime, source.length()) },
                            { _, _ -> source.inputStream() },
                        ).import(request)
                    assertTrue(imported.payload.importErrors.isEmpty())
                    val persistence =
                        object : DraftPersistence {
                            override fun read(): Map<String, String> = emptyMap()

                            override fun write(
                                key: String,
                                value: String?,
                            ) = Unit
                        }
                    val state =
                        WhiteNoiseAppState(
                            context,
                            DraftStore(persistence),
                            { null },
                            emptyList(),
                            "fixture",
                            inboundShareTextStager = { _, _, _ -> },
                        )
                    val reader = ConversationAttachmentReader(state, context)
                    val uri = imported.payload.streamUris.single()
                    val attachment =
                        if (mime == "image/png") {
                            reader.readVisualDraft(uri)!!
                        } else {
                            reader.readDocumentDraft(uri)!!
                        }
                    assertEquals("image/jpeg", attachment.mediaType)
                    assertTrue(attachment.plaintextBytes.size < 32 * 1024 * 1024)
                }
            } finally {
                files.leases.releaseRequest(request.requestId)
                source.delete()
            }
        }

    @Test
    fun expiredSourceEntersTheOrdinaryPendingAttachmentBoundaryWithExactBytesAndMetadata() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            var sourceAvailable = true
            val bytes = "# Original markdown\n\u0000tail".toByteArray()
            val files = PrivateShareFiles(context)
            val importer =
                ShareFileImporter(
                    files,
                    { _, _ -> ShareSourceMetadata("../original.md", "text/markdown", bytes.size.toLong()) },
                    { _, _ ->
                        check(sourceAvailable)
                        ByteArrayInputStream(bytes)
                    },
                )
            val request =
                ShareRequest(
                    SharePayload(null, listOf(Uri.parse("content://external/source")), "*/*"),
                    shortcutId = null,
                    requestId = "document-boundary",
                )
            val imported = importer.import(request)
            sourceAvailable = false
            val persistence =
                object : DraftPersistence {
                    override fun read(): Map<String, String> = emptyMap()

                    override fun write(
                        key: String,
                        value: String?,
                    ) = Unit
                }
            val appState =
                WhiteNoiseAppState(
                    context,
                    DraftStore(persistence),
                    { null },
                    emptyList(),
                    "fixture",
                    inboundShareTextStager = { _, _, _ -> },
                )
            val attachment =
                ConversationAttachmentReader(appState, context)
                    .readDocumentDraft(imported.payload.streamUris.single())!!
            assertArrayEquals(bytes, attachment.plaintextBytes)
            assertEquals("text/markdown", attachment.mediaType)
            assertTrue(attachment.fileName.endsWith(".md"))
            assertTrue(!attachment.fileName.contains('/'))
            // This immutable PendingAttachment is the retained-upload/retry unit. It no longer needs either URI.
            files.leases.releaseRequest(request.requestId)
            assertArrayEquals(bytes, attachment.plaintextBytes)
        }

    @Test
    fun actualRetainedQueueRemovalAndDiscardReleaseEverySourceOwnerExactlyOnce() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val files = PrivateShareFiles(context)
            val importer =
                ShareFileImporter(
                    files,
                    { _, _ -> ShareSourceMetadata("file", null, null) },
                    { _, _ -> ByteArrayInputStream(byteArrayOf(1)) },
                )
            val request =
                ShareRequest(
                    SharePayload(null, listOf(Uri.parse("content://external/source")), null),
                    shortcutId = null,
                    requestId = "queue-removal",
                )
            val uri =
                importer
                    .import(request)
                    .payload.streamUris
                    .single()
            val lease = PrivateShareSendLease.acquire(context, listOf(uri))!!
            val releases = lease.ownerReleases(2) { it.release() }
            val persistence =
                object : DraftPersistence {
                    override fun read(): Map<String, String> = emptyMap()

                    override fun write(
                        key: String,
                        value: String?,
                    ) = Unit
                }
            val state =
                WhiteNoiseAppState(
                    context,
                    DraftStore(persistence),
                    { null },
                    emptyList(),
                    "fixture",
                    inboundShareTextStager = { _, _, _ -> },
                )
            val queue = state.retainedMediaUploads("fixture", "chat")
            val attachment =
                dev.ipf.whitenoise.android.state
                    .PendingAttachment(byteArrayOf(1), "application/octet-stream", "file")
            val first =
                dev.ipf.whitenoise.android.state
                    .RetainedMediaUpload(listOf(attachment), null)
            val second =
                dev.ipf.whitenoise.android.state
                    .RetainedMediaUpload(listOf(attachment), null)
            first.retainSource(releases[0])
            second.retainSource(releases[1])
            queue.put("first", first)
            queue.put("second", second)
            files.leases.releaseRequest(request.requestId)
            queue.remove("first")
            releases[0]() // A delayed durable acceptance cannot release this owner a second time.
            org.junit.Assert.assertNotNull(files.metadata(uri))
            queue.clear()
            org.junit.Assert.assertNull(files.resolve(uri))
            releases[1]()
        }

    @Test
    fun rejectedDestinationCommitPreservesConcurrentComposerRemovalAndAddition() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val files = PrivateShareFiles(context)
            val importer =
                ShareFileImporter(
                    files,
                    { _, _ -> ShareSourceMetadata("file", null, null) },
                    { _, _ -> ByteArrayInputStream(byteArrayOf(1)) },
                )
            val sources = listOf("old", "incoming", "concurrent").map { Uri.parse("content://x/$it") }
            val request =
                importer.import(
                    ShareRequest(
                        SharePayload(null, sources, null),
                        shortcutId = null,
                        requestId = "concurrent-rollback",
                    ),
                )
            val (old, incoming, concurrent) = request.payload.streamUris
            files.leases.saveShelf("account", "chat", listOf(old))
            val committed =
                retainShareAtDestination(
                    context,
                    "account",
                    listOf("chat"),
                    request.payload.copy(streamUris = listOf(incoming)),
                ) {
                    files.leases.changeShelf("account", "chat", listOf(old), listOf(concurrent))
                    false
                }
            org.junit.Assert.assertFalse(committed)
            assertEquals(listOf(concurrent), files.leases.loadShelf("account", "chat"))
            org.junit.Assert.assertNotNull(files.metadata(incoming))
            files.leases.releaseRequest(request.requestId)
            files.leases.saveShelf("account", "chat", emptyList())
            assertTrue(files.availableBytes() == PRIVATE_SHARE_BATCH_MAX_BYTES)
        }

    @Test
    fun rejectedDestinationCommitKeepsTheRecoverableRequestAndRestoresPriorShelves() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val files = PrivateShareFiles(context)
            val importer =
                ShareFileImporter(
                    files,
                    { _, _ -> ShareSourceMetadata("file", null, null) },
                    { _, _ -> ByteArrayInputStream(byteArrayOf(1)) },
                )
            val old =
                importer.import(
                    ShareRequest(
                        SharePayload(null, listOf(Uri.parse("content://external/old")), null),
                        shortcutId = null,
                        requestId = "old",
                    ),
                )
            val oldUri = old.payload.streamUris.single()
            files.leases.saveShelf("account", "chat", listOf(oldUri))
            val incoming =
                importer.import(
                    ShareRequest(
                        SharePayload(null, listOf(Uri.parse("content://external/new")), null),
                        shortcutId = null,
                        requestId = "new",
                    ),
                )
            val committed =
                retainShareAtDestination(
                    context,
                    "account",
                    listOf("chat", "second"),
                    incoming.payload,
                ) { false }
            org.junit.Assert.assertFalse(committed)
            assertEquals(listOf(oldUri), files.leases.loadShelf("account", "chat"))
            assertTrue(files.leases.loadShelf("account", "second").isEmpty())
            org.junit.Assert.assertNotNull(files.metadata(incoming.payload.streamUris.single()))
            files.leases.releaseRequest("new")
            files.leases.saveShelf("account", "chat", emptyList())
            org.junit.Assert.assertNull(files.resolve(oldUri))
        }
}
