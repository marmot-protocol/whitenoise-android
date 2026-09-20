package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AttachmentLocalAssetFfi
import dev.ipf.marmotkit.AttachmentLocalBytesFfi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Regression coverage for plaintext ownership across cancellation and partial batch failure. */
class NativeAttachmentLeaseCleanupTest {
    /** Cancellation at the final IO chunk must not strand the completed plaintext file. */
    @Test
    fun cancellationOnFinalChunkMustNotOrphanPlaintextLease() =
        runBlocking {
            val root = Files.createTempDirectory("pr2691-cancel-").toFile()
            try {
                var returned = false
                val reader =
                    NativeAttachmentLocalAccess(
                        cacheRoot = root,
                        queryAssets = { listOf(AttachmentLocalAssetFfi("asset", 3u)) },
                        readAsset = { _, _, _ ->
                            currentCoroutineContext().cancel()
                            AttachmentLocalBytesFfi(true, byteArrayOf(1, 2, 3))
                        },
                    )
                val owner =
                    launch {
                        val sources = reader.open(listOf(target()))
                        returned = true
                        sources.forEach { it?.close() }
                    }
                owner.join()
                assertTrue("Caller must observe cancellation instead of a returned lease", owner.isCancelled && !returned)
                assertEquals("Cancelled dispatcher handoff orphaned a plaintext lease", 0, leases(root).size)
            } finally {
                root.deleteRecursively()
            }
        }

    /** A failed batch closes earlier successfully materialized leases as well as the partial file. */
    @Test
    fun laterAssetFailureMustCloseEarlierBatchLeases() =
        runBlocking {
            val root = Files.createTempDirectory("pr2691-batch-").toFile()
            try {
                val reader =
                    NativeAttachmentLocalAccess(
                        cacheRoot = root,
                        queryAssets = { listOf(AttachmentLocalAssetFfi("first", 3u), AttachmentLocalAssetFfi("second", 3u)) },
                        readAsset = { reference, _, _ ->
                            if (reference == "second") throw IOException("second asset unavailable")
                            AttachmentLocalBytesFfi(true, byteArrayOf(1, 2, 3))
                        },
                    )
                val failure = runCatching { reader.open(listOf(target(), target())) }.exceptionOrNull()
                assertTrue(failure is IOException)
                assertEquals("Failed batch orphaned an earlier plaintext lease", 0, leases(root).size)
            } finally {
                root.deleteRecursively()
            }
        }

    /** Uses distinct display and source identities without any real account content. */
    private fun target() = NativeAttachmentTarget("11".repeat(32), "22".repeat(32), 0)

    /** Counts only this test-owned lease directory. */
    private fun leases(root: File) = File(root, "native_attachment_leases").listFiles().orEmpty()
}
