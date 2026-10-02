package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.media.AttachmentPlaintext
import dev.ipf.whitenoise.android.media.DiskByteCacheLease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AttachmentPlaintextResolverTest {
    /** Canonical retained bytes bypass acquisition even when both Android presentation caches miss. */
    @Test
    fun nativeLeaseBypassesAcquisitionAndRemainsCallerOwned() =
        runBlocking {
            val file = File.createTempFile("native-retained", ".lease").apply { writeBytes(byteArrayOf(3, 4)) }
            val lease = AttachmentPlaintext.Lease(DiskByteCacheLease(file))
            val events = mutableListOf<String>()
            val source =
                resolveAttachmentPlaintext(
                    loadMemory = {
                        events += "memory"
                        null
                    },
                    loadDisk = { _, onAcquired ->
                        events += "disk"
                        null.also(onAcquired)
                    },
                    cacheMemory = { error("leases must not be copied into memory") },
                    clearInteractiveIntent = { events += "clear" },
                    loadMiss = { error("retained reads must not join or promote network work") },
                    loadNative = {
                        events += "native"
                        lease
                    },
                )
            assertSame(lease, source)
            org.junit.Assert.assertEquals(listOf("memory", "disk", "native", "clear"), events)
            assertTrue(file.exists())
            source.close()
            assertFalse(file.exists())
        }

    /** A retained native source stays immediately usable while its optional host copy is encrypted. */
    @Test
    fun preferredNativeLeaseSkipsPendingHostPublication() =
        runBlocking {
            val file = File.createTempFile("outgoing-retained", ".lease")
            val lease = AttachmentPlaintext.Lease(DiskByteCacheLease(file))
            val source =
                resolveAttachmentPlaintext(
                    loadMemory = { null },
                    loadDisk = { _, _ -> error("must not wait for the outgoing host copy") },
                    cacheMemory = { error("leases must remain streamed") },
                    clearInteractiveIntent = {},
                    loadMiss = { error("retained data must not download") },
                    loadNative = { lease },
                    preferNative = true,
                )
            assertSame(lease, source)
            source.close()
            assertFalse(file.exists())
        }

    /** If native retention is unavailable, a pending host write still precedes every network admission. */
    @Test
    fun preferredNativeMissWaitsForHostPublication() =
        runBlocking {
            val release = kotlinx.coroutines.CompletableDeferred<Unit>()
            val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
            val expected = byteArrayOf(4, 5)
            var nativeProbes = 0
            val read =
                async {
                    resolveAttachmentPlaintext(
                        loadMemory = { null },
                        loadDisk = { _, acquired ->
                            entered.complete(Unit)
                            release.await()
                            AttachmentPlaintext.Bytes(expected).also(acquired)
                        },
                        cacheMemory = {},
                        clearInteractiveIntent = {},
                        loadMiss = { error("pending outgoing data must not download") },
                        loadNative = {
                            nativeProbes++
                            null
                        },
                        preferNative = true,
                    )
                }
            entered.await()
            assertFalse(read.isCompleted)
            release.complete(Unit)
            assertArrayEquals(expected, (read.await() as AttachmentPlaintext.Bytes).bytes)
            org.junit.Assert.assertEquals(1, nativeProbes)
        }

    /** Failed local preparation propagates without silently turning a retained hit into a transfer. */
    @Test
    fun nativeMaterializationFailureNeverAcquiresOrClearsIntent() =
        runBlocking {
            val failure = java.io.IOException("local preparation failed")
            val thrown =
                runCatching {
                    resolveAttachmentPlaintext(
                        loadMemory = { null },
                        loadDisk = { _, onAcquired -> null.also(onAcquired) },
                        cacheMemory = {},
                        clearInteractiveIntent = { error("failed local reads preserve intent") },
                        loadMiss = { error("failed local reads must not download") },
                        loadNative = { throw failure },
                    )
                }.exceptionOrNull()
            assertSame(failure, thrown)
        }

    /** Post-load failure closes the native lease just as it closes an Android disk lease. */
    @Test
    fun nativeLeaseBookkeepingFailureDeletesPlaintext() =
        runBlocking {
            val file = File.createTempFile("native-retained", ".lease")
            val lease = AttachmentPlaintext.Lease(DiskByteCacheLease(file))
            val failure = CancellationException("caller detached")
            val thrown =
                runCatching {
                    resolveAttachmentPlaintext(
                        loadMemory = { null },
                        loadDisk = { _, onAcquired -> null.also(onAcquired) },
                        cacheMemory = {},
                        clearInteractiveIntent = { throw failure },
                        loadMiss = { error("native hit must not download") },
                        loadNative = { lease },
                    )
                }.exceptionOrNull()
            assertSame(failure, thrown)
            assertFalse(file.exists())
        }

    /** A failed native reopen must not erase the durable request that can retry it. */
    @Test
    fun failedNativeMaterializationPreservesInteractiveIntent() =
        runBlocking {
            var cleared = false
            val request = MediaDownloadIntegrationFixture.qualifiedRequest()

            val failure =
                runCatching {
                    materializeAttachmentAcquisition(
                        outcome = AttachmentAcquisitionOutcome.NativeRetained(request),
                        openNative = { null },
                        afterSuccess = { cleared = true },
                    )
                }.exceptionOrNull()

            assertTrue(failure is java.io.IOException)
            assertFalse(cleared)
        }

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
                    loadMiss = { AttachmentPlaintext.Bytes(expected) },
                )

            assertTrue(resolved is AttachmentPlaintext.Bytes)
            assertArrayEquals(expected, (resolved as AttachmentPlaintext.Bytes).bytes)
            assertFalse(cleared)
        }
}
