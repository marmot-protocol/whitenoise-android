package dev.ipf.whitenoise.android.share

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ShareFileImporterTest {
    private lateinit var root: File
    private lateinit var files: PrivateShareFiles
    private val source = Uri.parse("content://external/document")

    /** Uses a fresh private directory so retained bytes from another case cannot affect the storage budget. */
    @Before fun setup() {
        root = Files.createTempDirectory("private-intake").toFile()
        files = PrivateShareFiles(root, "test.private-share")
    }

    /** Removes the fixture directory, including leases left by negative import cases. */
    @After fun cleanup() {
        root.deleteRecursively()
    }

    /** Counts directory access to enforce lazy storage initialization during URI ownership checks. */
    @Test fun constructionAndOwnershipChecksDoNotTouchAndroidStorage() {
        var directoryReads = 0
        val context =
            object : ContextWrapper(RuntimeEnvironment.getApplication()) {
                /** Keeps the counting wrapper as the application context used by the storage adapter. */
                override fun getApplicationContext(): Context = this

                /** Records the first actual storage access rather than merely constructing the adapter. */
                override fun getNoBackupFilesDir(): File {
                    directoryReads++
                    return root
                }
            }
        val lazyFiles = PrivateShareFiles(context)
        assertTrue(lazyFiles.owns(Uri.parse("content://${context.packageName}.private-share/file")))
        assertEquals(0, directoryReads)
        lazyFiles.newFile()
        assertEquals(1, directoryReads)
    }

    /** Revokes the source after import and verifies that recreated storage reads only the private copy. */
    @Test fun bytesAndSafeMetadataSurviveSourceRevocationAndStoreRecreation() =
        runBlocking {
            var available = true
            val bytes = "# shared markdown\n".toByteArray()
            val importer =
                ShareFileImporter(
                    files,
                    { _, _ -> ShareSourceMetadata("../report\u202e.md", "text/markdown", bytes.size.toLong()) },
                    { _, _ -> if (available) ByteArrayInputStream(bytes) else error("revoked") },
                )
            val imported = importer.import(request(listOf(source)))
            available = false
            val restored = PrivateShareFiles(root, "test.private-share")
            val uri = imported.payload.streamUris.single()
            assertArrayEquals(bytes, restored.resolve(uri)!!.readBytes())
            assertEquals("text/markdown", restored.metadata(uri)!!.getString("mime"))
            assertEquals(bytes.size.toLong(), restored.metadata(uri)!!.getLong("size"))
            assertFalse(restored.metadata(uri)!!.getString("name").contains('/'))
            assertFalse(restored.metadata(uri)!!.getString("name").contains('\u202e'))
            assertTrue(imported.payload.importReady)
            assertTrue(imported.payload.importErrors.isEmpty())
        }

    /** Shares one file between accounts to distinguish account cleanup from last-owner file deletion. */
    @Test fun accountWipeReleasesOnlyItsShelvesAndQueuedSends() =
        runBlocking {
            val importer =
                ShareFileImporter(
                    files,
                    { _, _ -> ShareSourceMetadata("file", null, null) },
                    { _, _ -> ByteArrayInputStream(byteArrayOf(1)) },
                )
            val imported = importer.import(request(List(4) { Uri.parse("content://external/$it") }))
            val (first, second, shared) = imported.payload.streamUris
            val queued = imported.payload.streamUris.last()
            files.leases.saveShelf("first-account", "chat", listOf(first, shared))
            files.leases.saveShelf("second-account", "chat", listOf(second, shared))
            files.leases.holdSend("queued", listOf(queued), "first-account")
            files.leases.releaseRequest(imported.requestId)
            files.leases.releaseAccount("first-account")
            assertTrue(files.leases.loadShelf("first-account", "chat").isEmpty())
            assertEquals(listOf(second, shared), files.leases.loadShelf("second-account", "chat"))
            assertNull(files.resolve(first))
            assertNull(files.resolve(queued))
            assertNotNull(files.metadata(second))
            assertNotNull(files.metadata(shared))
            files.leases.releaseAccount("second-account")
            assertTrue(root.listFiles().orEmpty().isEmpty())
        }

    /** Exercises unknown provider size and conflicting MIME hints at the completed-file boundary. */
    @Test fun missingSizeIsMeasuredAndProviderMimeBeatsIntentMime() =
        runBlocking {
            val importer =
                ShareFileImporter(
                    files,
                    { _, _ -> ShareSourceMetadata("table.csv", "text/csv", null) },
                    { _, _ -> ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
                )
            val imported = importer.import(request(listOf(source)))
            val metadata = files.metadata(imported.payload.streamUris.single())!!
            assertEquals(3L, metadata.getLong("size"))
            assertEquals("text/csv", metadata.getString("mime"))
        }

    /** Mixes provider failures with a misleading size hint; accepted bytes must retain their own outcome. */
    @Test fun unsupportedEmptyAndUnreadableStayDistinctWhileActualBytesOverrideSizeHints() =
        runBlocking {
            val uris =
                listOf(
                    Uri.parse("file:///private"),
                    Uri.parse("content://x/empty"),
                    Uri.parse("content://x/denied"),
                    Uri.parse("content://x/changed"),
                )
            val importer =
                ShareFileImporter(
                    files,
                    { uri, _ ->
                        ShareSourceMetadata("file.txt", null, if (uri.lastPathSegment == "changed") 10 else null)
                    },
                    { uri, _ ->
                        when (uri.lastPathSegment) {
                            "denied" -> throw SecurityException()
                            "empty" -> ByteArrayInputStream(byteArrayOf())
                            else -> ByteArrayInputStream(byteArrayOf(1))
                        }
                    },
                )
            val result = importer.import(request(uris))
            assertEquals(1, result.payload.streamUris.size)
            assertEquals(
                listOf(
                    ShareImportError.Scheme,
                    ShareImportError.Empty,
                    ShareImportError.Unreadable,
                ),
                result.payload.importErrors,
            )
            assertEquals(1L, files.metadata(result.payload.streamUris.single())!!.getLong("size"))
        }

    /** Makes provider callbacks fail if invoked, including Android user-prefixed app-owned authorities. */
    @Test fun appOwnedProvidersAreRejectedBeforeMetadataOrOpen() =
        runBlocking {
            val importer =
                ShareFileImporter(
                    files,
                    { _, _ -> error("Must not query app-owned provider") },
                    { _, _ -> error("Must not open app-owned provider") },
                    isAppOwnedProvider = { it.authority == "test.fileprovider" },
                )
            val sources =
                listOf(
                    Uri.parse("content://test.fileprovider/audit/secret"),
                    Uri.parse("content://0@test.fileprovider/audit/secret"),
                    Uri.parse("content://0@test.private-share/00000000-0000-0000-0000-000000000000"),
                )
            val result = importer.import(request(sources))
            assertEquals(List(3) { ShareImportError.Scheme }, result.payload.importErrors)
            assertTrue(result.payload.streamUris.isEmpty())
        }

    /** Supplies unusable metadata while keeping readable bytes, requiring a safe fallback name and measured size. */
    @Test fun blankFilenameAndZeroSizeHintDoNotRejectReadableContent() =
        runBlocking {
            val importer =
                ShareFileImporter(
                    files,
                    { _, _ -> ShareSourceMetadata(".", null, 0) },
                    { _, _ -> ByteArrayInputStream(byteArrayOf(1, 2)) },
                )
            val imported = importer.import(request(listOf(source)))
            val metadata = files.metadata(imported.payload.streamUris.single())!!
            assertEquals("file", metadata.getString("name"))
            assertEquals(2L, metadata.getLong("size"))
        }

    /** Exercises blocked open, EOF and I/O cancellation while retaining the earlier completed import. */
    @Test fun timedOutBatchRetainsCompletedFilesAndClosesOnlyThePartialSource() =
        runBlocking {
            for (mode in CancelledProvider.entries) {
                val stalled = Uri.parse("content://external/stalled")
                val closed = java.util.concurrent.CountDownLatch(1)
                val importer =
                    ShareFileImporter(
                        files,
                        { _, _ -> ShareSourceMetadata("file", null, null) },
                        { uri, signal ->
                            if (uri != stalled) {
                                ByteArrayInputStream(byteArrayOf(1))
                            } else {
                                cancelledSource(mode, signal, closed)
                            }
                        },
                        timeoutMs = 1_000,
                    )
                val result = importer.import(request(listOf(source, stalled)))
                assertEquals(mode.name, listOf(ShareImportError.Interrupted), result.payload.importErrors)
                assertEquals(1, result.payload.importRejectedCount)
                assertEquals(1, result.payload.streamUris.size)
                assertEquals(1L, files.metadata(result.payload.streamUris.single())!!.getLong("size"))
                assertEquals(1, root.listFiles()!!.count { it.extension == "bin" })
                assertEquals(0L, closed.count)
            }
        }

    /** Blocks open or read until cancellation; its close latch proves the partial provider was released. */
    private fun cancelledSource(
        mode: CancelledProvider,
        signal: android.os.CancellationSignal,
        closed: java.util.concurrent.CountDownLatch,
    ): InputStream {
        if (mode == CancelledProvider.OPEN) {
            signal.setOnCancelListener { closed.countDown() }
            check(closed.await(5, java.util.concurrent.TimeUnit.SECONDS))
            throw android.os.OperationCanceledException()
        }
        return object : InputStream() {
            override fun read(): Int {
                check(closed.await(5, java.util.concurrent.TimeUnit.SECONDS))
                if (mode == CancelledProvider.IO) throw java.io.IOException("Closed provider")
                return -1
            }

            override fun close() {
                closed.countDown()
            }
        }
    }

    private enum class CancelledProvider { EOF, IO, OPEN }

    /** Checks normalized ownership lookup separately from the original user-prefixed URI needed for access. */
    @Test fun externalUserIdPrefixIsPreservedForTheGrantedRead() =
        runBlocking {
            val granted = Uri.parse("content://10@external/document")
            val importer =
                ShareFileImporter(
                    files,
                    { uri, _ ->
                        assertEquals(granted, uri)
                        ShareSourceMetadata("file.txt", "text/plain", null)
                    },
                    { uri, _ ->
                        assertEquals(granted, uri)
                        ByteArrayInputStream(byteArrayOf(1))
                    },
                    isAppOwnedProvider = { uri ->
                        assertEquals("external", uri.authority)
                        false
                    },
                )
            val imported = importer.import(request(listOf(granted)))
            assertTrue(imported.payload.importErrors.isEmpty())
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

    /** Recreates storage between releases to prove durable destination ownership, including account isolation. */
    @Test fun twoDestinationLeasesKeepBytesUntilBothAreRemoved() =
        runBlocking {
            val importer =
                ShareFileImporter(
                    files,
                    { _, _ -> ShareSourceMetadata("file.bin", null, null) },
                    { _, _ -> ByteArrayInputStream(byteArrayOf(1)) },
                )
            val uri =
                importer
                    .import(request(listOf(source)))
                    .payload.streamUris
                    .single()
            files.leases.saveShelf("account", "one", listOf(uri))
            files.leases.saveShelf("account", "two", listOf(uri))
            files.leases.releaseRequest("request")
            files.leases.saveShelf("account", "one", emptyList())
            assertNotNull(files.resolve(uri))
            assertEquals(listOf(uri), PrivateShareFiles(root, "test.private-share").leases.loadShelf("account", "two"))
            assertTrue(files.leases.loadShelf("other", "two").isEmpty())
            files.leases.saveShelf("account", "two", emptyList())
            assertNull(files.resolve(uri))
        }

    /** Rejects recursive private intake, foreign authorities and traversal without opening any source. */
    @Test fun privateUriAndTraversalCannotBeImportedOrResolved() =
        runBlocking {
            val importer =
                ShareFileImporter(
                    files,
                    { _, _ -> error("must not query") },
                    { _, _ -> error("must not open") },
                )
            assertEquals(
                listOf(ShareImportError.Scheme),
                importer
                    .import(
                        request(
                            listOf(
                                Uri.parse("content://test.private-share/123"),
                            ),
                        ),
                    ).payload.importErrors,
            )
            assertNull(files.resolve(Uri.parse("content://test.private-share/../outside")))
            assertNull(files.resolve(Uri.parse("content://foreign/00000000-0000-0000-0000-000000000000")))
        }

    /** Cancels through the progress callback and requires cleanup without returning a ready request. */
    @Test fun cancelledImportClosesInputAndDeletesPartialWithoutAcknowledging() =
        runBlocking {
            var closed = false
            val stream =
                object : ByteArrayInputStream(ByteArray(64 * 1024)) {
                    override fun close() {
                        closed = true
                        super.close()
                    }
                }
            val importer =
                ShareFileImporter(
                    files,
                    { _, _ -> ShareSourceMetadata("file", null, null) },
                    { _, _ -> stream },
                )
            try {
                importer.import(request(listOf(source))) { throw kotlinx.coroutines.CancellationException() }
                fail("cancelled import must not return ready")
            } catch (_: kotlinx.coroutines.CancellationException) {
            }
            assertTrue(closed)
            assertEquals(0, root.listFiles()!!.count { it.extension == "bin" })
        }

    /** Reads the real provider and manifest contract, then verifies that output access is rejected. */
    @Test fun productionProviderPreservesMetadataAndDeniesWritesOrGrants() =
        runBlocking {
            val context = org.robolectric.RuntimeEnvironment.getApplication()
            val providerFiles = PrivateShareFiles(context)
            val bytes = byteArrayOf(5, 6, 7)
            val importer =
                ShareFileImporter(
                    providerFiles,
                    { _, _ -> ShareSourceMetadata("archive.md", "text/markdown", 3) },
                    { _, _ -> ByteArrayInputStream(bytes) },
                )
            val imported = importer.import(request(listOf(source)))
            val uri = imported.payload.streamUris.single()
            val resolver = context.contentResolver
            assertArrayEquals(bytes, resolver.openInputStream(uri)!!.use { it.readBytes() })
            assertEquals("text/markdown", resolver.getType(uri))
            resolver.query(uri, null, null, null, null)!!.use {
                assertTrue(it.moveToFirst())
                assertEquals(
                    "archive.md",
                    it.getString(it.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME)),
                )
                assertEquals(3L, it.getLong(it.getColumnIndexOrThrow(android.provider.OpenableColumns.SIZE)))
            }
            val info = context.packageManager.resolveContentProvider(uri.authority!!, 0)!!
            assertFalse(info.exported)
            assertFalse(info.grantUriPermissions)
            try {
                resolver.openOutputStream(uri)
                fail("read-only provider")
            } catch (_: java.io.FileNotFoundException) {
            }
            providerFiles.leases.releaseRequest("request")
        }

    /** Checks the actual no-backup path and filesystem permissions rather than an in-memory ownership model. */
    @Test fun privateFilesHaveOwnerOnlyPermissionsAndNoBackupRoot() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val (uri, file) = PrivateShareFiles(context).newFile()
        assertEquals(
            File(context.noBackupFilesDir, PRIVATE_SHARE_DIRECTORY).canonicalFile,
            file.canonicalFile.parentFile,
        )
        val mode = Files.getPosixFilePermissions(file.toPath())
        assertEquals(
            setOf(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
            ),
            mode,
        )
        PrivateShareFiles(context).delete(uri)
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

    /** Mutates a completed file, then replaces it with a symlink; neither remains a valid private source. */
    @Test fun symlinksAndChangedPrivateBytesCannotBeReadAsACompletedImport() =
        runBlocking {
            val importer =
                ShareFileImporter(
                    files,
                    { _, _ -> ShareSourceMetadata("file", null, null) },
                    { _, _ -> ByteArrayInputStream(byteArrayOf(1)) },
                )
            val uri =
                importer
                    .import(request(listOf(source)))
                    .payload.streamUris
                    .single()
            val file = files.resolve(uri)!!
            file.appendBytes(byteArrayOf(2))
            assertNull(files.metadata(uri))
            file.delete()
            val other = File(root, "other").apply { writeBytes(byteArrayOf(1)) }
            Files.createSymbolicLink(file.toPath(), other.toPath())
            assertNull(files.resolve(uri))
        }

    /** Uses latches to cancel an active blocking read and waits for cleanup before inspecting storage. */
    @Test fun cancelledBlockedReadClosesProviderAndRemovesThePartialFile() =
        runBlocking {
            val entered = java.util.concurrent.CountDownLatch(1)
            val released = java.util.concurrent.CountDownLatch(1)
            val input =
                object : InputStream() {
                    override fun read(): Int = error("bulk only")

                    override fun read(
                        buffer: ByteArray,
                        off: Int,
                        len: Int,
                    ): Int {
                        entered.countDown()
                        check(released.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        return -1
                    }

                    override fun close() {
                        released.countDown()
                    }
                }
            val importer =
                ShareFileImporter(
                    files,
                    { _, _ -> ShareSourceMetadata("file", null, null) },
                    { _, _ -> input },
                )
            val job =
                launch(kotlinx.coroutines.Dispatchers.Default) {
                    importer.import(request(listOf(source)))
                }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            job.cancel()
            job.join()
            assertEquals(0, root.listFiles()!!.count { it.extension == "bin" })
        }

    /** Clears the composer shelf while a send owns the source; only the final send release deletes it. */
    @Test fun completedSendLeaseProtectsBytesUntilDurableAcceptance() =
        runBlocking {
            val importer =
                ShareFileImporter(
                    files,
                    { _, _ -> ShareSourceMetadata("file", null, null) },
                    { _, _ -> ByteArrayInputStream(byteArrayOf(1)) },
                )
            val uri =
                importer
                    .import(request(listOf(source)))
                    .payload.streamUris
                    .single()
            files.leases.saveShelf("account", "chat", listOf(uri))
            files.leases.releaseRequest("request")
            files.leases.holdSend("send", listOf(uri))
            files.leases.saveShelf("account", "chat", emptyList())
            assertNotNull(files.metadata(uri))
            assertTrue(files.leases.loadShelf("account", "chat").isEmpty())
            files.leases.releaseSend("send")
            assertNull(files.resolve(uri))
        }

    /** Separates incomplete-file recovery from the explicit maximum lifetime of completed originals. */
    @Test fun restartRecoveryDeletesUnfinishedFilesAndExpiryBoundsCompleteRetention() =
        runBlocking {
            val unfinished = files.newFile().second.apply { writeBytes(byteArrayOf(1)) }
            files.recoverIncomplete()
            assertFalse(unfinished.exists())
            val importer =
                ShareFileImporter(
                    files,
                    { _, _ -> ShareSourceMetadata("file", null, null) },
                    { _, _ -> ByteArrayInputStream(byteArrayOf(1)) },
                )
            val uri =
                importer
                    .import(request(listOf(source)))
                    .payload.streamUris
                    .single()
            files.cleanStale(System.currentTimeMillis() + PRIVATE_SHARE_MAX_AGE_MS + 1000)
            assertNull(files.resolve(uri))
        }

    /** Replays stale shelf snapshots around addition and removal to verify revision-aware ownership changes. */
    @Test fun lateComposerSnapshotCannotOverwriteANewerImportOrResurrectRemovedFiles() =
        runBlocking {
            val importer =
                ShareFileImporter(
                    files,
                    { _, _ -> ShareSourceMetadata("file", null, null) },
                    { _, _ -> ByteArrayInputStream(byteArrayOf(1)) },
                )
            val imported = importer.import(request(listOf(source, Uri.parse("content://external/new"))))
            val (old, newer) = imported.payload.streamUris
            files.leases.saveShelf("account", "chat", listOf(old))
            files.leases.saveShelf("account", "chat", listOf(old, newer))
            files.leases.changeShelf("account", "chat", listOf(old), listOf(old))
            assertEquals(listOf(old, newer), files.leases.loadShelf("account", "chat"))
            files.leases.changeShelf("account", "chat", listOf(old), emptyList())
            assertEquals(listOf(newer), files.leases.loadShelf("account", "chat"))
            files.leases.changeShelf("account", "chat", listOf(old), listOf(old))
            assertEquals(listOf(newer), files.leases.loadShelf("account", "chat"))
        }

    /** Restores an interrupted request marker and requires local cleanup without another provider read. */
    @Test fun interruptedProcessMarkerCleansTheLeasedPartialSourceWithoutReopeningIt() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val privateFiles = PrivateShareFiles(context)
        val (uri, file) = privateFiles.newFile()
        file.writeBytes(byteArrayOf(1, 2, 3))
        privateFiles.leases.holdRequest("killed-import", listOf(uri))
        val marker =
            ShareRequest(
                SharePayload(null, emptyList(), null, true, listOf(ShareImportError.Interrupted)),
                shortcutId = null,
                requestId = "killed-import",
            )
        assertEquals(marker, validateImportedShare(context, marker))
        assertFalse(file.exists())
    }

    /** Blocks the private directory with a regular file and expects the storage-specific recovery outcome. */
    @Test fun unavailablePrivateStorageIsDistinctFromProviderFailure() =
        runBlocking {
            val unavailable = File(root, "not-a-directory").apply { writeText("fixture") }
            val importer =
                ShareFileImporter(
                    PrivateShareFiles(unavailable, "test.private-share"),
                    { _, _ -> ShareSourceMetadata("file", null, null) },
                    { _, _ -> ByteArrayInputStream(byteArrayOf(1)) },
                )
            assertEquals(
                listOf(ShareImportError.Storage),
                importer.import(request(listOf(source))).payload.importErrors,
            )
        }

    /** Covers control characters, traversal separators, length bounds and MIME fallback precedence. */
    @Test fun filenameAndMimeNormalizationPreserveSafeExtensionsAndRejectUnusableNames() {
        assertEquals("report.csv", sanitizeShareFilename("report.csv"))
        assertEquals("archive.tar.gz", sanitizeShareFilename("archive.tar.gz"))
        assertNull(sanitizeShareFilename(" ... "))
        assertEquals(120, sanitizeShareFilename("a".repeat(200) + ".csv")!!.length)
        assertTrue(sanitizeShareFilename("a\u0000\u202e/b\\c.txt")!!.endsWith(".txt"))
        assertEquals("text/csv", resolveShareMime("Text/CSV; charset=utf-8", "image/png"))
        assertEquals("font/ttf", resolveShareMime("*/*", "font/ttf"))
        assertEquals("application/octet-stream", resolveShareMime("invalid", "*/*"))
    }

    /** Replaces pending intake after destination staging to verify that existing chat ownership survives. */
    @Test fun replacementReleasesOnlyTheUncommittedRequestAndNeverAnotherChat() =
        runBlocking {
            val importer =
                ShareFileImporter(
                    files,
                    { _, _ -> ShareSourceMetadata("file", null, null) },
                    { _, _ -> ByteArrayInputStream(byteArrayOf(1)) },
                )
            val first =
                importer
                    .import(request(listOf(source)))
                    .payload.streamUris
                    .single()
            files.leases.saveShelf("account", "already-staged", listOf(first))
            val newer = importer.import(request(listOf(Uri.parse("content://external/new"))).copy(requestId = "newer"))
            assertNotNull(files.metadata(first))
            files.leases.releaseRequest("newer")
            assertNull(files.resolve(newer.payload.streamUris.single()))
            assertEquals(listOf(first), files.leases.loadShelf("account", "already-staged"))
            files.leases.saveShelf("account", "already-staged", emptyList())
            assertNull(files.resolve(first))
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

    /** Uses one stable request owner so lease-release assertions match the imported batch identity. */
    private fun request(uris: List<Uri>) =
        ShareRequest(
            SharePayload(null, uris, "application/octet-stream"),
            null,
            "request",
        )
}
