package dev.ipf.whitenoise.android.media

import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.state.AttachmentTransferRequest
import dev.ipf.whitenoise.android.state.DISK_MEDIA_CACHE_MAX_ENTRY_BYTES
import dev.ipf.whitenoise.android.state.NativeAttachmentLocalAccess
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.cacheKey
import dev.ipf.whitenoise.android.state.downloadAttachmentPlaintextSource
import dev.ipf.whitenoise.android.state.resolveNativeAttachmentTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec

/** Paired retained reads compare chunk sizes; the host counts HTTP independently. */
internal object LargeAttachmentLocalReadComparison {
    /** Selects a generated large payload only when the host requests the paired comparison. */
    fun payloadBytes(enabled: Boolean): Int = if (enabled) 32 * 1024 * 1024 else 1024

    /** Large comparison extends only its own fixture deadline, leaving the small baseline unchanged. */
    fun timeoutMillis(enabled: Boolean): Long = if (enabled) 300_000L else 120_000L

    /** Holds only the generated sender's host copy; genuine MDK retention and native reads remain unchanged. */
    fun holdOutgoingHostCopy(
        state: WhiteNoiseAppState,
        root: File,
    ): OutgoingHostCopyHold {
        val hold = OutgoingHostCopyHold()
        val cache =
            DiskByteCache(
                File(root, "held-outgoing-host-cache"),
                maxBytes = 64L * 1024 * 1024,
                maxEntryBytes = DISK_MEDIA_CACHE_MAX_ENTRY_BYTES,
                keyProvider = DiskByteCacheKeyProvider { SecretKeySpec(ByteArray(32) { 19 }, "AES") },
                afterEncryptedWrite = hold::awaitRelease,
            )
        WhiteNoiseAppState::class.java
            .getDeclaredField("diskMediaCache")
            .apply { isAccessible = true }
            .set(state, cache)
        return hold
    }

    /** Proves a real own 32 MiB file is available and readable before the encrypted host copy can finish. */
    suspend fun assertNativeBeforeHostCopy(
        state: WhiteNoiseAppState,
        request: AttachmentTransferRequest,
        reference: MediaAttachmentReferenceFfi,
        expected: ByteArray,
        hold: OutgoingHostCopyHold,
    ) {
        try {
            hold.awaitEntered()
            ControlledAttachmentProbe.measure("large-own-native-with-host-copy-held", expected.size) {
                withTimeout(10_000L) {
                    assertTrue(state.outgoingAttachmentCachePublications.isPending(request.cacheKey()))
                    assertTrue(state.hasCachedAttachmentAfterHydration(request))
                    state
                        .downloadAttachmentPlaintextSource(request, reference, persistInteractiveIntent = false)
                        .use { local ->
                            verifyDigest(local, expected.size, MessageDigest.getInstance("SHA-256").digest(expected))
                        }
                    assertTrue(state.outgoingAttachmentCachePublications.isPending(request.cacheKey()))
                }
            }
        } finally {
            hold.release()
        }
    }

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
            state
                .downloadAttachmentPlaintextSource(request, reference, persistInteractiveIntent = false)
                .use { local ->
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

/** Deliberately blocks only a generated fixture write, with bounded cleanup on every failure path. */
internal class OutgoingHostCopyHold {
    private val entered = CountDownLatch(1)
    private val released = CountDownLatch(1)

    /** Signals encrypted-copy completion but prevents promotion until the native-read assertion finishes. */
    fun awaitRelease() {
        entered.countDown()
        check(released.await(30, TimeUnit.SECONDS)) { "held outgoing host copy timed out" }
    }

    /** Waits off-main for the genuine shipping cache write to reach the controlled boundary. */
    suspend fun awaitEntered() {
        assertTrue(withContext(Dispatchers.IO) { entered.await(10, TimeUnit.SECONDS) })
    }

    /** Idempotently permits promotion, including after failed assertions or native timeouts. */
    fun release() = released.countDown()
}
