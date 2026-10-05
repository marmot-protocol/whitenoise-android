package dev.ipf.whitenoise.android.share

import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files

/** Provider size hints cannot bypass per-file, item-count or remaining private-storage budgets. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ShareFileImporterBudgetTest {
    private lateinit var root: File
    private lateinit var files: PrivateShareFiles
    private val source = Uri.parse("content://external/document")

    /** Gives each budget case an empty private store so prior ownership cannot alter available space. */
    @Before fun setup() {
        root = Files.createTempDirectory("private-intake-budget").toFile()
        files = PrivateShareFiles(root, "test.private-share")
    }

    /** Removes sparse and partial fixture files after success or rejection. */
    @After fun cleanup() {
        root.deleteRecursively()
    }

    /** Uses sparse retained files to verify that intake uses the remaining budget without deleting old drafts. */
    @Test fun tinyShareUsesTheActualFreeSpaceAlongsideLargeRetainedDrafts() =
        runBlocking {
            val retained =
                List(4) { index ->
                    val size = if (index < 3) PRIVATE_SHARE_MAX_BYTES else 1024L * 1024
                    val (uri, file) = files.newFile()
                    java.io.RandomAccessFile(file, "rw").use { it.setLength(size) }
                    files.finish(uri, "retained.png", "image/png", size)
                    uri
                }
            files.leases.saveShelf("account", "existing", retained)
            val importer =
                ShareFileImporter(
                    files,
                    { _, _ -> ShareSourceMetadata("tiny.txt", "text/plain", null) },
                    { _, _ -> ByteArrayInputStream(byteArrayOf(1)) },
                )
            val imported = importer.import(request(listOf(source)))
            assertTrue(imported.payload.importErrors.isEmpty())
            assertEquals(1L, files.metadata(imported.payload.streamUris.single())!!.getLong("size"))
            assertEquals(retained, files.leases.loadShelf("account", "existing"))
        }

    /** Exhausts private storage and makes metadata/open callbacks fail if rejection happens too late. */
    @Test fun fullRetainedShelfRejectsNewIntakeWithoutOpeningTheSource() =
        runBlocking {
            val retained =
                List(4) {
                    val (uri, file) = files.newFile()
                    java.io.RandomAccessFile(file, "rw").use { it.setLength(PRIVATE_SHARE_MAX_BYTES) }
                    files.finish(uri, "retained.png", "image/png", PRIVATE_SHARE_MAX_BYTES)
                    uri
                }
            files.leases.saveShelf("account", "existing", retained)
            val importer =
                ShareFileImporter(
                    files,
                    { _, _ -> error("Full storage must reject before metadata access") },
                    { _, _ -> error("Full storage must reject before opening the source") },
                )
            val imported = importer.import(request(listOf(source)))
            assertEquals(listOf(ShareImportError.Storage), imported.payload.importErrors)
            assertTrue(imported.payload.streamUris.isEmpty())
            assertEquals(retained, files.leases.loadShelf("account", "existing"))
            retained.forEach { assertEquals(PRIVATE_SHARE_MAX_BYTES, files.metadata(it)!!.getLong("size")) }
        }

    /** Feeds an unbounded stream with a false size hint to verify bounded probing and partial-file cleanup. */
    @Test fun oversizedStreamingInputReadsAtMostBudgetPlusOneAndDeletesPartial() =
        runBlocking {
            var readBytes = 0L
            val stream =
                object : InputStream() {
                    override fun read(): Int = 1

                    override fun read(
                        buffer: ByteArray,
                        off: Int,
                        len: Int,
                    ): Int {
                        readBytes += len
                        java.util.Arrays.fill(buffer, off, off + len, 1.toByte())
                        return len
                    }
                }
            val importer =
                ShareFileImporter(
                    files,
                    { _, _ -> ShareSourceMetadata("large.bin", null, 1) },
                    { _, _ -> stream },
                )
            val result = importer.import(request(listOf(source)))
            assertEquals(listOf(ShareImportError.FileTooLarge), result.payload.importErrors)
            assertEquals(PRIVATE_SHARE_DOCUMENT_MAX_BYTES + 1, readBytes)
            assertEquals(0, root.listFiles()!!.count { it.extension == "bin" })
        }

    /** Duplicates an eleven-source batch; only unique excess items produce the visible limit outcome. */
    @Test fun tenItemLimitDeduplicatesAndKeepsValidItemsWithVisibleOverflow() =
        runBlocking {
            val importer =
                ShareFileImporter(
                    files,
                    { _, _ -> ShareSourceMetadata("file.bin", null, null) },
                    { _, _ -> ByteArrayInputStream(byteArrayOf(1)) },
                )
            val uris = (0..10).map { Uri.parse("content://external/$it") }
            val result = importer.import(request(uris + uris))
            assertEquals(10, result.payload.streamUris.size)
            assertEquals(listOf(ShareImportError.TooMany), result.payload.importErrors)
        }

    /** Streams files across the cumulative limit and verifies that a later fitting item can still complete. */
    @Test fun exactByteBoundaryAndCumulativeOverflowKeepOnlyCompleteFiles() =
        runBlocking {
            val sizes = List(3) { PRIVATE_SHARE_MAX_BYTES } + listOf(PRIVATE_SHARE_MAX_BYTES - 1, 2L, 1L)
            val importer =
                ShareFileImporter(
                    files,
                    { _, _ -> ShareSourceMetadata("file", "image/png", null) },
                    { uri, _ -> sizedStream(sizes[uri.lastPathSegment!!.toInt()]) },
                )
            val result = importer.import(request(sizes.indices.map { Uri.parse("content://external/$it") }))
            assertEquals(listOf(ShareImportError.BatchTooLarge), result.payload.importErrors)
            assertEquals(
                List(3) { PRIVATE_SHARE_MAX_BYTES } + listOf(PRIVATE_SHARE_MAX_BYTES - 1, 1L),
                result.payload.streamUris.map {
                    files.metadata(it)!!.getLong("size")
                },
            )
        }

    /** Generates a counted stream without allocating the large byte arrays used by budget-boundary cases. */
    private fun sizedStream(length: Long): InputStream =
        object : InputStream() {
            var remaining = length

            override fun read(): Int = if (remaining-- > 0) 1 else -1

            override fun read(
                buffer: ByteArray,
                off: Int,
                len: Int,
            ): Int {
                if (remaining == 0L) return -1
                val count = minOf(remaining, len.toLong()).toInt()
                java.util.Arrays.fill(buffer, off, off + count, 1.toByte())
                remaining -= count
                return count
            }
        }

    /** Uses one stable request owner so budget assertions do not depend on generated intake identities. */
    private fun request(uris: List<Uri>) =
        ShareRequest(
            SharePayload(null, uris, "application/octet-stream"),
            null,
            "request",
        )
}
