package dev.ipf.whitenoise.android.media

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.reflect.Modifier
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.security.ProviderException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.cancellation.CancellationException

class DiskByteCacheTest {
    private lateinit var dir: File
    private val keyProvider =
        DiskByteCacheKeyProvider {
            SecretKeySpec(ByteArray(32) { index -> (index + 1).toByte() }, "AES")
        }

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("disk-cache-test").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun memoizedFileNamesAreDroppedByClear() {
        val cache = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
        cache.contains("account-a:blob-1")
        assertEquals(1, cache.memoizedFileNameKeyCount())

        cache.clear()

        assertEquals(0, cache.memoizedFileNameKeyCount())
    }

    @Test
    fun clearDuringHashingCannotResurrectMemoizedKeys() {
        // Deterministic interleaving: the lookup misses, hashes the key, and a
        // clear() lands before the memo insert — the stale key must be
        // rejected, not re-inserted after the wipe.
        var cache: DiskByteCache? = null
        var clearOnceMidHash = true
        cache =
            DiskByteCache(
                dir,
                keyProvider = keyProvider,
                maxBytes = 1024,
                afterFileNameHashed = {
                    if (clearOnceMidHash) {
                        clearOnceMidHash = false
                        cache!!.clear()
                    }
                },
            )

        cache.contains("old-account:blob-1")

        assertEquals(0, cache.memoizedFileNameKeyCount())
    }

    @Test
    fun emptyCache_getReturnsNull() {
        val cache = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
        assertNull(cache.get("absent"))
        assertEquals(0, cache.size())
        assertEquals(0L, cache.residentBytes())
    }

    @Test
    fun definitiveContainsHydratesAColdIndexBeforeReporting() {
        DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024).put("cached", byteArrayOf(1, 2, 3))
        val reopened = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)

        assertFalse("the composition-safe peek remains non-blocking", reopened.contains("cached"))
        assertTrue(reopened.containsAfterHydration("cached"))
        assertFalse(reopened.containsAfterHydration("missing"))
    }

    @Test
    fun putThenGet_roundTripsThroughDisk() {
        val cache = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
        val payload = ByteArray(40) { it.toByte() }
        cache.put("k", payload)
        val out = cache.get("k")
        assertNotNull(out)
        assertTrue(out!!.contentEquals(payload))
        assertEquals(102L, cache.residentBytes())
    }

    @Test
    fun put_persistsCiphertextInsteadOfPlaintext() {
        val cache = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
        val payload = "private attachment bytes".toByteArray()

        cache.put("k", payload)

        val stored = dir.listFiles()!!.single { it.name.endsWith(".enc") }.readBytes()
        assertFalse("decrypted media must not be persisted verbatim", stored.contentEquals(payload))
        assertEquals("AES-GCM envelope stores a 6-byte header, metadata auth, and payload IV/tag", payload.size + 62, stored.size)
    }

    @Test
    fun oneByteEntry_survivesRestart() {
        val payload = byteArrayOf(7)
        DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024).put("tiny", payload)

        val reopened = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)

        assertTrue(reopened.get("tiny")!!.contentEquals(payload))
    }

    @Test
    fun malformedPreEnvelopeEntry_withVersionLikeIvPrefix_isRejectedDuringHydration() {
        val file = File(dir, sha256Hex("legacy") + ".enc")
        file.writeBytes(ByteArray(64).also { bytes -> bytes[0] = 1 })

        val reopened = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)

        assertEquals(0, reopened.size())
        assertFalse(file.exists())
    }

    @Test
    fun leaseCloseDoesNotMaskCompletedPublicationWhenDeletionFails() {
        val backing = File(dir, "undeletable.lease").apply { writeBytes(byteArrayOf(1)) }
        val undeletable =
            object : File(backing.absolutePath) {
                override fun delete(): Boolean = false
            }

        var diagnostic: String? = null
        DiskByteCacheLease(undeletable) { diagnostic = it }.close()
        assertTrue(backing.exists())
        assertTrue(diagnostic?.contains("failed to delete plaintext cache lease") == true)
    }

    @Test
    fun hydrationSweepsLegacySidecar_evenWhenEnvelopeExists() {
        DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
            .put("k", ByteArray(32) { 1 }, DiskByteCachePublicationToken(0, 0), "old-ciphertext")
        val legacySidecar = File(dir, sha256Hex("k") + ".tag").also { it.writeText("stale-ciphertext") }

        DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024).size()

        assertFalse("the single-file format never retains legacy sidecars", legacySidecar.exists())
    }

    @Test
    fun repeatedWritesUseFreshRandomIvs() {
        val cache = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
        val payload = ByteArray(32) { 7 }
        cache.put("k", payload)
        val file = File(dir, sha256Hex("k") + ".enc")
        val first = file.readBytes()

        cache.put("k", payload)
        val second = file.readBytes()

        assertFalse(
            "same plaintext must not reuse an AES-GCM IV",
            first.copyOfRange(6, 18).contentEquals(second.copyOfRange(6, 18)),
        )
        assertTrue(cache.get("k")!!.contentEquals(payload))
    }

    @Test
    fun encryptedEntries_areBoundToTheirHashedCacheKey() {
        val cache = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
        val first = ByteArray(32) { 1 }
        cache.put("first", first)
        cache.put("second", ByteArray(32) { 2 })
        val firstFile = File(dir, sha256Hex("first") + ".enc")
        val secondFile = File(dir, sha256Hex("second") + ".enc")

        secondFile.writeBytes(firstFile.readBytes())

        assertNull("moving ciphertext to another cache key must fail authentication", cache.get("second"))
        assertFalse("the poisoned entry must be removed from the index", cache.contains("second"))
        assertFalse("the poisoned ciphertext must be deleted", secondFile.exists())
        assertEquals(1, cache.size())
    }

    @Test
    fun tamperedPayloadTagIsRejectedAndPoisonedEntryIsEvicted() {
        val cache = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
        cache.put("tampered", ByteArray(32) { it.toByte() })
        val encrypted = File(dir, sha256Hex("tampered") + ".enc")
        val bytes = encrypted.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        encrypted.writeBytes(bytes)

        assertNull(cache.get("tampered"))
        assertFalse("payload authentication failure must evict the index row", cache.contains("tampered"))
        assertFalse("payload authentication failure must delete the envelope", encrypted.exists())
        assertEquals(0L, cache.residentBytes())
    }

    @Test
    fun truncatedPayloadIsRejectedAndPoisonedEntryIsEvicted() {
        val cache = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
        cache.put("truncated", ByteArray(32) { it.toByte() })
        val encrypted = File(dir, sha256Hex("truncated") + ".enc")
        encrypted.writeBytes(encrypted.readBytes().copyOf(55))

        assertNull(cache.get("truncated"))
        assertFalse(cache.contains("truncated"))
        assertFalse(encrypted.exists())
    }

    @Test
    fun legacyPlaintextEntries_areWipedInsteadOfMigrated() {
        val key = "legacy"
        val legacy = File(dir, sha256Hex(key) + ".bin").also { it.writeText("decrypted media") }
        val legacyTag = File(dir, sha256Hex(key) + ".tag").also { it.writeText("ciphertext-hash") }
        val foreign = File(dir, "keep-me.txt").also { it.writeText("foreign") }

        val cache = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)

        assertEquals(0, cache.size())
        assertNull(cache.get(key))
        assertFalse("upgrade must delete legacy plaintext", legacy.exists())
        assertFalse("legacy metadata must not be orphaned", legacyTag.exists())
        assertTrue("foreign cache-directory files are not ours to wipe", foreign.isFile)
    }

    @Test
    fun prepareWipesLegacyPlaintextBeforeFirstCacheAccess() {
        val legacy = File(dir, sha256Hex("legacy") + ".bin").also { it.writeText("decrypted media") }
        val cache = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
        assertTrue(legacy.isFile)

        cache.prepare()

        assertFalse("background startup preparation must delete legacy plaintext", legacy.exists())
    }

    @Test
    fun unavailableKeyFailsClosedWithoutPersistingPlaintext() {
        val existingPayload = "already encrypted".toByteArray()
        DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024).put("existing", existingPayload)
        val unavailableKey =
            DiskByteCacheKeyProvider {
                throw ProviderException("keystore unavailable")
            }
        val cache = DiskByteCache(dir, keyProvider = unavailableKey, maxBytes = 1024)

        assertNull("provider failure on read must be a cache miss", cache.get("existing"))
        cache.put("new", "private attachment bytes".toByteArray())

        assertFalse(File(dir, sha256Hex("new") + ".enc").exists())
        assertTrue(dir.listFiles()?.none { it.name.endsWith(".bin") } ?: true)
    }

    @Test
    fun oversizedEntry_isNotPersistedOrReadBack() {
        val cache = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024, maxEntryBytes = 64)
        cache.put("too-large", ByteArray(65))

        assertNull(cache.get("too-large"))
        assertEquals(0, cache.size())
        assertEquals(0L, cache.residentBytes())
        assertTrue(dir.listFiles()?.none { it.name.endsWith(".enc") } ?: true)
    }

    @Test
    fun rehydrateDropsOversizedEntryBeforeReadBytes() {
        val writer = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024, maxEntryBytes = 128)
        writer.put("large", ByteArray(120) { 1 })

        val tighter = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024, maxEntryBytes = 64)
        assertNull(tighter.get("large"))
        assertEquals(0L, tighter.residentBytes())
        assertTrue(dir.listFiles()?.none { it.name.endsWith(".enc") } ?: true)
    }

    @Test
    fun largeEntry_usesChunkAuthenticatedEnvelopeAndRoundTrips() {
        val payload = ByteArray(1024 * 1024 + 37) { index -> (index * 31).toByte() }
        val nearLimit = payload.size.toLong() + 1L
        val cache =
            DiskByteCache(
                dir,
                keyProvider = keyProvider,
                maxBytes = 2L * 1024L * 1024L,
                maxEntryBytes = nearLimit,
                availablePlaintextAllocationBytes = { Long.MAX_VALUE },
            )

        cache.put("large", payload)

        val envelope = File(dir, sha256Hex("large") + ".enc")
        assertEquals("large entries must use the bounded chunked format", 3, envelope.readBytes()[4].toInt())
        val reopened =
            DiskByteCache(
                dir,
                keyProvider = keyProvider,
                maxBytes = 2L * 1024L * 1024L,
                maxEntryBytes = nearLimit,
                availablePlaintextAllocationBytes = { Long.MAX_VALUE },
                plaintextAllocator = {
                    throw AssertionError("materialization must not allocate a whole plaintext result")
                },
            )
        assertNull("large entries must never allocate through getIfSmall()", reopened.getIfSmall("large"))
        reopened.materialize("large").use { lease ->
            assertNotNull(lease)
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(lease!!.file.toPath()),
            )
            assertTrue(payload.contentEquals(lease.file.readBytes()))
        }
    }

    @Test
    fun get_largeEntryStillSupportsByteOrientedCallers() {
        val payload = ByteArray(1024 * 1024 + 37) { 6 }
        largeCache().put("large", payload)
        val reopened = largeCache()

        assertTrue(payload.contentEquals(reopened.get("large")))
    }

    @Test
    fun materialize_rejectsTamperedLargeEntryAndDeletesPartialPlaintext() {
        val payload = ByteArray(1024 * 1024 + 37) { 7 }
        val cache = largeCache()
        cache.put("large", payload)
        val envelope = File(dir, sha256Hex("large") + ".enc")
        val bytes = envelope.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        envelope.writeBytes(bytes)

        assertNull(cache.materialize("large"))
        assertFalse(envelope.exists())
        assertTrue(dir.listFiles()?.none { it.name.contains("lease") } ?: true)
    }

    @Test
    fun materialize_dropsStaleIndexWhenBackingFileIsMissing() {
        val cache = largeCache()
        cache.put("large", ByteArray(1024 * 1024 + 37) { 3 })
        File(dir, sha256Hex("large") + ".enc").delete()

        assertNull(cache.materialize("large"))
        assertFalse(cache.contains("large"))
        assertEquals(0L, cache.residentBytes())
    }

    @Test
    fun materialize_generationFenceRejectsPlaintextCompletedAcrossClear() {
        val plaintextCompleted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cache =
            largeCache(
                afterLeasePlaintextWritten = {
                    plaintextCompleted.countDown()
                    release.await(5, TimeUnit.SECONDS)
                },
            )
        cache.put("large", ByteArray(1024 * 1024 + 37) { 4 })
        var lease: DiskByteCacheLease? = null
        val reader = Thread { lease = cache.materialize("large") }
        reader.start()
        assertTrue(plaintextCompleted.await(5, TimeUnit.SECONDS))
        cache.clear()
        release.countDown()
        reader.join(5_000)

        assertNull(lease)
        assertTrue(dir.listFiles()?.none { it.name.contains("lease") } ?: true)
    }

    @Test
    fun materialize_cancellationDeletesPartialPlaintext() {
        val cache = largeCache()
        cache.put("large", ByteArray(1024 * 1024 + 37) { 5 })
        var checks = 0

        try {
            cache.materialize("large") {
                checks += 1
                if (checks >= 3) throw CancellationException("test cancellation")
            }
            throw AssertionError("expected cancellation")
        } catch (_: CancellationException) {
            // Expected.
        }

        assertTrue(dir.listFiles()?.none { it.name.contains("lease") } ?: true)
        assertTrue(cache.contains("large"))
    }

    @Test
    fun materialize_allocationFailureIsRecoverableAndPreservesEncryptedEnvelope() {
        val payload = ByteArray(1024 * 1024 + 37) { 5 }
        val cache =
            largeCache(
                afterLeasePlaintextWritten = {
                    throw OutOfMemoryError("simulated allocation failure")
                },
            )
        cache.put("large", payload)
        val envelope = File(dir, sha256Hex("large") + ".enc")

        assertNull(cache.materialize("large"))

        assertTrue(envelope.isFile)
        assertTrue(cache.contains("large"))
        assertTrue(dir.listFiles()?.none { it.name.contains("lease") } ?: true)
    }

    @Test
    fun clearDeletesAlreadyIssuedPlaintextLease() {
        val cache = largeCache()
        cache.put("large", ByteArray(1024 * 1024 + 37) { 8 })
        val lease = requireNotNull(cache.materialize("large"))
        assertTrue(lease.file.isFile)

        cache.clear()

        assertFalse(lease.file.exists())
        lease.close()
    }

    @Test
    fun hydrationSweepsOrphanedPlaintextLeaseAfterProcessRestart() {
        val cache = largeCache()
        cache.put("large", ByteArray(1024 * 1024 + 37) { 9 })
        val lease = requireNotNull(cache.materialize("large"))
        assertTrue(lease.file.isFile)

        largeCache().size()

        assertFalse(lease.file.exists())
        lease.close()
    }

    private fun largeCache(afterLeasePlaintextWritten: () -> Unit = {}): DiskByteCache =
        DiskByteCache(
            dir,
            keyProvider = keyProvider,
            maxBytes = 2L * 1024L * 1024L,
            maxEntryBytes = 2L * 1024L * 1024L,
            availablePlaintextAllocationBytes = { Long.MAX_VALUE },
            afterLeasePlaintextWritten = afterLeasePlaintextWritten,
        )

    @Test
    fun chunkAuthenticationFailure_isRejectedAndEvicted() {
        val payload = ByteArray(1024 * 1024 + 37) { 7 }
        val writer =
            DiskByteCache(
                dir,
                keyProvider = keyProvider,
                maxBytes = 2L * 1024L * 1024L,
                maxEntryBytes = 2L * 1024L * 1024L,
            )
        writer.put("large", payload)
        val envelope = File(dir, sha256Hex("large") + ".enc")
        val bytes = envelope.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        envelope.writeBytes(bytes)
        val reopened =
            DiskByteCache(
                dir,
                keyProvider = keyProvider,
                maxBytes = 2L * 1024L * 1024L,
                maxEntryBytes = 2L * 1024L * 1024L,
                availablePlaintextAllocationBytes = { Long.MAX_VALUE },
            )

        assertNull(reopened.materialize("large"))
        assertFalse(envelope.exists())
    }

    @Test
    fun legacyAuthenticationFailure_isRejectedBeforePlaintextPublication() {
        val payload = ByteArray(512 * 1024) { 4 }
        DiskByteCache(
            dir,
            keyProvider = keyProvider,
            maxBytes = 2L * 1024L * 1024L,
            maxEntryBytes = 2L * 1024L * 1024L,
        ).put("legacy", payload)
        val envelope = File(dir, sha256Hex("legacy") + ".enc")
        val bytes = envelope.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        envelope.writeBytes(bytes)
        val reopened = largeCache()

        assertNull(reopened.materialize("legacy"))
        assertFalse(envelope.exists())
        assertTrue(dir.listFiles()?.none { it.name.contains("lease") } ?: true)
    }

    @Test
    fun oversizedLegacyEnvelope_isRecoverableNonDestructiveMiss() {
        val payload = ByteArray(1024 * 1024 + 37) { 2 }
        val envelope = writeLegacyEnvelope("legacy-large", payload)
        val reopened = largeCache()

        assertNull(reopened.get("legacy-large"))
        assertTrue(envelope.isFile)
        assertNull(reopened.materialize("legacy-large"))
        assertTrue(envelope.isFile)
    }

    @Test
    fun legacyMaterializationHonorsHeapBudgetWithoutEviction() {
        val payload = ByteArray(512 * 1024) { 5 }
        val envelope = writeLegacyEnvelope("legacy-budget", payload)
        val reopened =
            DiskByteCache(
                cacheDir = dir,
                keyProvider = keyProvider,
                maxBytes = 128L * 1024L * 1024L,
                maxEntryBytes = 64L * 1024L * 1024L,
                // Legacy ciphertext and plaintext coexist during authenticated decryption.
                availablePlaintextAllocationBytes = { payload.size.toLong() },
            )

        assertNull(reopened.materialize("legacy-budget"))
        assertTrue(envelope.isFile)
    }

    @Test
    fun proportionalReserve_preservesTinyEntriesUnderLowHeadroom() {
        val budget = plaintextAllocationBudget(256L * 1024L, 256L * 1024L * 1024L)
        val cache =
            DiskByteCache(
                cacheDir = dir,
                keyProvider = keyProvider,
                maxBytes = 2L * 1024L * 1024L,
                maxEntryBytes = 2L * 1024L * 1024L,
                availablePlaintextAllocationBytes = { budget },
            )
        val payload = ByteArray(64 * 1024) { 9 }

        cache.put("tiny-pending-share", payload)

        assertEquals(192L * 1024L, budget)
        assertArrayEquals(payload, cache.get("tiny-pending-share"))
    }

    @Test
    fun insufficientHeapHeadroom_isRecoverableMissWithoutEvictingValidEntry() {
        val payload = ByteArray(1024 * 1024 + 1) { 5 }
        DiskByteCache(
            dir,
            keyProvider = keyProvider,
            maxBytes = 2L * 1024L * 1024L,
            maxEntryBytes = 2L * 1024L * 1024L,
        ).put("large", payload)
        val constrained =
            DiskByteCache(
                dir,
                keyProvider = keyProvider,
                maxBytes = 2L * 1024L * 1024L,
                maxEntryBytes = 2L * 1024L * 1024L,
                availablePlaintextAllocationBytes = { payload.size.toLong() - 1L },
            )

        assertNull(constrained.get("large"))
        assertTrue(constrained.contains("large"))
        assertTrue(File(dir, sha256Hex("large") + ".enc").exists())
    }

    @Test
    fun plaintextAllocationFailure_isRecoverableMiss() {
        val payload = ByteArray(1024 * 1024 + 1) { 9 }
        DiskByteCache(
            dir,
            keyProvider = keyProvider,
            maxBytes = 2L * 1024L * 1024L,
            maxEntryBytes = 2L * 1024L * 1024L,
        ).put("large", payload)
        val constrained =
            DiskByteCache(
                dir,
                keyProvider = keyProvider,
                maxBytes = 2L * 1024L * 1024L,
                maxEntryBytes = 2L * 1024L * 1024L,
                availablePlaintextAllocationBytes = { Long.MAX_VALUE },
                plaintextAllocator = { throw OutOfMemoryError("injected") },
            )

        assertNull(constrained.get("large"))
        assertTrue(constrained.contains("large"))
    }

    @Test
    fun put_withStaleGeneration_isRejectedAfterClear() {
        val cache = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
        // Capture the generation a deferred write would have grabbed at
        // schedule time, then sign-out wipes the cache before it lands.
        val scheduledToken = cache.capturePublicationToken()
        cache.clear()
        cache.put("k", ByteArray(40) { 7 }, scheduledToken)
        assertNull("a write from a wiped session must not re-persist", cache.get("k"))
        assertEquals(0L, cache.residentBytes())
    }

    @Test
    fun put_withCurrentGeneration_succeedsAfterClear() {
        val cache = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
        cache.clear()
        // A write scheduled after the wipe (current generation) is honored.
        cache.put("k", ByteArray(40) { 7 }, cache.capturePublicationToken())
        assertNotNull(cache.get("k"))
        assertEquals(102L, cache.residentBytes())
    }

    @Test
    fun emptyPut_ignored() {
        val cache = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
        cache.put("k", ByteArray(0))
        assertNull(cache.get("k"))
        assertEquals(0L, cache.residentBytes())
    }

    @Test
    fun replacingKey_updatesByteAccounting() {
        val cache = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
        cache.put("k", ByteArray(40))
        cache.put("k", ByteArray(70))
        assertEquals(1, cache.size())
        assertEquals(132L, cache.residentBytes())
    }

    @Test
    fun tamperedEnvelopeTag_rejectedDuringHydration_beforeExpirySweepTrustsIt() {
        DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
            .put("k", ByteArray(40) { 1 }, DiskByteCachePublicationToken(0, 0), "real-tag")
        val enc = File(dir, sha256Hex("k") + ".enc")
        val bytes = enc.readBytes()
        val tagLen = bytes[5].toInt() and 0xFF
        require(tagLen > 0) { "test requires a tagged envelope" }
        bytes[6] = (bytes[6].toInt() xor 0xFF).toByte()
        enc.writeBytes(bytes)

        val reopened = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)

        assertEquals(0, reopened.size())
        assertFalse("tampered envelopes must be deleted during hydration", enc.exists())
        assertEquals(0, reopened.removeByCiphertextTags(setOf("real-tag")))
    }

    @Test
    fun put_pausedAfterTempCreation_thenClearAndCrash_leavesNoOldSessionFile() {
        class SimulatedCrash : RuntimeException("simulated process death")

        val tempCreated = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        var crashObserved = false
        val cache =
            DiskByteCache(
                cacheDir = dir,
                keyProvider = keyProvider,
                maxBytes = 1024,
                afterTempFileCreated = {
                    tempCreated.countDown()
                    releaseWrite.await(5, TimeUnit.SECONDS)
                },
                afterEncryptedWrite = { throw SimulatedCrash() },
            )
        val payload = ByteArray(40) { 9 }
        val scheduledToken = cache.capturePublicationToken()
        val putThread =
            Thread {
                try {
                    cache.put("k", payload, scheduledToken)
                } catch (_: SimulatedCrash) {
                    crashObserved = true
                }
            }
        putThread.start()
        assertTrue("put must open its temp file under the cache monitor", tempCreated.await(5, TimeUnit.SECONDS))
        cache.clear()
        releaseWrite.countDown()
        putThread.join(5_000)

        assertTrue("test must interrupt the writer before its final generation cleanup", crashObserved)
        assertTrue(
            "clear must leave no old-session temp path even if the process dies before final put cleanup",
            dir.listFiles()?.none { it.isFile && it.name.endsWith(".tmp") } ?: true,
        )
        val reopened = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
        assertNull("a cleared-session put must not survive restart", reopened.get("k"))
        assertTrue(
            "clear must not leave decryptable old-session cache artifacts behind",
            dir.listFiles()?.none { it.isFile && (it.name.endsWith(".enc") || it.name.endsWith(".tmp")) } ?: true,
        )
    }

    @Test
    fun inFlightPut_rejectedWhenExpirySweepDuringWrite() {
        DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
            .put("k", ByteArray(40) { 1 }, DiskByteCachePublicationToken(0, 0), "old-tag")
        val writeFinished = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val cache =
            DiskByteCache(
                cacheDir = dir,
                keyProvider = keyProvider,
                maxBytes = 1024,
                afterEncryptedWrite = {
                    writeFinished.countDown()
                    releaseCommit.await(5, TimeUnit.SECONDS)
                },
            )
        val putThread =
            Thread {
                cache.put("k", ByteArray(40) { 2 }, cache.capturePublicationToken(), "new-tag")
            }
        putThread.start()
        assertTrue("replacement write must finish before commit", writeFinished.await(5, TimeUnit.SECONDS))
        assertEquals(1, cache.removeByCiphertextTags(setOf("old-tag")))
        releaseCommit.countDown()
        putThread.join(5_000)

        val reopened = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
        assertNull("an in-flight replacement must not publish after an expiry sweep", reopened.get("k"))
        assertEquals(0, reopened.removeByCiphertextTags(setOf("new-tag")))
    }

    @Test
    fun expirySweep_afterPutEntryBeforeContext_invalidatesWriter() {
        val epochCaptured = CountDownLatch(1)
        val releasePut = CountDownLatch(1)
        val cache =
            DiskByteCache(
                cacheDir = dir,
                keyProvider = keyProvider,
                maxBytes = 1024,
                afterPutEpochCaptured = {
                    epochCaptured.countDown()
                    releasePut.await(5, TimeUnit.SECONDS)
                },
            )
        cache.prepare()
        val putThread =
            Thread {
                cache.put("k", ByteArray(40) { 2 }, cache.capturePublicationToken(), "expired-tag")
            }

        putThread.start()
        assertTrue("put must capture its sweep epoch at entry", epochCaptured.await(5, TimeUnit.SECONDS))
        assertEquals(0, cache.removeByCiphertextTags(setOf("expired-tag")))
        releasePut.countDown()
        putThread.join(5_000)

        assertNull("a sweep must invalidate a put already in flight at entry", cache.get("k"))
        assertFalse(dir.listFiles()?.any { it.isFile && it.name.endsWith(".enc") } ?: false)
    }

    @Test
    fun put_rejectedWhenExpirySweepRunsDuringSimulatedFetch() {
        val cache = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
        cache.put("k", ByteArray(40) { 1 }, cache.capturePublicationToken(), "sweep-tag")
        val tokenBeforeFetch = cache.capturePublicationToken()
        val fetchPaused = CountDownLatch(1)
        val releaseFetch = CountDownLatch(1)
        val putThread =
            Thread {
                fetchPaused.countDown()
                releaseFetch.await(5, TimeUnit.SECONDS)
                cache.put("k", ByteArray(40) { 2 }, tokenBeforeFetch, "sweep-tag")
            }
        putThread.start()
        assertTrue("simulated fetch must start before the sweep", fetchPaused.await(5, TimeUnit.SECONDS))
        assertEquals(1, cache.removeByCiphertextTags(setOf("sweep-tag")))
        releaseFetch.countDown()
        putThread.join(5_000)

        val reopened = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
        assertNull("a deferred put must not publish after an expiry sweep during fetch", reopened.get("k"))
        assertEquals(0, reopened.removeByCiphertextTags(setOf("sweep-tag")))
    }

    @Test
    fun staleHydration_doesNotDeleteCurrentGenerationEnvelopeAfterClear() {
        File(dir, sha256Hex("k") + ".enc").writeBytes(ByteArray(64).also { bytes -> bytes[0] = 1 })
        val destructiveDeletePaused = CountDownLatch(1)
        val releaseDestructiveDelete = CountDownLatch(1)
        val cache =
            DiskByteCache(
                cacheDir = dir,
                keyProvider = keyProvider,
                maxBytes = 1024,
                beforeHydrationDestructiveDelete = {
                    destructiveDeletePaused.countDown()
                    releaseDestructiveDelete.await(5, TimeUnit.SECONDS)
                },
            )
        val hydrationThread =
            Thread {
                cache.size()
            }
        hydrationThread.start()
        assertTrue(
            "hydration must reach destructive envelope cleanup",
            destructiveDeletePaused.await(5, TimeUnit.SECONDS),
        )
        cache.clear()
        val payload = ByteArray(40) { 9 }
        cache.put("k", payload, cache.capturePublicationToken())
        releaseDestructiveDelete.countDown()
        hydrationThread.join(5_000)

        val reopened = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
        assertTrue(
            "current-generation put must survive stale hydration cleanup",
            reopened.get("k")!!.contentEquals(payload),
        )
    }

    @Test
    fun staleHydration_doesNotDeleteCurrentGenerationTempAfterClear() {
        val orphanSweepPaused = CountDownLatch(1)
        val releaseOrphanSweep = CountDownLatch(1)
        val orphanSweepComplete = CountDownLatch(1)
        val encryptedWriteFinished = CountDownLatch(1)
        val releasePutCommit = CountDownLatch(1)
        val cache =
            DiskByteCache(
                cacheDir = dir,
                keyProvider = keyProvider,
                maxBytes = 1024,
                beforeOrphanTmpSweep = {
                    orphanSweepPaused.countDown()
                    releaseOrphanSweep.await(5, TimeUnit.SECONDS)
                },
                afterOrphanTmpSweep = { orphanSweepComplete.countDown() },
                afterEncryptedWrite = {
                    encryptedWriteFinished.countDown()
                    releasePutCommit.await(5, TimeUnit.SECONDS)
                },
            )
        val hydrationThread =
            Thread {
                cache.put("seed", ByteArray(40) { 1 })
            }
        hydrationThread.start()
        assertTrue("hydration must reach orphan tmp sweep", orphanSweepPaused.await(5, TimeUnit.SECONDS))
        cache.clear()
        val payload = ByteArray(40) { 9 }
        val putThread =
            Thread {
                cache.put("k", payload, cache.capturePublicationToken())
            }
        putThread.start()
        assertTrue("current-generation put must finish encrypting its temp", encryptedWriteFinished.await(5, TimeUnit.SECONDS))
        releaseOrphanSweep.countDown()
        assertTrue("stale hydration must finish its tmp sweep before put commits", orphanSweepComplete.await(5, TimeUnit.SECONDS))
        releasePutCommit.countDown()
        hydrationThread.join(5_000)
        putThread.join(5_000)

        val reopened = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
        assertTrue("current-generation put must survive stale hydration cleanup", reopened.get("k")!!.contentEquals(payload))
    }

    @Test
    fun hydration_preservesEntryOnTransientEnvelopeReadFailure() {
        val payload = ByteArray(40) { 3 }
        DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024).put("k", payload)
        val enc = File(dir, sha256Hex("k") + ".enc")
        val remainingFailures = AtomicInteger(1)
        val flakyProvider =
            DiskByteCacheKeyProvider {
                if (remainingFailures.getAndDecrement() > 0) {
                    throw ProviderException("transient keystore fault")
                }
                keyProvider.getOrCreate()
            }
        val flaky = DiskByteCache(dir, keyProvider = flakyProvider, maxBytes = 1024)
        assertEquals("transient read failure must not index the entry", 0, flaky.size())
        assertTrue("transient read failure must not delete a valid envelope", enc.isFile)

        val recovered = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
        assertTrue(recovered.get("k")!!.contentEquals(payload))
    }

    @Test
    fun expirySweep_failClosedDeletesUnresolvedEnvelopeBeforeProviderRecovers() {
        val key = "acct|grp|expired-msg|0"
        val payload = ByteArray(40) { 5 }
        DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
            .put(key, payload, DiskByteCachePublicationToken(0, 0), "expired-hash")
        val enc = File(dir, sha256Hex(key) + ".enc")
        val remainingFailures = AtomicInteger(1)
        val flakyProvider =
            DiskByteCacheKeyProvider {
                if (remainingFailures.getAndDecrement() > 0) {
                    throw ProviderException("transient keystore fault")
                }
                keyProvider.getOrCreate()
            }
        val flaky = DiskByteCache(dir, keyProvider = flakyProvider, maxBytes = 1024)
        assertEquals("transient hydration must not index the entry", 0, flaky.size())
        assertTrue("transient hydration must preserve the valid envelope", enc.isFile)

        assertTrue(
            "any non-empty expiry sweep must fail closed and delete unresolved envelopes",
            flaky.removeByCiphertextTags(setOf("different-hash")) >= 1,
        )
        assertFalse("unresolved envelope must be deleted during the sweep", enc.exists())

        val recovered = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
        assertNull("expired attachment must not reappear after provider recovery", recovered.get(key))
        assertEquals(0, recovered.size())
    }

    @Test
    fun inFlightReplacement_rejectedWhenExactKeyRemoveDuringUnresolvedWrite() {
        val key = "k"
        val original = ByteArray(40) { 6 }
        DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024).put(key, original)
        val enc = File(dir, sha256Hex(key) + ".enc")
        val remainingFailures = AtomicInteger(1)
        val flakyProvider =
            DiskByteCacheKeyProvider {
                if (remainingFailures.getAndDecrement() > 0) {
                    throw ProviderException("transient keystore fault")
                }
                keyProvider.getOrCreate()
            }
        val writeFinished = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val cache =
            DiskByteCache(
                cacheDir = dir,
                keyProvider = flakyProvider,
                maxBytes = 1024,
                afterEncryptedWrite = {
                    writeFinished.countDown()
                    releaseCommit.await(5, TimeUnit.SECONDS)
                },
            )
        assertEquals("transient hydration must leave the envelope unresolved", 0, cache.size())
        assertTrue(enc.isFile)

        val replacement = ByteArray(40) { 9 }
        val putThread =
            Thread {
                cache.put(key, replacement, cache.capturePublicationToken())
            }
        putThread.start()
        assertTrue("replacement write must finish before commit", writeFinished.await(5, TimeUnit.SECONDS))
        cache.remove(key)
        releaseCommit.countDown()
        putThread.join(5_000)

        assertNull("an in-flight replacement must not publish after exact-key remove", cache.get(key))
        assertFalse("exact-key remove must delete the unresolved envelope", enc.exists())
        val reopened = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
        assertNull("removed unresolved envelope must not reappear after restart", reopened.get(key))
        assertEquals(0, reopened.size())
    }

    @Test
    fun remove_deletesUnresolvedEnvelopeAtExactHashedPath() {
        val payload = ByteArray(40) { 6 }
        DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024).put("k", payload)
        val enc = File(dir, sha256Hex("k") + ".enc")
        val remainingFailures = AtomicInteger(1)
        val flakyProvider =
            DiskByteCacheKeyProvider {
                if (remainingFailures.getAndDecrement() > 0) {
                    throw ProviderException("transient keystore fault")
                }
                keyProvider.getOrCreate()
            }
        val flaky = DiskByteCache(dir, keyProvider = flakyProvider, maxBytes = 1024)
        assertEquals(0, flaky.size())
        assertTrue(enc.isFile)

        flaky.remove("k")

        assertFalse("exact-key remove must delete an unresolved envelope", enc.exists())
        val recovered = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
        assertNull(recovered.get("k"))
        assertEquals(0, recovered.size())
    }

    @Test
    fun concurrentSameKeyPuts_publishReadableEnvelopeAfterRestart() {
        val tempFilesCreated = CountDownLatch(2)
        val releaseEncryption = CountDownLatch(1)
        val cache =
            DiskByteCache(
                cacheDir = dir,
                keyProvider = keyProvider,
                maxBytes = 10_000,
                afterTempFileCreated = {
                    tempFilesCreated.countDown()
                    releaseEncryption.await(5, TimeUnit.SECONDS)
                },
            )
        val payloadA = ByteArray(40) { 1 }
        val payloadB = ByteArray(40) { 2 }
        val done = CountDownLatch(2)
        Thread {
            cache.put("k", payloadA)
            done.countDown()
        }.start()
        Thread {
            cache.put("k", payloadB)
            done.countDown()
        }.start()
        assertTrue(
            "both puts must create distinct temp files before either encrypts",
            tempFilesCreated.await(5, TimeUnit.SECONDS),
        )
        releaseEncryption.countDown()
        assertTrue("both concurrent puts must finish", done.await(5, TimeUnit.SECONDS))

        val finalBytes = cache.get("k")
        assertNotNull(finalBytes)
        assertTrue(
            "concurrent same-key puts must leave one complete payload",
            finalBytes!!.contentEquals(payloadA) || finalBytes.contentEquals(payloadB),
        )
        val reopened = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 10_000)
        val afterRestart = reopened.get("k")
        assertNotNull(afterRestart)
        assertTrue(
            "the winning concurrent put must survive restart",
            afterRestart!!.contentEquals(payloadA) || afterRestart.contentEquals(payloadB),
        )
    }

    @Test
    fun expiryDelete_cannotUnlinkConcurrentSameKeyReput() {
        val staleDeleteStarted = CountDownLatch(1)
        val reputPublished = CountDownLatch(1)
        var observeReput = false
        val cache =
            DiskByteCache(
                cacheDir = dir,
                keyProvider = keyProvider,
                maxBytes = 1024,
                syncCacheDirectory = { directory ->
                    FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
                    if (observeReput) reputPublished.countDown()
                },
                beforeStaleFilesDeleted = {
                    staleDeleteStarted.countDown()
                    reputPublished.await(5, TimeUnit.SECONDS)
                },
            )
        cache.put("k", ByteArray(40) { 1 }, cache.capturePublicationToken(), "old-tag")
        observeReput = true

        val removeThread = Thread { cache.removeByCiphertextTags(setOf("old-tag")) }
        removeThread.start()
        assertTrue("expiry delete must reach its deferred unlink", staleDeleteStarted.await(5, TimeUnit.SECONDS))

        val updated = ByteArray(40) { 2 }
        val putThread = Thread { cache.put("k", updated, cache.capturePublicationToken(), "new-tag") }
        putThread.start()
        assertTrue(
            "the concurrent re-put must publish before stale delete unlinks it",
            reputPublished.await(5, TimeUnit.SECONDS),
        )
        removeThread.join(5_000)
        putThread.join(5_000)

        assertTrue("the concurrent re-put must remain readable", cache.get("k")!!.contentEquals(updated))
        val reopened = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
        assertTrue("the concurrent re-put must survive restart", reopened.get("k")!!.contentEquals(updated))
    }

    @Test
    fun put_syncsParentDirectoryAfterRename_beforeIndexReflectsCommit() {
        val syncOrder = mutableListOf<String>()
        val cache =
            DiskByteCache(
                cacheDir = dir,
                keyProvider = keyProvider,
                maxBytes = 1024,
                renameFile = { source, target ->
                    syncOrder += "rename"
                    source.renameTo(target)
                },
                syncCacheDirectory = { directory ->
                    syncOrder += "sync"
                    FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
                },
            )

        cache.put("k", ByteArray(40))

        assertEquals(listOf("rename", "sync"), syncOrder)
        assertNotNull(cache.get("k"))
    }

    @Test
    fun put_directorySyncFailure_keepsCompleteEnvelopeRecoverable() {
        val payload = ByteArray(40) { 1 }
        val cache =
            DiskByteCache(
                cacheDir = dir,
                keyProvider = keyProvider,
                maxBytes = 1024,
                syncCacheDirectory = { throw IOException("sync failed") },
            )

        cache.put("k", payload)

        assertTrue("a completed atomic rename must not be undone after sync failure", cache.get("k")!!.contentEquals(payload))
        val reopened = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
        assertTrue("the complete envelope remains recoverable", reopened.get("k")!!.contentEquals(payload))
    }

    @Test
    fun replacingKey_keepsTheNewBytesReadableAndOnDisk() {
        // Regression: the replace path scheduled the previous entry's file for
        // deletion after the monitor, but same-key entries share the destination
        // path, so the deferred delete removed the freshly-renamed bytes.
        val cache = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
        cache.put("k", ByteArray(40) { 1 })
        val updated = ByteArray(70) { 2 }
        cache.put("k", updated)

        val out = cache.get("k")
        assertNotNull("replaced value must still be readable", out)
        assertTrue(out!!.contentEquals(updated))
        assertTrue("a `.enc` must remain on disk after replace", dir.listFiles()?.any { it.name.endsWith(".enc") } ?: false)

        // Survives a restart: a fresh cache over the same dir rehydrates it.
        val reopened = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
        val afterRestart = reopened.get("k")
        assertNotNull("replaced value must survive restart", afterRestart)
        assertTrue(afterRestart!!.contentEquals(updated))
    }

    @Test
    fun interruptedTaggedReplacement_neverPairsOldBytesWithNewTagAfterRestart() {
        // Regression for the old two-file layout: an interrupted replace could leave
        // the previous envelope paired with a freshly published `.tag` sidecar.
        // Expiry metadata must come only from the envelope, never a legacy sidecar.
        val original = ByteArray(40) { 1 }
        val writer = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
        writer.put("k", original, writer.capturePublicationToken(), ciphertextTag = "old-ciphertext")
        File(dir, sha256Hex("k") + ".tag").writeText("new-ciphertext")

        val reopened = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
        val bytes = reopened.get("k")
        assertNotNull(bytes)
        assertTrue(bytes!!.contentEquals(original))

        val oldTagEvicts =
            DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
                .let { it.removeByCiphertextTags(setOf("old-ciphertext")) == 1 }
        val newTagEvicts =
            DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
                .let { it.removeByCiphertextTags(setOf("new-ciphertext")) == 1 }
        assertTrue(oldTagEvicts)
        assertFalse(newTagEvicts)
    }

    @Test
    fun interruptedTaggedReplacement_beforeDataRename_keepsOldEnvelopeAfterRestart() {
        class SimulatedCrash : RuntimeException("simulated process death")

        var crashBeforeDataRename = false
        val cache =
            DiskByteCache(
                cacheDir = dir,
                keyProvider = keyProvider,
                maxBytes = 1024,
                renameFile = { source, target ->
                    if (
                        crashBeforeDataRename &&
                        source.name.contains("-enc-") &&
                        target.name.endsWith(".enc")
                    ) {
                        throw SimulatedCrash()
                    }
                    source.renameTo(target)
                },
            )
        val original = ByteArray(40) { 1 }
        cache.put("k", original, cache.capturePublicationToken(), ciphertextTag = "old-ciphertext")

        crashBeforeDataRename = true
        try {
            cache.put("k", ByteArray(40) { 2 }, cache.capturePublicationToken(), ciphertextTag = "new-ciphertext")
            error("expected simulated crash before data publication")
        } catch (_: SimulatedCrash) {
            // Abrupt termination before the sole data rename lands.
        }

        val reopened = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
        assertTrue(reopened.get("k")!!.contentEquals(original))
        assertEquals(1, reopened.removeByCiphertextTags(setOf("old-ciphertext")))
        assertNull(reopened.get("k"))
    }

    @Test
    fun interruptedTaggedReplacement_afterDataRename_publishesNewEnvelopeAfterRestart() {
        class SimulatedCrash : RuntimeException("simulated process death")

        var crashAfterDataRename = false
        val cache =
            DiskByteCache(
                cacheDir = dir,
                keyProvider = keyProvider,
                maxBytes = 1024,
                renameFile = { source, target ->
                    val renamed = source.renameTo(target)
                    if (
                        crashAfterDataRename &&
                        renamed &&
                        source.name.contains("-enc-") &&
                        target.name.endsWith(".enc")
                    ) {
                        throw SimulatedCrash()
                    }
                    renamed
                },
            )
        cache.put("k", ByteArray(40) { 1 }, cache.capturePublicationToken(), ciphertextTag = "old-ciphertext")
        val updated = ByteArray(40) { 2 }

        crashAfterDataRename = true
        try {
            cache.put("k", updated, cache.capturePublicationToken(), ciphertextTag = "new-ciphertext")
            error("expected simulated crash after data publication")
        } catch (_: SimulatedCrash) {
            // Index may be inconsistent in-memory; restart is the proof.
        }

        val reopened = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
        assertTrue(reopened.get("k")!!.contentEquals(updated))
        assertEquals(1, reopened.removeByCiphertextTags(setOf("new-ciphertext")))
        assertNull(reopened.get("k"))
    }

    @Test
    fun failedReplacementKeepsOldBytesAndRestoresTheirTagAcrossRestart() {
        var failDataCommit = false
        val cache =
            DiskByteCache(
                cacheDir = dir,
                keyProvider = keyProvider,
                maxBytes = 1024,
                renameFile = { source, target ->
                    if (failDataCommit && source.name.contains("-enc-") && target.name.endsWith(".enc")) {
                        false
                    } else {
                        source.renameTo(target)
                    }
                },
            )
        val original = ByteArray(40) { 1 }
        cache.put("k", original, cache.capturePublicationToken(), ciphertextTag = "old-ciphertext")

        failDataCommit = true
        cache.put("k", ByteArray(40) { 2 }, cache.capturePublicationToken(), ciphertextTag = "new-ciphertext")

        assertTrue(cache.get("k")!!.contentEquals(original))
        val reopened = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
        assertTrue(reopened.get("k")!!.contentEquals(original))
        assertEquals(0, reopened.removeByCiphertextTags(setOf("new-ciphertext")))
        assertEquals(1, reopened.removeByCiphertextTags(setOf("old-ciphertext")))
        assertNull(reopened.get("k"))
    }

    @Test
    fun failedUntaggedReplacementKeepsTaggedExistingEntryAndTag() {
        // Regression: an untagged same-key replacement whose `.enc` rename fails
        // never touches the tag file, so the still-valid tagged existing entry
        // must survive — the fail-closed cleanup only applies to puts that
        // attempted a tag rename (tagTmp != null).
        var failDataCommit = false
        val cache =
            DiskByteCache(
                cacheDir = dir,
                keyProvider = keyProvider,
                maxBytes = 1024,
                renameFile = { source, target ->
                    if (failDataCommit && source.name.contains("-enc-") && target.name.endsWith(".enc")) {
                        false
                    } else {
                        source.renameTo(target)
                    }
                },
            )
        val original = ByteArray(40) { 1 }
        cache.put("k", original, cache.capturePublicationToken(), ciphertextTag = "old-ciphertext")

        failDataCommit = true
        cache.put("k", ByteArray(40) { 2 }, cache.capturePublicationToken(), ciphertextTag = null)

        assertTrue("old bytes must survive a failed untagged replacement", cache.get("k")!!.contentEquals(original))
        val reopened = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
        assertTrue("old bytes must survive restart", reopened.get("k")!!.contentEquals(original))
        // The old tag must still authorize eviction — proof the sidecar was untouched.
        assertEquals(1, reopened.removeByCiphertextTags(setOf("old-ciphertext")))
        assertNull(reopened.get("k"))
    }

    @Test
    fun get_refreshesFileLastModifiedForReadRecency() {
        val cache = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
        cache.put("a", ByteArray(40))
        val file = dir.listFiles { f -> f.isFile }!!.single()
        file.setLastModified(1_000L)
        cache.get("a")
        // Post-restart rehydration orders by lastModified, so a read must
        // refresh it or read-hot entries get evicted first. See #228.
        assertTrue(file.lastModified() > 1_000L)
    }

    @Test
    fun pastCap_evictsLRU() {
        // 220-byte cap; three 40-byte payloads (102-byte envelopes) push over → oldest evicted.
        val cache = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 220)
        cache.put("a", ByteArray(40))
        cache.put("b", ByteArray(40))
        cache.put("c", ByteArray(40)) // 306 → evict a
        assertNull(cache.get("a"))
        assertNotNull(cache.get("b"))
        assertNotNull(cache.get("c"))
        assertTrue(cache.residentBytes() <= 220)
    }

    @Test
    fun successfulMutationsPublishCacheRevisionSignals() {
        val mutations = AtomicInteger(0)
        val cache =
            DiskByteCache(
                dir,
                keyProvider = keyProvider,
                maxBytes = 220,
                onMutation = { mutations.incrementAndGet() },
            )

        cache.put("a", ByteArray(40))
        cache.put("b", ByteArray(40))
        cache.put("c", ByteArray(40)) // publishes the put and its LRU eviction
        cache.remove("b")
        cache.remove("missing")
        cache.put(
            "tagged",
            ByteArray(8),
            cache.capturePublicationToken(),
            ciphertextTag = "ciphertext-tag",
        )
        val mutationsBeforeTagRemoval = mutations.get()
        assertEquals(0, cache.removeByCiphertextTags(setOf("missing-tag")))
        assertEquals(mutationsBeforeTagRemoval, mutations.get())
        assertEquals(1, cache.removeByCiphertextTags(setOf("ciphertext-tag")))
        assertEquals(mutationsBeforeTagRemoval + 1, mutations.get())
        assertFalse(cache.contains("tagged"))
        cache.clear()

        assertEquals(7, mutations.get())
        assertFalse(cache.contains("a"))
        assertFalse(cache.contains("b"))
        assertFalse(cache.contains("c"))
    }

    @Test
    fun get_promotesToMRU_protectsFromEviction() {
        val cache = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 220)
        cache.put("a", ByteArray(40))
        cache.put("b", ByteArray(40))
        cache.get("a") // bump a to MRU
        cache.put("c", ByteArray(40)) // 306 → evict b (now LRU)
        assertNotNull(cache.get("a"))
        assertNull(cache.get("b"))
        assertNotNull(cache.get("c"))
    }

    @Test
    fun clear_deletesAllFiles() {
        val cache = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
        cache.put("a", ByteArray(30))
        cache.put("b", ByteArray(30))
        cache.clear()
        assertEquals(0, cache.size())
        assertEquals(0L, cache.residentBytes())
        assertNull(cache.get("a"))
        // Directory remains but empty (orphan-sweep happens in clear).
        assertEquals(0, dir.listFiles()?.size ?: 0)
    }

    @Test
    fun clearBeforeHydrationSweepsOwnedFilesWithoutIndexScan() {
        DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024).put("a", ByteArray(30))
        val fresh = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)

        fresh.clear()

        assertEquals(0, fresh.size())
        assertEquals(0L, fresh.residentBytes())
        assertEquals(0, dir.listFiles()?.size ?: 0)
    }

    @Test
    fun reinit_rehydratesIndexFromDisk() {
        // The whole point of L2: process restart rehydrates the cache.
        DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024).run {
            put("a", ByteArray(40) { 1 })
            put("b", ByteArray(50) { 2 })
        }
        // Simulate process restart by constructing a new instance.
        val rehydrated = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
        assertEquals(2, rehydrated.size())
        assertEquals(214L, rehydrated.residentBytes())
        val a = rehydrated.get("a")
        assertNotNull(a)
        assertTrue(a!!.all { it == 1.toByte() })
        val b = rehydrated.get("b")
        assertNotNull(b)
        assertTrue(b!!.all { it == 2.toByte() })
    }

    @Test
    fun rehydration_isDeferredUntilFirstAccess() {
        // #100: the constructor must not do directory I/O (it ran on the main
        // thread at app launch). Proof: build the cache over an empty dir, then
        // have a *separate* instance write an entry to the same dir. If the
        // first instance only rehydrates on first access it scans the dir now
        // and sees the entry; eager constructor rehydration would have missed
        // it (the dir was empty at construction time).
        val cache = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
        DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024).put("late", ByteArray(40) { 9 })

        val out = cache.get("late")
        assertNotNull(out)
        assertTrue(out!!.all { it == 9.toByte() })
    }

    @Test
    fun contains_beforeHydration_reportsMissWithoutStattingDisk() {
        // #983: contains() is reached from composition, so the un-hydrated
        // branch must not stat the backing file — it reports a plain miss and
        // leaves hydration to the first real get/put on Dispatchers.IO. Proof:
        // an entry persisted by a previous "session" is invisible to a fresh
        // instance's contains() until something hydrates the index.
        DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024).put("persisted", ByteArray(40) { 7 })

        val cold = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
        assertFalse(cold.contains("persisted"))

        // First real read hydrates; the probe now sees the on-disk entry.
        assertNotNull(cold.get("persisted"))
        assertTrue(cold.contains("persisted"))
    }

    @Test
    fun coldReadinessHandoffPublishesOnlyAfterAuthenticatedBytesAreReadable() {
        val payload = ByteArray(40) { 8 }
        DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024).put("persisted", payload)
        val hydrationEntered = CountDownLatch(1)
        val releaseHydration = CountDownLatch(1)
        val cold =
            DiskByteCache(
                BlockingListFilesDir(dir, hydrationEntered, releaseHydration),
                keyProvider = keyProvider,
                maxBytes = 1024,
            )
        val readFinished = CountDownLatch(1)
        var authenticated: ByteArray? = null
        val reader =
            Thread {
                try {
                    authenticated = cold.get("persisted")
                } finally {
                    readFinished.countDown()
                }
            }

        reader.start()
        try {
            assertTrue(hydrationEntered.await(5, TimeUnit.SECONDS))
            assertFalse(
                "readiness must not publish while cold hydration is blocked",
                readFinished.await(100, TimeUnit.MILLISECONDS),
            )
            assertFalse("the main-safe peek remains a miss before handoff", cold.contains("persisted"))
        } finally {
            releaseHydration.countDown()
            reader.join(5_000)
        }

        assertTrue(readFinished.await(5, TimeUnit.SECONDS))
        assertTrue(authenticated!!.contentEquals(payload))
        assertTrue("the index is ready after the authenticated handoff", cold.contains("persisted"))
    }

    @Test
    fun contains_afterHydration_reflectsIndexWithoutSeedingIt() {
        val cache = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
        cache.put("present", ByteArray(40) { 1 })

        assertTrue(cache.contains("present"))
        assertFalse(cache.contains("absent"))
        // Strictly a peek: probing an absent key must not create an entry.
        assertEquals(1, cache.size())
    }

    @Test
    fun put_overloadsAreNotSynchronizedMethods() {
        val deferredPut =
            DiskByteCache::class.java.getDeclaredMethod(
                "put",
                String::class.java,
                ByteArray::class.java,
                DiskByteCachePublicationToken::class.java,
                String::class.java,
            )
        val immediatePut =
            DiskByteCache::class.java.getDeclaredMethod(
                "put",
                String::class.java,
                ByteArray::class.java,
            )

        assertFalse(
            "deferred put must not hold the object monitor across disk writes",
            Modifier.isSynchronized(deferredPut.modifiers),
        )
        assertFalse(
            "immediate put must not hold the object monitor across disk writes",
            Modifier.isSynchronized(immediatePut.modifiers),
        )
    }

    @Test
    fun put_coldHydrationDoesNotBlockContainsMonitor() {
        DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024).put("persisted", ByteArray(40) { 3 })

        val hydrationEntered = CountDownLatch(1)
        val releaseHydration = CountDownLatch(1)
        val blockingDir = BlockingListFilesDir(dir, hydrationEntered, releaseHydration)
        val cache = DiskByteCache(blockingDir, keyProvider = keyProvider, maxBytes = 1024)
        val putFinished = CountDownLatch(1)
        val putThread =
            Thread {
                try {
                    cache.put("new", ByteArray(40) { 4 }, cache.capturePublicationToken())
                } finally {
                    putFinished.countDown()
                }
            }
        putThread.start()

        assertTrue(
            "put should enter cold hydration",
            hydrationEntered.await(5, TimeUnit.SECONDS),
        )
        assertFalse(
            "put should still be parked in cold hydration",
            putFinished.await(100, TimeUnit.MILLISECONDS),
        )

        val containsFinished = CountDownLatch(1)
        var containsValue: Boolean? = null
        val containsThread =
            Thread {
                try {
                    containsValue = cache.contains("persisted")
                } finally {
                    containsFinished.countDown()
                }
            }
        containsThread.start()

        try {
            assertTrue(
                "contains must not wait for cold put hydration to release the cache monitor",
                containsFinished.await(500, TimeUnit.MILLISECONDS),
            )
            assertFalse(
                "cold contains still reports miss before hydration is installed",
                containsValue!!,
            )
        } finally {
            releaseHydration.countDown()
            putThread.join(5_000)
            containsThread.join(5_000)
        }
        assertTrue("put should finish after hydration is released", putFinished.await(5, TimeUnit.SECONDS))
        assertNotNull(cache.get("new"))
    }

    @Test
    fun reinit_evictsToFitReducedCap() {
        DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024).run {
            put("a", ByteArray(40))
            put("b", ByteArray(40))
            put("c", ByteArray(40))
        }
        // Restart with tighter cap — should trim down on init.
        val tighter = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 50)
        assertTrue(tighter.residentBytes() <= 50)
        assertTrue(tighter.size() <= 1)
    }

    @Test
    fun missingFileOnDisk_evictsIndexEntryReturnsNull() {
        val cache = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
        cache.put("k", ByteArray(40))
        // Tamper: delete the file out from under the cache.
        dir.listFiles()?.forEach { it.delete() }
        assertNull(cache.get("k"))
        assertEquals(0, cache.size())
        assertEquals(0L, cache.residentBytes())
    }

    @Test
    fun get_transientReadError_keepsIndexedEntrySubjectToByteCap() {
        val cache = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 220)
        cache.put("a", ByteArray(40))
        cache.put("b", ByteArray(40))
        val unreadable = File(dir, sha256Hex("a") + ".enc")
        assertTrue(unreadable.setReadable(false, false))

        assertNull("transient read failure is a miss", cache.get("a"))
        assertTrue("backing path must still exist", unreadable.isFile)
        assertTrue(cache.contains("a"))
        assertEquals(2, cache.size())
        assertEquals(204L, cache.residentBytes())

        cache.put("c", ByteArray(40))
        assertTrue(cache.residentBytes() <= 220)
        assertNull(cache.get("b"))
        assertNotNull(cache.get("c"))

        unreadable.setReadable(true, false)
    }

    @Test
    fun clear_skipsForeignFilesInDir() {
        // Defensive: if a future co-tenant ever drops files in cacheDir,
        // clear() must not wipe them. Only our own `.enc` / `.tmp` files.
        val cache = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
        cache.put("a", ByteArray(30))
        val foreign = File(dir, "not-mine.txt").also { it.writeText("hello") }
        cache.clear()
        // Our entry is gone…
        assertNull(cache.get("a"))
        assertEquals(0, cache.size())
        // …but the foreign file survives.
        assertTrue("foreign file should survive", foreign.exists())
        assertEquals("hello", foreign.readText())
    }

    @Test
    fun put_writesAtomically_noPartialFile() {
        // Sanity check that the .tmp → rename dance leaves no `.tmp` files
        // behind on successful writes.
        val cache = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
        cache.put("k", ByteArray(40))
        val tmpFiles = dir.listFiles()?.filter { it.name.endsWith(".tmp") }.orEmpty()
        assertTrue("no .tmp file should linger after successful put", tmpFiles.isEmpty())
    }

    @Test
    fun tempFileNamesUseAProcessCounterForSameKeyWrites() {
        val cache = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
        val method =
            DiskByteCache::class.java
                .getDeclaredMethod("uniqueTmpFile", String::class.java, String::class.java)
                .apply { isAccessible = true }

        val names =
            (0 until 256).map {
                (method.invoke(cache, "same-hash", "bin") as File).name
            }

        assertEquals(names.size, names.toSet().size)
        assertTrue(names.all { it.startsWith("same-hash-bin-") && it.endsWith(".tmp") })
    }

    @Test
    fun removeByCiphertextTags_evictsTaggedEntriesOnly() {
        val cache = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
        cache.put("acct|grp|msg-1|0", ByteArray(30), cache.capturePublicationToken(), "hash-a")
        cache.put("acct|grp|msg-2|0", ByteArray(30), cache.capturePublicationToken(), "hash-b")
        cache.put("acct|grp|msg-3|0", ByteArray(30)) // untagged
        val removed = cache.removeByCiphertextTags(setOf("hash-a"))
        assertEquals(1, removed)
        assertNull(cache.get("acct|grp|msg-1|0"))
        assertNotNull(cache.get("acct|grp|msg-2|0"))
        assertNotNull(cache.get("acct|grp|msg-3|0"))
        assertEquals(190L, cache.residentBytes())
    }

    @Test
    fun removeByCiphertextTags_worksAfterRehydrate_forUnloadedMedia() {
        // The #334 crux: media cached in a prior session must still be evictable
        // by ciphertext hash after a process restart, when nothing in memory maps
        // the hash to its cache key. Proven by tagging, dropping the instance, and
        // evicting purely by hash from a fresh instance over the same dir.
        // generation 0 is the initial generation of a fresh instance.
        DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
            .put("acct|grp|old-msg|0", ByteArray(40) { 5 }, DiskByteCachePublicationToken(0, 0), "expired-hash")
        val rehydrated = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
        assertNotNull("entry should survive the restart", rehydrated.get("acct|grp|old-msg|0"))
        val removed = rehydrated.removeByCiphertextTags(setOf("expired-hash"))
        assertEquals("the persisted tag must drive eviction across sessions", 1, removed)
        assertNull(rehydrated.get("acct|grp|old-msg|0"))
        assertEquals(0L, rehydrated.residentBytes())
        // The sidecar must be gone too, not orphaned.
        assertTrue(dir.listFiles()?.none { it.name.endsWith(".tag") } ?: true)
    }

    @Test
    fun blankLegacyTagCannotDowngradeAuthenticatedEnvelopeMetadata() {
        val key = "acct|grp|expired-msg|0"
        val writer = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
        writer.put(key, ByteArray(40) { 7 }, writer.capturePublicationToken(), "expired-hash")
        File(dir, sha256Hex(key) + ".tag").writeText("")

        val reopened = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)

        assertEquals(1, reopened.removeByCiphertextTags(setOf("expired-hash")))
        assertNull(reopened.get(key))
        assertTrue(dir.listFiles()?.none { it.name.endsWith(".tag") } ?: true)
    }

    @Test
    fun taggedPut_failsClosed_whenCommitCannotComplete() {
        // The ciphertext tag authorizes hash-based expiry deletion, so a tagged
        // write must fail closed: if the envelope cannot be published, no
        // encrypted cache entry may survive without its expiry metadata.
        var failCommit = false
        val key = "acct|grp|msg|0"
        val cache =
            DiskByteCache(
                cacheDir = dir,
                keyProvider = keyProvider,
                maxBytes = 1024,
                renameFile = { source, target ->
                    if (failCommit && source.name.contains("-enc-") && target.name.endsWith(".enc")) {
                        false
                    } else {
                        source.renameTo(target)
                    }
                },
            )
        failCommit = true
        cache.put(key, ByteArray(40) { 1 }, cache.capturePublicationToken(), "the-hash")
        assertNull("a tagged write whose commit failed must not be readable", cache.get(key))
        assertEquals(0, cache.size())
        assertEquals(0L, cache.residentBytes())
        assertTrue(
            "no encrypted cache entry may linger when its required envelope could not be published",
            dir.listFiles()?.none { it.isFile && it.name.endsWith(".enc") } ?: true,
        )
    }

    @Test
    fun removeByCiphertextTags_emptySet_isNoOp() {
        val cache = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
        cache.put("k", ByteArray(30), cache.capturePublicationToken(), "h")
        assertEquals(0, cache.removeByCiphertextTags(emptySet()))
        assertNotNull(cache.get("k"))
    }

    @Test
    fun differentKeys_collideToDifferentFiles() {
        // Defense against hash collision oversight — two keys must map to
        // two distinct files. (sha256 makes real collisions improbable; this
        // pins that we're hashing the key, not the file content.)
        val cache = DiskByteCache(dir, keyProvider = keyProvider, maxBytes = 1024)
        cache.put("alice|group|msg-1", ByteArray(20))
        cache.put("bob|group|msg-1", ByteArray(30))
        assertEquals(2, cache.size())
        assertEquals(174L, cache.residentBytes())
    }

    private fun writeLegacyEnvelope(
        key: String,
        plaintext: ByteArray,
    ): File {
        val cache = largeCache()
        val fileName = sha256Hex(key) + ".enc"
        val header =
            DiskByteCache::class.java
                .getDeclaredMethod("envelopeHeaderBytes", String::class.java, Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .invoke(cache, null, 1) as ByteArray
        val metadataAad =
            DiskByteCache::class.java
                .getDeclaredMethod("buildMetadataAad", String::class.java, ByteArray::class.java)
                .apply { isAccessible = true }
                .invoke(cache, fileName, header) as ByteArray
        val metadataCipher = Cipher.getInstance("AES/GCM/NoPadding")
        metadataCipher.init(Cipher.ENCRYPT_MODE, keyProvider.getOrCreate())
        metadataCipher.updateAAD(metadataAad)
        val metadataTag = metadataCipher.doFinal()
        val payloadAad =
            DiskByteCache::class.java
                .getDeclaredMethod(
                    "buildPayloadAad",
                    String::class.java,
                    ByteArray::class.java,
                    ByteArray::class.java,
                    ByteArray::class.java,
                ).apply { isAccessible = true }
                .invoke(cache, fileName, header, metadataCipher.iv, metadataTag) as ByteArray
        val payloadCipher = Cipher.getInstance("AES/GCM/NoPadding")
        payloadCipher.init(Cipher.ENCRYPT_MODE, keyProvider.getOrCreate())
        payloadCipher.updateAAD(payloadAad)
        val envelope = File(dir, fileName)
        FileOutputStream(envelope).use { output ->
            output.write(header)
            output.write(metadataCipher.iv)
            output.write(metadataTag)
            output.write(payloadCipher.iv)
            output.write(payloadCipher.doFinal(plaintext))
        }
        return envelope
    }

    private fun sha256Hex(value: String): String {
        val digest =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private class BlockingListFilesDir(
        private val delegate: File,
        private val hydrationEntered: CountDownLatch,
        private val releaseHydration: CountDownLatch,
    ) : File(delegate.path) {
        override fun mkdirs(): Boolean = delegate.mkdirs()

        override fun listFiles(): Array<File>? {
            hydrationEntered.countDown()
            try {
                releaseHydration.await(5, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            return delegate.listFiles()
        }
    }
}
