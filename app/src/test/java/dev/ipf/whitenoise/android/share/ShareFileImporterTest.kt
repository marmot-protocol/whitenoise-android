package dev.ipf.whitenoise.android.share

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

    @Before fun setup() {
        root = Files.createTempDirectory("private-intake").toFile()
        files = PrivateShareFiles(root, "test.private-share")
    }

    @After fun cleanup() {
        root.deleteRecursively()
    }

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

    @Test fun appOwnedProvidersAreRejectedBeforeMetadataOrOpen() =
        runBlocking {
            val importer =
                ShareFileImporter(
                    files,
                    { _, _ -> error("Must not query app-owned provider") },
                    { _, _ -> error("Must not open app-owned provider") },
                    isAppOwnedProvider = { it.authority == "test.fileprovider" },
                )
            val result = importer.import(request(listOf(Uri.parse("content://test.fileprovider/audit/secret"))))
            assertEquals(listOf(ShareImportError.Scheme), result.payload.importErrors)
            assertTrue(result.payload.streamUris.isEmpty())
        }

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

    @Test fun timedOutBatchRetainsCompletedFilesAndClosesOnlyThePartialSource() =
        runBlocking {
            val stalled = Uri.parse("content://external/stalled")
            val closed = java.util.concurrent.CountDownLatch(1)
            val importer =
                ShareFileImporter(
                    files,
                    { _, _ -> ShareSourceMetadata("file", null, null) },
                    { uri, _ ->
                        if (uri != stalled) ByteArrayInputStream(byteArrayOf(1)) else
                            object : InputStream() {
                                override fun read(): Int {
                                    check(closed.await(5, java.util.concurrent.TimeUnit.SECONDS))
                                    return -1
                                }

                                override fun close() { closed.countDown() }
                            }
                    },
                    timeoutMs = 1_000,
                )
            val result = importer.import(request(listOf(source, stalled)))
            assertEquals(listOf(ShareImportError.Interrupted), result.payload.importErrors)
            assertEquals(1, result.payload.importRejectedCount)
            assertEquals(1, result.payload.streamUris.size)
            assertEquals(1L, files.metadata(result.payload.streamUris.single())!!.getLong("size"))
            assertEquals(1, root.listFiles()!!.count { it.extension == "bin" })
            assertEquals(0, closed.count)
        }

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
            assertEquals(PRIVATE_SHARE_MAX_BYTES + 1, readBytes)
            assertEquals(0, root.listFiles()!!.count { it.extension == "bin" })
        }

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

    @Test fun exactByteBoundaryAndCumulativeOverflowKeepOnlyCompleteFiles() =
        runBlocking {
            val sizes = listOf(PRIVATE_SHARE_MAX_BYTES, PRIVATE_SHARE_MAX_BYTES, PRIVATE_SHARE_MAX_BYTES, PRIVATE_SHARE_MAX_BYTES, 1L)
            val importer =
                ShareFileImporter(
                    files,
                    { _, _ -> ShareSourceMetadata("file", null, null) },
                    { uri, _ -> sizedStream(sizes[uri.lastPathSegment!!.toInt()]) },
                )
            val result = importer.import(request(sizes.indices.map { Uri.parse("content://external/$it") }))
            assertEquals(listOf(ShareImportError.BatchTooLarge), result.payload.importErrors)
            assertEquals(
                List(4) { PRIVATE_SHARE_MAX_BYTES },
                result.payload.streamUris.map {
                    files.metadata(it)!!.getLong("size")
                },
            )
        }

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

    private fun request(uris: List<Uri>) =
        ShareRequest(
            SharePayload(null, uris, "application/octet-stream"),
            null,
            "request",
        )
}
