package dev.ipf.whitenoise.android.share

import android.net.Uri
import dev.ipf.whitenoise.android.ui.conversation.media.FILE_BACKED_ATTACHMENT_MAX_BYTES
import dev.ipf.whitenoise.android.ui.conversation.media.FILE_BACKED_DISK_RESERVE_BYTES
import dev.ipf.whitenoise.android.ui.conversation.media.FILE_BACKED_TAG_BYTES
import dev.ipf.whitenoise.android.ui.conversation.media.FileBackedSendLimits
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream
import java.nio.file.Files

/** External imports, restored ownership and error snapshots consume the same file-backed transport policy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SharedFileImportLimitParityCoverageTest {
    private val root = Files.createTempDirectory("share-limit-parity").toFile()
    private val authority = "test.private-share"
    private val source = Uri.parse("content://external/document")

    /** All generated originals and leases belong only to this test. */
    @After fun cleanup() {
        root.deleteRecursively()
    }

    /** Native absence remains conservative; a smaller native batch constrains both document and image input. */
    @Test fun nativePolicyAndLegacyFallbackNeverAuthorizeBeyondTheirSender() {
        assertEquals(LEGACY_SHARE_IMPORT_LIMITS, shareImportLimits(null))
        assertEquals(PRIVATE_SHARE_DOCUMENT_MAX_BYTES, shareImportLimits(null).documentBytes)
        val limits = shareImportLimits(FileBackedSendLimits(FILE_BACKED_ATTACHMENT_MAX_BYTES, 48L))
        assertEquals(32L, limits.documentBytes)
        assertEquals(32L, limits.imageBytes)
        assertEquals(0L, limits.remainingPlaintext(32L))
        val native = shareImportLimits(FileBackedSendLimits(FILE_BACKED_ATTACHMENT_MAX_BYTES, 900L * MIB))
        assertEquals(FILE_BACKED_ATTACHMENT_MAX_BYTES, native.documentBytes)
        assertEquals(64L * MIB, native.imageBytes)
    }

    /** A real 56 MiB ZIP-like source and a 64 MiB document stream without allocating either whole body. */
    @Test fun largeOpaqueDocumentsImportAndRemainRetryableAfterRepositoryRecreation() =
        runBlocking {
            val limits = shareImportLimits(FileBackedSendLimits(FILE_BACKED_ATTACHMENT_MAX_BYTES, 900L * MIB))
            val files = PrivateShareFiles(root, authority, limits)
            val sizes = listOf(56L * MIB, 64L * MIB)
            var largestRead = 0
            val importer =
                ShareFileImporter(
                    files,
                    { _, _ -> ShareSourceMetadata("archive.zip", "application/zip", 1L) },
                    { uri, _ ->
                        sizedStream(sizes[uri.lastPathSegment!!.toInt()]) { largestRead = maxOf(largestRead, it) }
                    },
                    timeoutMs = Long.MAX_VALUE,
                )
            val imported = importer.import(request(sizes.indices.map { Uri.parse("content://external/$it") }))
            assertTrue(imported.payload.importErrors.isEmpty())
            assertEquals(sizes, imported.payload.streamUris.map { files.metadata(it)!!.getLong("size") })
            assertTrue("copy reads stay bounded", largestRead <= 16 * 1024)
            files.leases.saveShelf("account", "chat", imported.payload.streamUris)
            files.leases.releaseRequest(imported.requestId)
            val restored = PrivateShareFiles(root, authority, limits)
            assertEquals(imported.payload.streamUris, restored.leases.loadShelf("account", "chat"))
            assertEquals(0, restored.leases.loadShelf("other-account", "chat").size)
            assertEquals(imported, decodePendingShareRequest(encodePendingShareRequest(imported), imported.requestId))
            assertEquals(limits.documentBytes, imported.payload.importDocumentLimitBytes)
            assertEquals(limits.batchCiphertextBytes, imported.payload.importBatchLimitBytes)
        }

    /** Actual streamed bytes, not a false declared size, enforce the effective native plaintext ceiling. */
    @Test fun lyingProviderIsStoppedAtNativeBoundPlusOneAndLeavesNoPartialOriginal() =
        runBlocking {
            val limits = shareImportLimits(FileBackedSendLimits(64L, 64L))
            var read = 0
            val importer =
                ShareFileImporter(
                    PrivateShareFiles(root, authority, limits),
                    { _, _ -> ShareSourceMetadata("archive.zip", "application/zip", 1L) },
                    { _, _ -> sizedStream(49L) { read += it } },
                )
            val imported = importer.import(request(listOf(source)))
            assertEquals(listOf(ShareImportError.FileTooLarge), imported.payload.importErrors)
            assertEquals(49, read)
            assertTrue(root.listFiles().orEmpty().none { it.extension == "bin" })
            val exact =
                ShareFileImporter(
                    PrivateShareFiles(root, authority, limits),
                    { _, _ -> ShareSourceMetadata("exact.zip", "application/zip", null) },
                    { _, _ -> sizedStream(48L) },
                ).import(request(listOf(source)))
            assertTrue(exact.payload.importErrors.isEmpty())
            val exactFile = exact.payload.streamUris.single()
            assertEquals(48L, PrivateShareFiles(root, authority, limits).metadata(exactFile)!!.getLong("size"))
        }

    /** Header-confirmed images retain their smaller decoder-input ceiling even when a provider reports generic MIME. */
    @Test fun sniffedImageMimeAndBoundSurvivePrivateMetadataRecovery() =
        runBlocking {
            val limits = ShareImportLimits(16L, 64L, 128L)
            val files = PrivateShareFiles(root, authority, limits)
            val bytes = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 0, 0, 0, 0, 0)
            val importer =
                ShareFileImporter(
                    files,
                    { _, _ -> ShareSourceMetadata("image.bin", "application/octet-stream", null) },
                    { _, _ -> bytes.inputStream() },
                )
            val imported = importer.import(request(listOf(source)))
            assertTrue(imported.payload.importErrors.isEmpty())
            val uri = imported.payload.streamUris.single()
            assertEquals("image/png", files.metadata(uri)!!.getString("mime"))
            val file = files.resolve(uri)!!
            file.appendBytes(byteArrayOf(0))
            writePrivateShareJson(
                File(root, "${file.nameWithoutExtension}.json"),
                JSONObject().put("name", "image.bin").put("mime", "image/png").put("size", 17L),
            )
            assertNull(PrivateShareFiles(root, authority, limits).metadata(uri))
        }

    /** Aggregate accounting includes one tag per item and a later fitting source still survives a rejection. */
    @Test fun aggregateAndShelfUseTheSameTagBudget() =
        runBlocking {
            val limits = ShareImportLimits(64L, 64L, 80L)
            val files = PrivateShareFiles(root, authority, limits)
            val sizes = listOf(32L, 33L, 1L)
            val importer =
                ShareFileImporter(
                    files,
                    { _, _ -> ShareSourceMetadata("file.bin", "application/octet-stream", null) },
                    { uri, _ -> sizedStream(sizes[uri.lastPathSegment!!.toInt()]) },
                )
            val imported = importer.import(request(sizes.indices.map { Uri.parse("content://external/$it") }))
            assertEquals(listOf(ShareImportError.BatchTooLarge), imported.payload.importErrors)
            assertEquals(listOf(32L, 1L), imported.payload.streamUris.map { files.metadata(it)!!.getLong("size") })
            assertEquals(
                imported.payload.streamUris,
                files.leases.saveShelf("account", "chat", imported.payload.streamUris),
            )
            assertEquals(80L - 33L - 2L * FILE_BACKED_TAG_BYTES, files.availableBytes())
        }

    /** Both early reserve exhaustion and reserve loss after copying are storage failures, never size errors. */
    @Test fun storageReserveRejectsBeforeOpeningAndRechecksBeforeCommit() =
        runBlocking {
            val limits = ShareImportLimits(64L, 64L, 128L)
            var free = FILE_BACKED_DISK_RESERVE_BYTES
            val files = PrivateShareFiles(root, authority, limits) { free }
            val importer =
                ShareFileImporter(
                    files,
                    { _, _ -> ShareSourceMetadata("file.bin", null, null) },
                    { _, _ -> sizedStream(8L) },
                )
            assertEquals(
                listOf(ShareImportError.Storage),
                importer.import(request(listOf(source))).payload.importErrors,
            )
            free += 128L
            val imported =
                importer.import(request(listOf(source))) { progress ->
                    if (progress.bytes == 8L) free = FILE_BACKED_DISK_RESERVE_BYTES - 1
                }
            assertEquals(listOf(ShareImportError.Storage), imported.payload.importErrors)
            assertTrue(root.listFiles().orEmpty().none { it.extension == "bin" })
        }

    private fun request(uris: List<Uri>) =
        ShareRequest(
            SharePayload("caption", uris, "application/zip"),
            null,
            "request",
        )

    /** Emits a ZIP-like prefix and zero body from bounded reads rather than retaining a large fixture array. */
    private fun sizedStream(
        length: Long,
        observedRead: (Int) -> Unit = {},
    ): InputStream =
        object : InputStream() {
            private var position = 0L
            private val prefix = byteArrayOf(80, 75, 3, 4)

            override fun read(): Int =
                if (position >= length) {
                    -1
                } else {
                    val value = if (position < prefix.size) prefix[position.toInt()].toInt() else 0
                    position++
                    value
                }

            override fun read(
                buffer: ByteArray,
                off: Int,
                len: Int,
            ): Int {
                if (position >= length) return -1
                val count = minOf(len.toLong(), length - position).toInt()
                java.util.Arrays.fill(buffer, off, off + count, 0.toByte())
                for (index in 0 until count) {
                    if (position + index < prefix.size) buffer[off + index] = prefix[(position + index).toInt()]
                }
                position += count
                observedRead(count)
                return count
            }
        }

    private companion object {
        const val MIB = 1024L * 1024L
    }
}
