package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.media.AttachmentPlaintext
import dev.ipf.whitenoise.android.media.DiskByteCacheLease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AttachmentPlaintextResolverTest {
    /** Confirms an L1 hit bypasses disk and clears completed interactive intent. */
    @Test
    fun memoryHitSkipsDiskMissAndRepromotionButClearsInteractiveIntent() =
        runBlocking {
            val expected = byteArrayOf(1, 9)
            var promoted = false
            var cleared = false

            val resolved =
                resolveAttachmentPlaintext(
                    loadMemory = { expected },
                    loadDisk = { _, _ -> error("disk must not run") },
                    cacheMemory = { promoted = true },
                    clearInteractiveIntent = { cleared = true },
                    loadMiss = { error("cache miss must not run") },
                )

            assertFalse(promoted)
            assertTrue(cleared)
            assertArrayEquals(expected, (resolved as AttachmentPlaintext.Bytes).bytes)
        }

    /** Confirms small authenticated disk bytes are promoted to L1. */
    @Test
    fun smallDiskBytesArePromotedAndClearInteractiveIntent() =
        runBlocking {
            val expected = byteArrayOf(7, 8)
            var promoted: ByteArray? = null
            var cleared = false

            val resolved =
                resolveAttachmentPlaintext(
                    loadMemory = { null },
                    loadDisk = { _, onAcquired ->
                        AttachmentPlaintext.Bytes(expected).also(onAcquired)
                    },
                    cacheMemory = { promoted = it },
                    clearInteractiveIntent = { cleared = true },
                    loadMiss = { error("cache miss must not run") },
                )

            assertArrayEquals(expected, promoted)
            assertTrue(cleared)
            assertArrayEquals(expected, (resolved as AttachmentPlaintext.Bytes).bytes)
        }

    /** Confirms a large disk lease transfers to the caller without L1 byte promotion. */
    @Test
    fun diskLeaseIsReturnedWithoutByteArrayPromotion() =
        runBlocking {
            val file = File.createTempFile("attachment-plaintext", ".lease")
            file.writeBytes(byteArrayOf(1, 2, 3))
            val lease = AttachmentPlaintext.Lease(DiskByteCacheLease(file))
            var cached = false
            var cleared = false

            val resolved =
                resolveAttachmentPlaintext(
                    loadMemory = { null },
                    loadDisk = { _, onAcquired -> lease.also(onAcquired) },
                    cacheMemory = { cached = true },
                    clearInteractiveIntent = { cleared = true },
                    loadMiss = { error("cache miss must not run") },
                )

            assertSame(lease, resolved)
            assertFalse(cached)
            assertTrue(cleared)
            assertTrue(file.exists())
            resolved.close()
            assertFalse(file.exists())
        }

    /** Confirms post-load bookkeeping failures cannot leak an acquired plaintext lease. */
    @Test
    fun postLoadFailureClosesPendingLease() =
        runBlocking {
            val file = File.createTempFile("attachment-plaintext", ".lease")
            val lease = AttachmentPlaintext.Lease(DiskByteCacheLease(file))
            val failure = IllegalStateException("post-load bookkeeping failed")

            val thrown =
                runCatching {
                    resolveAttachmentPlaintext(
                        loadMemory = { null },
                        loadDisk = { _, onAcquired -> lease.also(onAcquired) },
                        cacheMemory = {},
                        clearInteractiveIntent = { throw failure },
                        loadMiss = { error("cache miss must not run") },
                    )
                }.exceptionOrNull()

            assertSame(failure, thrown)
            assertFalse(file.exists())
        }

    /** Confirms cancellation during dispatcher handoff closes the already-acquired lease. */
    @Test
    fun cancelledDiskHandoffClosesAcquiredLease() =
        runBlocking {
            val file = File.createTempFile("attachment-plaintext", ".lease")
            val lease = AttachmentPlaintext.Lease(DiskByteCacheLease(file))

            val thrown =
                runCatching {
                    resolveAttachmentPlaintext(
                        loadMemory = { null },
                        loadDisk = { _, onAcquired ->
                            onAcquired(lease)
                            throw CancellationException("cancelled during handoff")
                        },
                        cacheMemory = {},
                        clearInteractiveIntent = {},
                        loadMiss = { error("cache miss must not run") },
                    )
                }.exceptionOrNull()

            assertTrue(thrown is CancellationException)
            assertFalse(file.exists())
        }

    /** Confirms a true cache miss preserves the bounded byte-oriented download contract. */
    @Test
    fun missIsWrappedAsBoundedBytes() =
        runBlocking {
            val expected = byteArrayOf(4, 5, 6)
            var cleared = false
            val resolved =
                resolveAttachmentPlaintext(
                    loadMemory = { null },
                    loadDisk = { _, onAcquired -> null.also(onAcquired) },
                    cacheMemory = {},
                    clearInteractiveIntent = { cleared = true },
                    loadMiss = { expected },
                )

            assertTrue(resolved is AttachmentPlaintext.Bytes)
            assertArrayEquals(expected, (resolved as AttachmentPlaintext.Bytes).bytes)
            assertFalse(cleared)
        }
}
