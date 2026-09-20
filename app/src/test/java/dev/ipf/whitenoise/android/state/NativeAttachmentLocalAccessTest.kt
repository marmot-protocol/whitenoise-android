package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AttachmentLocalAssetFfi
import dev.ipf.marmotkit.AttachmentLocalBytesFfi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors

class NativeAttachmentLocalAccessTest {
    /** Directory, query, and streaming work leave a caller-confined UI dispatcher. */
    @Test
    fun `open confines native materialization to io`() =
        runBlocking {
            val caller = Executors.newSingleThreadExecutor { task -> Thread(task, "native-access-caller") }
            val callerDispatcher = caller.asCoroutineDispatcher()
            try {
                val observedThreads = mutableListOf<String>()
                withContext(callerDispatcher) {
                    NativeAttachmentLocalAccess(
                        cacheRoot = temporaryRoot(),
                        queryAssets = {
                            observedThreads += Thread.currentThread().name
                            listOf(AttachmentLocalAssetFfi("empty", 0u))
                        },
                        readAsset = { _, _, _ ->
                            observedThreads += Thread.currentThread().name
                            AttachmentLocalBytesFfi(true, byteArrayOf())
                        },
                    ).open(listOf(target())).single()?.close()
                }

                assertTrue(observedThreads.isNotEmpty())
                assertTrue(observedThreads.none { it == "native-access-caller" })
            } finally {
                callerDispatcher.close()
                caller.shutdownNow()
            }
        }

    /** Native targets never substitute a display ID for their source ID. */
    @Test
    fun `target preserves display id source id and original album index`() {
        val target = NativeAttachmentTarget(DISPLAY_ID, SOURCE_ID, 7)

        val ffi = target.toFfi()

        assertEquals(DISPLAY_ID, ffi.messageIdHex)
        assertEquals(SOURCE_ID, ffi.sourceMessageIdHex)
        assertEquals(7u, ffi.attachmentIndex)
    }

    /** Large retained assets stream through bounded reads and closeable leases. */
    @Test
    fun `reader uses bounded chunks and deletes lease when closed`() =
        runTest {
            val root = temporaryRoot()
            val bytes = ByteArray(NATIVE_ATTACHMENT_READ_CHUNK_BYTES * 2 + 17) { (it % 251).toByte() }
            val limits = mutableListOf<UInt>()
            val source =
                NativeAttachmentLocalAccess(
                    cacheRoot = root,
                    queryAssets = { listOf(AttachmentLocalAssetFfi("opaque", bytes.size.toULong())) },
                    readAsset = { _, offset, limit ->
                        limits += limit
                        val start = offset.toInt()
                        AttachmentLocalBytesFfi(
                            available = true,
                            bytes = bytes.copyOfRange(start, minOf(start + limit.toInt(), bytes.size)),
                        )
                    },
                ).open(listOf(target())).single()

            requireNotNull(source)
            val output = ByteArrayOutputStream()
            source.copyTo(output)
            assertArrayEquals(bytes, output.toByteArray())
            assertTrue(limits.all { it in 1u..1_048_576u })
            val leaseFile = checkNotNull(source.leaseFileForTesting())
            assertTrue(leaseFile.isFile)
            source.close()
            assertFalse(leaseFile.exists())
            root.deleteRecursively()
        }

    /** A zero-length retained asset is represented by a valid empty lease. */
    @Test
    fun `available empty chunk is eof including zero byte attachment`() =
        runTest {
            val source =
                NativeAttachmentLocalAccess(
                    cacheRoot = temporaryRoot(),
                    queryAssets = { listOf(AttachmentLocalAssetFfi("empty", 0u)) },
                    readAsset = { _, _, _ -> AttachmentLocalBytesFfi(true, byteArrayOf()) },
                ).open(listOf(target())).single()

            requireNotNull(source)
            assertEquals(0L, source.size)
            source.close()
        }

    /** An unavailable later chunk deletes every byte of the partial lease. */
    @Test
    fun `unavailable chunk invalidates and removes partial file`() =
        runTest {
            val root = temporaryRoot()
            var reads = 0
            val result =
                NativeAttachmentLocalAccess(
                    cacheRoot = root,
                    queryAssets = { listOf(AttachmentLocalAssetFfi("opaque", 8u)) },
                    readAsset = { _, _, _ ->
                        reads++
                        if (reads == 1) {
                            AttachmentLocalBytesFfi(true, byteArrayOf(1, 2, 3, 4))
                        } else {
                            AttachmentLocalBytesFfi(false, byteArrayOf())
                        }
                    },
                ).open(listOf(target())).single()

            assertNull(result)
            assertTrue(File(root, "native_attachment_leases").listFiles().orEmpty().isEmpty())
            root.deleteRecursively()
        }

    /** A same-process sign-out wipe recreates the previously prepared lease root safely. */
    @Test
    fun `removed prepared directory is recreated before the next lease`() =
        runTest {
            val root = temporaryRoot()
            val access =
                NativeAttachmentLocalAccess(
                    cacheRoot = root,
                    queryAssets = { listOf(AttachmentLocalAssetFfi("empty", 0u)) },
                    readAsset = { _, _, _ -> AttachmentLocalBytesFfi(true, byteArrayOf()) },
                )
            access.open(listOf(target())).single()?.close()
            val directory = File(root, "native_attachment_leases")
            assertTrue(directory.deleteRecursively())

            access.open(listOf(target())).single()?.close()

            assertTrue(directory.isDirectory)
            root.deleteRecursively()
        }

    /** Native local-access batches enforce the MarmotKit request bound. */
    @Test(expected = IllegalArgumentException::class)
    fun `query batches are capped at sixty four`() =
        runTest {
            NativeAttachmentLocalAccess(
                cacheRoot = temporaryRoot(),
                queryAssets = { emptyList() },
                readAsset = { _, _, _ -> error("unused") },
            ).open(List(65) { target(index = it) })
        }

    /** Builds an exact native target for one authored attachment slot. */
    private fun target(index: Int = 0) = NativeAttachmentTarget(DISPLAY_ID, SOURCE_ID, index)

    /** Creates an isolated lease root for one local-access scenario. */
    private fun temporaryRoot(): File =
        File(
            System.getProperty("java.io.tmpdir"),
            "native-attachment-${System.nanoTime()}",
        ).apply { mkdirs() }

    private companion object {
        val DISPLAY_ID = "11".repeat(32)
        val SOURCE_ID = "22".repeat(32)
    }
}
