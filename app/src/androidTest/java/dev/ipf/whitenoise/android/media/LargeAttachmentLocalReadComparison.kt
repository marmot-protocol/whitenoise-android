package dev.ipf.whitenoise.android.media

import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.state.AttachmentTransferRequest
import dev.ipf.whitenoise.android.state.NativeAttachmentLocalAccess
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.cacheKey
import dev.ipf.whitenoise.android.state.downloadAttachmentPlaintextSource
import dev.ipf.whitenoise.android.state.resolveNativeAttachmentTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.OutputStream
import java.security.MessageDigest

/** Paired retained reads compare chunk sizes; the host counts HTTP independently. */
internal object LargeAttachmentLocalReadComparison {
    /** Selects a generated large payload only when the host requests the paired comparison. */
    fun payloadBytes(enabled: Boolean): Int = if (enabled) 32 * 1024 * 1024 else 1024

    /** Large comparison extends only its own fixture deadline, leaving the small baseline unchanged. */
    fun timeoutMillis(enabled: Boolean): Long = if (enabled) 300_000L else 120_000L

    /** A genuine large own send bypasses L1 and waits for its encrypted host publication without acquiring again. */
    suspend fun assertOwnHostCache(
        state: WhiteNoiseAppState,
        request: AttachmentTransferRequest,
        reference: MediaAttachmentReferenceFfi,
        expected: ByteArray,
    ) {
        ControlledAttachmentProbe.measure("large-own-host-publication", expected.size) {
            withContext(Dispatchers.Main.immediate) { assertNull(state.cachedMediaPlaintext(request.cacheKey())) }
            assertTrue(state.hasHostCachedAttachmentAfterHydration(request))
            state.downloadAttachmentPlaintextSource(request, reference, persistInteractiveIntent = false).use { local ->
                verifyDigest(local, expected.size, MessageDigest.getInstance("SHA-256").digest(expected))
            }
        }
    }

    /** Alternates read order and hashes leases without a second full-size heap copy. */
    suspend fun run(
        state: WhiteNoiseAppState,
        request: AttachmentTransferRequest,
        expected: ByteArray,
    ) {
        val target = requireNotNull(state.resolveNativeAttachmentTarget(request))
        val expectedDigest = MessageDigest.getInstance("SHA-256").digest(expected)
        listOf(256 * 1024, 1024 * 1024, 1024 * 1024, 256 * 1024).forEachIndexed { index, chunkBytes ->
            var reads = 0
            ControlledAttachmentProbe.measure("large-local-read-$chunkBytes-$index", expected.size) {
                val source =
                    NativeAttachmentLocalAccess(
                        cacheRoot = state.diskMediaCache.siblingCacheRoot(),
                        queryAssets = { targets ->
                            state.marmotIo { attachmentLocalAssets(request.accountRef, request.groupIdHex, targets) }
                        },
                        readAsset = { reference, offset, limit ->
                            reads++
                            state.marmotIo { readAttachmentAsset(request.accountRef, reference, offset, limit) }
                        },
                        readChunkBytes = chunkBytes,
                    ).open(listOf(target)).single()
                requireNotNull(source).use { local ->
                    verifyDigest(local, expected.size, expectedDigest)
                }
            }
            assertEquals((expected.size + chunkBytes - 1) / chunkBytes, reads)
            ControlledAttachmentProbe.report(
                JSONObject().put("phase", "large-local-read-count").put("chunk_bytes", chunkBytes).put("reads", reads),
            )
        }
    }

    /** Checks lease length and streaming content without another full-size plaintext allocation. */
    private suspend fun verifyDigest(
        local: AttachmentPlaintext,
        size: Int,
        expectedDigest: ByteArray,
    ) {
        assertEquals(size.toLong(), local.size)
        val digest = MessageDigest.getInstance("SHA-256")
        local.copyTo(
            object : OutputStream() {
                override fun write(value: Int) = digest.update(value.toByte())

                override fun write(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ) = digest.update(bytes, offset, length)
            },
        )
        assertArrayEquals(expectedDigest, digest.digest())
    }
}
