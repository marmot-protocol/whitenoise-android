package dev.ipf.whitenoise.android.media

import dev.ipf.whitenoise.android.state.StalenessGuard
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.ProviderException
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal fun interface DiskByteCacheKeyProvider {
    /** Returns the non-exportable key used to authenticate encrypted cache envelopes. */
    @Throws(GeneralSecurityException::class, IOException::class)
    fun getOrCreate(): SecretKey
}

/** Captures wipe and expiry-sweep state for a deferred cache publication. */
internal data class DiskByteCachePublicationToken(
    val generation: Long,
    val expirySweepEpoch: Long,
)

/** Owner-private authenticated plaintext materialization. Closing deletes it. */
internal class DiskByteCacheLease internal constructor(
    val file: File,
    private val onDeleteFailure: (String) -> Unit = { message ->
        Logger.getLogger("DiskByteCacheLease").warning(message)
    },
) : Closeable {
    /** Deletes the owner-private plaintext lease, leaving failed cleanup for hydration. */
    override fun close() {
        val failure =
            runCatching { file.delete() || !file.exists() }
                .fold(
                    onSuccess = { deleted -> if (deleted) null else "delete returned false" },
                    onFailure = { error -> error.message ?: error::class.java.simpleName },
                )
        if (failure != null) {
            // Orphan lease files are retried by the next hydration sweep or clear().
            onDeleteFailure("failed to delete plaintext cache lease ${file.absolutePath}: $failure")
        }
    }
}

/** Reserves proportional process headroom before permitting a plaintext allocation. */
internal fun plaintextAllocationBudget(
    availableBytes: Long,
    maxMemoryBytes: Long,
): Long {
    val available = availableBytes.coerceAtLeast(0L)
    val reserve =
        minOf(
            32L * 1024L * 1024L,
            maxMemoryBytes.coerceAtLeast(0L) / 8L,
            available / 4L,
        )
    return (available - reserve).coerceAtLeast(0L)
}

/**
 * On-disk byte cache, bounded by total size. Persists across process
 * restarts so re-opening a chat doesn't re-download every visible image.
 *
 * Sits as L2 behind an in-memory L1 ([ByteSizeLruCache]):
 *
 *   `controller.downloadAttachment` → L1 hit → return
 *                                   → L2 hit → hydrate L1, return
 *                                   → FFI download → store in both, return
 *
 * Files live under [cacheDir] — typically `context.cacheDir/decrypted-media/`
 * which Android does not back up to cloud by default. Each entry is a single
 * versioned AES-256-GCM envelope under `sha256(key).enc`; the original key is
 * not recoverable from disk (no stable account/group/messageId leak via `ls`).
 *
 * The envelope optionally embeds the attachment's ciphertext SHA-256 so the
 * disappearing-message sweep can evict an expired attachment by hash even when
 * its message isn't currently loaded (the in-memory hash→key map only covers
 * loaded rows). The hash is non-secret metadata bound into the GCM AAD.
 *
 * Index state is synchronized on `this`, but expensive read/write/hydration
 * disk I/O is kept outside that monitor so main-thread probes don't block on
 * background cache work. `clear()` is the exception: sign-out/account-switch
 * wipes intentionally hold the monitor to preserve the privacy guarantee.
 *
 * Eviction is LRU by access order via `LinkedHashMap(accessOrder=true)`.
 * During preparation, the directory is scanned and the in-memory index is
 * repopulated using file `lastModified` as the proxy for recency.
 */
internal class DiskByteCache(
    private val cacheDir: File,
    private val maxBytes: Long,
    private val keyProvider: DiskByteCacheKeyProvider,
    maxEntryBytes: Long = DEFAULT_MAX_ENTRY_BYTES,
    private val renameFile: (File, File) -> Boolean = { source, target -> source.renameTo(target) },
    private val syncCacheDirectory: (File) -> Unit = { directory ->
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { channel ->
            channel.force(true)
        }
    },
    private val afterTempFileCreated: () -> Unit = {},
    private val afterFileNameHashed: () -> Unit = {},
    private val afterEncryptedWrite: () -> Unit = {},
    private val afterPutEpochCaptured: () -> Unit = {},
    private val beforeStaleFilesDeleted: () -> Unit = {},
    private val beforeOrphanTmpSweep: () -> Unit = {},
    private val afterOrphanTmpSweep: () -> Unit = {},
    private val beforeHydrationDestructiveDelete: () -> Unit = {},
    private val onMutation: () -> Unit = {},
    private val availablePlaintextAllocationBytes: () -> Long = ::defaultAvailablePlaintextAllocationBytes,
    private val plaintextAllocator: (Int) -> ByteArray = { ByteArray(it) },
    private val maxInMemoryEntryBytes: Long = LEGACY_SINGLE_PAYLOAD_MAX_BYTES.toLong(),
    private val afterLeasePlaintextWritten: () -> Unit = {},
) {
    // accessOrder = true → LinkedHashMap iterates in LRU order for eviction.
    private val index = LinkedHashMap<String, Entry>(8, 0.75f, true)

    // Hydration-time transient auth failures leave valid envelopes on disk but
    // outside the index until restart. Tracked here so expiry sweeps and exact-key
    // removal can still delete them without trusting unauthenticated tags.
    private val unresolvedEnvelopes = mutableSetOf<String>()
    private var residentBytes: Long = 0L
    private var hydrated = false
    private var legacyPlaintextWiped = false
    private val hydrationLock = Any()

    // Bumped on every clear(). A deferred put() captures this at schedule time
    // and is rejected if a wipe intervened, so decrypted plaintext from a
    // signed-out session can't be re-persisted after sign-out. See #154.
    private val cacheLifetime = StalenessGuard()

    // Bumped at the start of every non-empty removeByCiphertextTags sweep so an
    // in-flight put cannot publish after expiry metadata was invalidated.
    private val expirySweeps = StalenessGuard()
    private val entryByteLimit = minOf(maxBytes, maxEntryBytes).coerceAtLeast(1L)

    // No directory I/O in the constructor. AppState calls prepare() on
    // Dispatchers.IO at launch, and the first cache operation remains a lazy
    // fallback for tests or other callers. See #100.

    /** Performs deferred disk initialization. Call from an I/O dispatcher. */
    fun prepare() = ensureHydrated()

    /** Installs one authenticated cold-start index unless a concurrent clear supersedes it. */
    private fun ensureHydrated() {
        if (synchronized(this) { hydrated && legacyPlaintextWiped }) return
        synchronized(hydrationLock) {
            cacheDir.mkdirs()
            val legacyCleanupComplete = wipeLegacyPlaintext()
            val generationAtStart =
                synchronized(this) {
                    if (legacyCleanupComplete) legacyPlaintextWiped = true
                    if (hydrated) null else cacheLifetime.capture()
                } ?: return
            val snapshot = buildHydratedIndex(generationAtStart)
            synchronized(this) install@{
                if (hydrated || !cacheLifetime.isCurrent(generationAtStart)) return@install
                index.clear()
                index.putAll(snapshot.index)
                unresolvedEnvelopes.clear()
                unresolvedEnvelopes.addAll(snapshot.unresolvedEnvelopes)
                residentBytes = snapshot.residentBytes
                hydrated = true
            }
        }
    }

    /**
     * Yes/no probe — true iff bytes for [key] are currently indexed on
     * disk. Doesn't read the file, doesn't promote LRU, doesn't fall
     * through to anywhere. Lets a caller decide UI affordances (e.g. show
     * a download chevron only on miss) without paying the read cost.
     *
     * Before the first hydration this reports a miss WITHOUT stat-ing the
     * backing file: contains() is reached from composition (via
     * `ConversationController.hasCachedAttachment`), and an inline
     * `File.isFile`/`length()` would put the deferred directory I/O (#100)
     * back on the caller's thread. A cold-start false only means the bubble
     * shows its download affordance until the first real get/put hydrates
     * the index off-thread. See #983.
     */
    fun contains(key: String): Boolean {
        val hashed = fileNameFor(key)
        return synchronized(this) {
            hydrated && index.containsKey(hashed)
        }
    }

    /**
     * Definitive off-main probe. Unlike [contains], this waits for cold-start
     * hydration before reporting a miss, but still avoids decrypting the entry.
     */
    fun containsAfterHydration(key: String): Boolean {
        ensureHydrated()
        return contains(key)
    }

    /** Returns an authenticated entry when its bounded allocation fits current heap headroom. */
    fun get(key: String): ByteArray? = getInternal(key, smallOnly = false)

    /** Returns only entries that are safe to promote into the bounded plaintext L1. */
    fun getIfSmall(key: String): ByteArray? = getInternal(key, smallOnly = true)

    /** Authenticates and reads one entry while preserving recoverable misses on resource pressure. */
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private fun getInternal(
        key: String,
        smallOnly: Boolean,
    ): ByteArray? {
        val hashed = fileNameFor(key)
        // Look up (and LRU-promote) the entry under the lock, then read the
        // file OUTSIDE it. Holding the monitor across decryption serialized
        // every concurrent media load and blocked clear() for the duration of
        // disk I/O. See #99.
        ensureHydrated()
        val (entry, generationAtLookup) =
            synchronized(this) {
                (index[hashed] ?: return null) to cacheLifetime.capture()
            }
        if (smallOnly && entry.plaintextSize > maxInMemoryEntryBytes) return null
        return try {
            val bytes = readEncrypted(entry.file, hashed)
            synchronized(this) {
                // A concurrent clear() (sign-out / account switch) bumps
                // `generation` and deletes files under this lock, but an
                // already-open read still succeeds after the unlink on POSIX —
                // so re-check before handing back plaintext for a session that
                // was wiped mid-read. Mirrors put()'s write-side guard (#154).
                // See #376.
                if (!cacheLifetime.isCurrent(generationAtLookup) || index[hashed] !== entry) return null
            }
            // The post-restart LRU rebuild uses file lastModified as the recency
            // proxy, so a read must touch it or frequently-read entries look stale
            // and get evicted first after a restart. Best-effort, and deliberately
            // outside the monitor because setLastModified is blocking disk I/O.
            entry.file.setLastModified(System.currentTimeMillis())
            bytes
        } catch (error: IOException) {
            if (error.isAuthenticationFailure() || error.isMalformedEnvelope()) {
                evictPoisonedEntry(hashed, entry, generationAtLookup)
                return null
            }
            // Distinguish a vanished backing file from a transient read failure
            // (permission, temporary I/O). Only drop stale index state when the
            // path is actually gone; otherwise report a miss but keep accounting
            // so LRU eviction can still reclaim the bytes. See #1321.
            val backingStillPresent = entry.file.isFile
            synchronized(this) {
                // Only evict if a concurrent put() hasn't already replaced it.
                if (!backingStillPresent && index[hashed] === entry) {
                    index.remove(hashed)
                    residentBytes -= entry.size
                }
            }
            null
        } catch (error: GeneralSecurityException) {
            if (error.isAuthenticationFailure()) {
                evictPoisonedEntry(hashed, entry, generationAtLookup)
            }
            // Keystore/provider faults are cache misses. Never fall back to
            // writing or returning plaintext when encryption is unavailable.
            null
        } catch (_: ProviderException) {
            null
        } catch (_: OutOfMemoryError) {
            // A cache hit is optional. If the process cannot reserve the single
            // plaintext result array, preserve the authenticated envelope for a
            // later attempt and let the caller continue through its miss path.
            null
        }
    }

    /** Authenticates a cache entry into an owner-private temporary file. */
    @Suppress("CyclomaticComplexMethod", "ReturnCount", "ThrowsCount")
    fun materialize(
        key: String,
        cancellationCheck: () -> Unit = {},
    ): DiskByteCacheLease? {
        val hashed = fileNameFor(key)
        ensureHydrated()
        val (entry, generationAtLookup) =
            synchronized(this) {
                (index[hashed] ?: return null) to cacheLifetime.capture()
            }
        val tmp = uniqueTmpFile(hashed, "lease")
        var leased = false
        try {
            cancellationCheck()
            cacheDir.mkdirs()
            if (!tmp.createNewFile()) throw IOException("failed to create private cache lease")
            val revokedForAll =
                tmp.setReadable(false, false) &&
                    tmp.setWritable(false, false) &&
                    tmp.setExecutable(false, false)
            val grantedToOwner = tmp.setReadable(true, true) && tmp.setWritable(true, true)
            if (!revokedForAll || !grantedToOwner || tmp.canExecute()) {
                throw IOException("failed to restrict cache lease permissions")
            }
            // Leases are ephemeral; publication performs the durability fsync once,
            // after moving or copying the plaintext into its presentation directory.
            FileOutputStream(tmp).use { output ->
                readEncryptedTo(entry.file, hashed, output, cancellationCheck)
            }
            afterLeasePlaintextWritten()
            cancellationCheck()
            synchronized(this) {
                if (!cacheLifetime.isCurrent(generationAtLookup) || index[hashed] !== entry) return null
            }
            entry.file.setLastModified(System.currentTimeMillis())
            leased = true
            return DiskByteCacheLease(tmp)
        } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            if (error.isAuthenticationFailure() || error.isMalformedEnvelope()) {
                evictPoisonedEntry(hashed, entry, generationAtLookup)
            } else if (!entry.file.isFile) {
                dropMissingEntry(hashed, entry)
            }
            return null
        } catch (error: GeneralSecurityException) {
            if (error.isAuthenticationFailure()) {
                evictPoisonedEntry(hashed, entry, generationAtLookup)
            }
            return null
        } catch (_: ProviderException) {
            return null
        } catch (_: OutOfMemoryError) {
            // Cache materialization is optional. Preserve the encrypted envelope
            // and delete the partial plaintext lease in finally so the caller can
            // continue through its miss path.
            return null
        } finally {
            if (!leased) runCatching { tmp.delete() }
        }
    }

    /** Removes stale accounting only when [entry] is still the indexed generation. */
    private fun dropMissingEntry(
        hashed: String,
        entry: Entry,
    ) {
        synchronized(this) {
            if (index[hashed] === entry) {
                index.remove(hashed)
                residentBytes -= entry.size
            }
        }
    }

    /** Captures the wipe and expiry generations that a deferred publication must match. */
    @Synchronized
    fun capturePublicationToken(): DiskByteCachePublicationToken =
        DiskByteCachePublicationToken(
            generation = cacheLifetime.capture(),
            expirySweepEpoch = expirySweeps.capture(),
        )

    /** Checks both invalidation dimensions carried by a deferred publication. */
    private fun publicationTokenIsCurrent(token: DiskByteCachePublicationToken): Boolean =
        cacheLifetime.isCurrent(token.generation) &&
            expirySweeps.isCurrent(token.expirySweepEpoch)

    /** Encrypts and atomically publishes bounded plaintext under a captured invalidation token. */
    fun put(
        key: String,
        bytes: ByteArray,
        token: DiskByteCachePublicationToken,
        ciphertextTag: String? = null,
    ) {
        if (bytes.isEmpty()) return
        if (bytes.size.toLong() > entryByteLimit) return
        synchronized(this) {
            if (!publicationTokenIsCurrent(token)) return
        }
        afterPutEpochCaptured()
        ensureHydrated()
        val hashed = fileNameFor(key)
        val file = File(cacheDir, hashed)
        val putContext =
            synchronized(this) {
                if (!publicationTokenIsCurrent(token)) return
                cacheDir.mkdirs()
                val tmp = uniqueTmpFile(hashed.removeSuffix(SUFFIX), "enc")
                val output =
                    try {
                        FileOutputStream(tmp)
                    } catch (_: IOException) {
                        return
                    }
                PutContext(
                    tmp = tmp,
                    output = output,
                    existingSnapshot = index[hashed],
                    unresolvedAtStart = unresolvedEnvelopes.contains(hashed),
                )
            }
        afterTempFileCreated()
        // Atomic write: encrypt into a sibling `.tmp` envelope, then rename onto
        // the final path in one commit step so data and expiry metadata cannot
        // diverge across process death. See #1373.
        try {
            putContext.output.use { output ->
                writeEncrypted(output, hashed, bytes, ciphertextTag)
            }
        } catch (_: GeneralSecurityException) {
            abortPut(putContext.tmp)
            return
        } catch (_: ProviderException) {
            abortPut(putContext.tmp)
            return
        } catch (_: IOException) {
            abortPut(putContext.tmp)
            // Disk full / permission error. L1 still holds the bytes; this
            // entry just won't survive restart. Silent fail is acceptable.
            return
        }
        afterEncryptedWrite()
        val evicted = mutableListOf<Pair<String, List<File>>>()
        synchronized(this) {
            // A concurrent clear() (sign-out / account switch) or expiry sweep may
            // have run while we were writing — abort and drop temp artifacts
            // rather than re-persisting plaintext for a wiped session. See #154,
            // #1033.
            if (
                !publicationTokenIsCurrent(token) ||
                index[hashed] !== putContext.existingSnapshot ||
                unresolvedEnvelopes.contains(hashed) != putContext.unresolvedAtStart
            ) {
                abortPut(putContext.tmp)
                return
            }
            if (!renameFile(putContext.tmp, file)) {
                runCatching { putContext.tmp.delete() }
                return
            }
            // The rename has already atomically published one complete envelope.
            // A directory-sync failure means durability is uncertain, but deleting
            // the new file here would turn a complete old-or-new result into a
            // guaranteed cache miss and still require another directory sync.
            runCatching { syncCacheDirectory(cacheDir) }
            val existing = putContext.existingSnapshot
            if (existing != null) {
                index.remove(hashed)
                residentBytes -= existing.size
            }
            unresolvedEnvelopes.remove(hashed)
            val fileLength = file.length()
            if (fileLength <= 0L || fileLength > Int.MAX_VALUE) {
                runCatching { file.delete() }
                return
            }
            val size = fileLength.toInt()
            index[hashed] = Entry(file, size, bytes.size.toLong(), ciphertextTag)
            residentBytes += size
            evicted += evictedEntryFiles()
        }
        deleteStaleEntries(evicted)
        onMutation()
    }

    fun put(
        key: String,
        bytes: ByteArray,
    ) {
        put(key, bytes, capturePublicationToken())
    }

    /**
     * Drop a single entry — delete its backing file and index row. Used by the
     * disappearing-message sweep to evict an expired attachment's encrypted
     * bytes once the engine reports it secure-deleted, so it isn't recoverable
     * from the L2 cache after expiry. No-op if absent.
     */
    fun remove(key: String) {
        ensureHydrated()
        val hashed = fileNameFor(key)
        val stale =
            synchronized(this) {
                val entry = index.remove(hashed)
                if (entry != null) {
                    residentBytes -= entry.size
                    listOf(hashed to filesFor(entry))
                } else if (unresolvedEnvelopes.remove(hashed)) {
                    listOf(hashed to filesForUnresolved(hashed))
                } else {
                    return
                }
            }
        deleteStaleEntries(stale)
        onMutation()
    }

    /**
     * Evict every entry whose ciphertext tag is in [ciphertextTags] — deleting
     * its backing file and index row. Unlike [remove], this matches
     * by the persisted ciphertext hash rather than the cache key, so the
     * disappearing-message sweep can wipe expired attachments from disk even
     * when their message isn't currently loaded (and thus has no entry in the
     * in-memory hash→key reference map). Returns the number of entries removed.
     */
    fun removeByCiphertextTags(ciphertextTags: Set<String>): Int {
        if (ciphertextTags.isEmpty()) return 0
        ensureHydrated()
        val stale = mutableListOf<Pair<String, List<File>>>()
        val removed =
            synchronized(this) {
                expirySweeps.advance()
                var removed = 0
                val iterator = index.entries.iterator()
                while (iterator.hasNext()) {
                    val (hashed, entry) = iterator.next()
                    if (entry.tag != null && entry.tag in ciphertextTags) {
                        stale += hashed to filesFor(entry)
                        residentBytes -= entry.size
                        iterator.remove()
                        removed++
                    }
                }
                // Fail closed: an unresolved envelope's tag is unauthenticated, so
                // any non-empty sweep must delete all of them rather than risk leaving
                // an expired attachment readable after provider recovery.
                val unresolvedIterator = unresolvedEnvelopes.iterator()
                while (unresolvedIterator.hasNext()) {
                    val hashed = unresolvedIterator.next()
                    unresolvedIterator.remove()
                    stale += hashed to filesForUnresolved(hashed)
                    removed++
                }
                removed
            }
        deleteStaleEntries(stale)
        if (removed > 0) onMutation()
        return removed
    }

    /**
     * Delete each removed entry only while its key remains absent. The liveness
     * check and unlink share the monitor with put's rename, so a same-key re-put
     * cannot publish between them and have its fresh envelope unlinked.
     */
    private fun deleteStaleEntries(stale: List<Pair<String, List<File>>>) {
        if (stale.isEmpty()) return
        stale.forEach { (hashed, files) ->
            val absentAtProbe =
                synchronized(this) {
                    !index.containsKey(hashed)
                }
            if (!absentAtProbe) return@forEach
            beforeStaleFilesDeleted()
            synchronized(this) {
                if (!index.containsKey(hashed)) {
                    files.forEach { runCatching { it.delete() } }
                }
            }
        }
    }

    private fun filesFor(entry: Entry): List<File> = listOf(entry.file, legacyTagFileFor(entry.file, SUFFIX))

    private fun filesForUnresolved(hashed: String): List<File> {
        val file = File(cacheDir, hashed)
        return listOf(file, legacyTagFileFor(file, SUFFIX))
    }

    /** LRU-evict down to [maxBytes], returning each evicted entry keyed by its
     *  hashed name so the caller's deferred delete can skip a concurrent re-put
     *  (see [deleteStaleEntries]). */
    private fun evictedEntryFiles(): List<Pair<String, List<File>>> {
        if (residentBytes <= maxBytes) return emptyList()
        val evicted = mutableListOf<Pair<String, List<File>>>()
        val it = index.entries.iterator()
        while (it.hasNext() && residentBytes > maxBytes) {
            val (hashed, entry) = it.next()
            residentBytes -= entry.size
            it.remove()
            evicted += hashed to filesFor(entry)
        }
        return evicted
    }

    /** Wipes every cache surface and invalidates reads, writes, sweeps, and filename memoization. */
    @Synchronized
    fun clear() {
        // Bump first so any put scheduled against the prior generation is
        // rejected even if it grabs this lock right after the wipe. See #154.
        cacheLifetime.advance()
        // The memo retains raw cache-key strings, which can carry account and
        // blob identifiers — drop them with the rest of the account's data.
        // The epoch bump rejects re-inserts from lookups mid-hash right now.
        synchronized(fileNameMemo) {
            fileNameMemoLifetime.advance()
            fileNameMemo.clear()
        }
        // Hold the lock for the whole wipe. Deleting outside it (an earlier
        // #99 attempt) let a concurrent put() recreate a `.enc` that the orphan
        // sweep then removed — a race. clear() runs on sign-out/account-switch,
        // so briefly blocking get()/put() is fine, and it keeps the privacy
        // guarantee that ALL of this account's media (including orphan `.enc`s)
        // is wiped. The #99 win — get() not holding the lock across decryption —
        // is unaffected, since that's in get(), not here.
        index.values.forEach { entry ->
            runCatching { entry.file.delete() }
            runCatching { legacyTagFileFor(entry.file, SUFFIX).delete() }
        }
        index.clear()
        unresolvedEnvelopes.clear()
        residentBytes = 0L
        hydrated = true
        // Sweep directly instead of calling ensureHydrated(): sign-out can run
        // before the cache has ever been touched, and building the full index
        // just to delete it causes avoidable main-thread directory work.
        cacheDir
            .listFiles()
            ?.asSequence()
            ?.filter {
                it.isFile &&
                    (
                        it.name.endsWith(SUFFIX) ||
                            it.name.endsWith(LEGACY_SUFFIX) ||
                            it.name.endsWith(TMP_SUFFIX) ||
                            it.name.endsWith(TAG_SUFFIX)
                    )
            }?.forEach { runCatching { it.delete() } }
        onMutation()
    }

    fun size(): Int {
        ensureHydrated()
        return synchronized(this) { index.size }
    }

    fun residentBytes(): Long {
        ensureHydrated()
        return synchronized(this) { residentBytes }
    }

    private fun uniqueTmpFile(
        baseName: String,
        kind: String,
    ): File =
        File(
            cacheDir,
            "$baseName-$kind-${TMP_COUNTER.incrementAndGet()}-${System.nanoTime()}$TMP_SUFFIX",
        )

    private fun abortPut(tmp: File) {
        runCatching { tmp.delete() }
    }

    private fun wipeLegacyPlaintext(): Boolean {
        val files = cacheDir.listFiles()
        if (files == null) {
            val directoryAbsent = !cacheDir.exists()
            if (!directoryAbsent) android.util.Log.w("DiskByteCache", "failed to scan for legacy plaintext")
            return directoryAbsent
        }
        var complete = true
        for (file in files) {
            if (!file.isFile || !file.name.endsWith(LEGACY_SUFFIX)) continue
            val deleted = runCatching { file.delete() || !file.exists() }.getOrDefault(false)
            if (!deleted) {
                complete = false
                android.util.Log.w("DiskByteCache", "failed to delete legacy plaintext")
                continue
            }
            val encryptedPeer = File(file.parentFile, file.name.removeSuffix(LEGACY_SUFFIX) + SUFFIX)
            if (!encryptedPeer.isFile) runCatching { legacyTagFileFor(file, LEGACY_SUFFIX).delete() }
        }
        return complete
    }

    /** Deletes stale startup artifacts only while the observed cache generation remains current. */
    private fun deleteHydrationArtifactsIfGenerationMatches(
        generationAtStart: Long,
        files: List<File>,
        pauseBeforeDelete: () -> Unit = {},
    ) {
        pauseBeforeDelete()
        synchronized(this) {
            if (!cacheLifetime.isCurrent(generationAtStart)) return
            files.forEach { file ->
                runCatching { file.delete() }.onFailure {
                    android.util.Log.w("DiskByteCache", "failed to delete hydration artifact")
                }
            }
        }
    }

    /** Reconstructs authenticated index metadata without exposing plaintext during startup. */
    private fun buildHydratedIndex(generationAtStart: Long): HydratedIndex {
        val hydratedIndex = LinkedHashMap<String, Entry>(8, 0.75f, true)
        val unresolvedDuringHydration = mutableSetOf<String>()
        var hydratedBytes = 0L
        val allFiles = cacheDir.listFiles()?.filter { it.isFile } ?: return HydratedIndex(hydratedIndex, hydratedBytes, unresolvedDuringHydration)
        beforeOrphanTmpSweep()
        cacheDir
            .listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.name.endsWith(TMP_SUFFIX) }
            ?.forEach { file ->
                deleteHydrationArtifactsIfGenerationMatches(generationAtStart, listOf(file))
            }
        afterOrphanTmpSweep()
        val files =
            allFiles
                .filter { it.name.endsWith(SUFFIX) }
                .sortedBy { it.lastModified() }
        for (file in files) {
            val size = file.length()
            val envelopeRead = readAuthenticatedEnvelopeHeader(file)
            when (envelopeRead.outcome) {
                EnvelopeHeaderOutcome.VALID -> {
                    val envelope = envelopeRead.header ?: continue
                    val plaintextSize =
                        envelope.plaintextBytes
                            ?: (size - envelope.headerBytes - ENVELOPE_CRYPTO_OVERHEAD_BYTES)
                    if (isInvalidEnvelopeSize(envelope, size, plaintextSize)) {
                        deleteHydrationArtifactsIfGenerationMatches(
                            generationAtStart,
                            listOf(file, legacyTagFileFor(file, SUFFIX)),
                            beforeHydrationDestructiveDelete,
                        )
                        continue
                    }
                    hydratedIndex[file.name] = Entry(file, size.toInt(), plaintextSize, envelope.ciphertextTag)
                    hydratedBytes += size
                }
                EnvelopeHeaderOutcome.CORRUPT -> {
                    deleteHydrationArtifactsIfGenerationMatches(
                        generationAtStart,
                        listOf(file, legacyTagFileFor(file, SUFFIX)),
                        beforeHydrationDestructiveDelete,
                    )
                }
                EnvelopeHeaderOutcome.TRANSIENT_FAILURE -> {
                    unresolvedDuringHydration.add(file.name)
                }
            }
        }
        // Hot-trim if total resident exceeds cap (e.g., cap was reduced
        // since the previous run; or disk filled out-of-band).
        val iterator = hydratedIndex.entries.iterator()
        while (iterator.hasNext() && hydratedBytes > maxBytes) {
            val (_, entry) = iterator.next()
            deleteHydrationArtifactsIfGenerationMatches(
                generationAtStart,
                listOf(entry.file, legacyTagFileFor(entry.file, SUFFIX)),
                beforeHydrationDestructiveDelete,
            )
            hydratedBytes -= entry.size
            iterator.remove()
        }
        // The envelope is authoritative now; every legacy `.tag` is stale.
        allFiles
            .filter { it.name.endsWith(TAG_SUFFIX) }
            .forEach { tagFile ->
                deleteHydrationArtifactsIfGenerationMatches(
                    generationAtStart,
                    listOf(tagFile),
                    beforeHydrationDestructiveDelete,
                )
            }
        return HydratedIndex(hydratedIndex, hydratedBytes, unresolvedDuringHydration)
    }

    /** Computes the exact authenticated envelope length for the declared plaintext size. */
    private fun expectedEnvelopeBytes(
        envelope: EnvelopeHeader,
        plaintextBytes: Long,
    ): Long =
        if (envelope.version == CHUNKED_ENVELOPE_VERSION) {
            val chunkCount = (plaintextBytes + ENCRYPTION_CHUNK_BYTES - 1L) / ENCRYPTION_CHUNK_BYTES
            envelope.headerBytes + METADATA_AUTH_BYTES + plaintextBytes + chunkCount * PAYLOAD_CRYPTO_OVERHEAD_BYTES
        } else {
            envelope.headerBytes + ENVELOPE_CRYPTO_OVERHEAD_BYTES + plaintextBytes
        }

    /** Rejects envelopes whose declaration and physical size cannot describe the same payload. */
    private fun isInvalidEnvelopeSize(
        envelope: EnvelopeHeader,
        fileBytes: Long,
        plaintextBytes: Long,
    ): Boolean =
        fileBytes > Int.MAX_VALUE ||
            plaintextBytes !in 1L..entryByteLimit ||
            fileBytes != expectedEnvelopeBytes(envelope, plaintextBytes)

    /** Locates a metadata sidecar left by the pre-#1373 two-file cache format. */
    private fun legacyTagFileFor(
        dataFile: File,
        dataSuffix: String,
    ): File = File(dataFile.parentFile, dataFile.name.removeSuffix(dataSuffix) + TAG_SUFFIX)

    private data class EnvelopeHeader(
        val ciphertextTag: String?,
        val bytes: ByteArray,
        val version: Byte,
        val plaintextBytes: Long?,
    ) {
        val headerBytes: Int
            get() = bytes.size
    }

    /** Encodes a versioned header, choosing chunk authentication for large plaintext. */
    private fun envelopeHeaderBytes(
        ciphertextTag: String?,
        plaintextBytes: Int,
    ): ByteArray {
        val tagBytes = ciphertextTag?.toByteArray(Charsets.UTF_8) ?: byteArrayOf()
        require(tagBytes.size <= MAX_TAG_BYTES) { "ciphertext tag exceeds $MAX_TAG_BYTES bytes" }
        val version =
            if (plaintextBytes > LEGACY_SINGLE_PAYLOAD_MAX_BYTES) {
                CHUNKED_ENVELOPE_VERSION
            } else {
                LEGACY_ENVELOPE_VERSION
            }
        val plaintextLengthBytes = if (version == CHUNKED_ENVELOPE_VERSION) Long.SIZE_BYTES else 0
        val header = ByteArray(FIXED_ENVELOPE_HEADER_BYTES + plaintextLengthBytes + tagBytes.size)
        System.arraycopy(ENVELOPE_MAGIC, 0, header, 0, ENVELOPE_MAGIC.size)
        header[ENVELOPE_MAGIC.size] = version
        header[ENVELOPE_MAGIC.size + 1] = tagBytes.size.toByte()
        if (version == CHUNKED_ENVELOPE_VERSION) {
            ByteBuffer.wrap(header, FIXED_ENVELOPE_HEADER_BYTES, Long.SIZE_BYTES).putLong(plaintextBytes.toLong())
        }
        if (tagBytes.isNotEmpty()) {
            System.arraycopy(tagBytes, 0, header, FIXED_ENVELOPE_HEADER_BYTES + plaintextLengthBytes, tagBytes.size)
        }
        return header
    }

    private enum class EnvelopeHeaderOutcome {
        VALID,
        CORRUPT,
        TRANSIENT_FAILURE,
    }

    private data class EnvelopeHeaderRead(
        val header: EnvelopeHeader?,
        val outcome: EnvelopeHeaderOutcome,
    )

    /** Classifies an envelope header as authenticated, corrupt, or transiently unreadable. */
    private fun readAuthenticatedEnvelopeHeader(file: File): EnvelopeHeaderRead {
        if (file.length() < MIN_ENVELOPE_BYTES) {
            return EnvelopeHeaderRead(null, EnvelopeHeaderOutcome.CORRUPT)
        }
        return try {
            FileInputStream(file).use { input ->
                EnvelopeHeaderRead(
                    readAndAuthenticateMetadata(input, file.name).envelope,
                    EnvelopeHeaderOutcome.VALID,
                )
            }
        } catch (error: IOException) {
            EnvelopeHeaderRead(
                null,
                if (error.isAuthenticationFailure() || error.isMalformedEnvelope()) {
                    EnvelopeHeaderOutcome.CORRUPT
                } else {
                    EnvelopeHeaderOutcome.TRANSIENT_FAILURE
                },
            )
        } catch (error: GeneralSecurityException) {
            EnvelopeHeaderRead(
                null,
                if (error.isAuthenticationFailure()) {
                    EnvelopeHeaderOutcome.CORRUPT
                } else {
                    EnvelopeHeaderOutcome.TRANSIENT_FAILURE
                },
            )
        } catch (_: ProviderException) {
            EnvelopeHeaderRead(null, EnvelopeHeaderOutcome.TRANSIENT_FAILURE)
        }
    }

    /** Parses and bounds the versioned header before any ciphertext allocation. */
    @Throws(IOException::class)
    private fun readEnvelopeHeader(input: InputStream): EnvelopeHeader {
        val fixedHeader = input.readExactly(FIXED_ENVELOPE_HEADER_BYTES)
        val version = fixedHeader[ENVELOPE_MAGIC.size]
        validateEnvelopePrefix(fixedHeader, version)
        val tagLength = fixedHeader[ENVELOPE_MAGIC.size + 1].toInt() and 0xFF
        val plaintextLengthBytes =
            if (version == CHUNKED_ENVELOPE_VERSION) {
                input.readExactly(Long.SIZE_BYTES)
            } else {
                byteArrayOf()
            }
        val plaintextBytes = plaintextLengthBytes.takeIf { it.isNotEmpty() }?.let { ByteBuffer.wrap(it).long }
        validateDeclaredPlaintextBytes(plaintextBytes)
        val tagBytes = if (tagLength == 0) byteArrayOf() else input.readExactly(tagLength)
        val header = fixedHeader + plaintextLengthBytes + tagBytes
        return EnvelopeHeader(
            ciphertextTag = tagBytes.toString(Charsets.UTF_8).takeIf { it.isNotBlank() },
            bytes = header,
            version = version,
            plaintextBytes = plaintextBytes,
        )
    }

    /** Validates the cache magic and supported envelope version before parsing metadata. */
    private fun validateEnvelopePrefix(
        fixedHeader: ByteArray,
        version: Byte,
    ) {
        if (!fixedHeader.copyOfRange(0, ENVELOPE_MAGIC.size).contentEquals(ENVELOPE_MAGIC)) {
            throw IOException("invalid cache envelope magic")
        }
        if (version != LEGACY_ENVELOPE_VERSION && version != CHUNKED_ENVELOPE_VERSION) {
            throw IOException("unsupported cache envelope version")
        }
    }

    /** Rejects authenticated size declarations outside the configured entry bound. */
    private fun validateDeclaredPlaintextBytes(plaintextBytes: Long?) {
        if (plaintextBytes != null && plaintextBytes !in 1L..entryByteLimit) {
            throw IOException("decrypted cache payload exceeds entry limit")
        }
    }

    @Throws(GeneralSecurityException::class, IOException::class)
    private fun readAndAuthenticateMetadata(
        input: InputStream,
        fileName: String,
    ): AuthenticatedMetadata {
        val envelope = readEnvelopeHeader(input)
        val metadataIv = input.readExactly(IV_BYTES)
        val metadataAuthTag = input.readExactly(GCM_TAG_BYTES)
        verifyMetadataAuth(metadataIv, metadataAuthTag, buildMetadataAad(fileName, envelope.bytes))
        return AuthenticatedMetadata(envelope, metadataIv, metadataAuthTag)
    }

    @Throws(GeneralSecurityException::class, IOException::class)
    private fun verifyMetadataAuth(
        metadataIv: ByteArray,
        metadataAuthTag: ByteArray,
        metadataAad: ByteArray,
    ) {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keyProvider.getOrCreate(), GCMParameterSpec(TAG_BITS, metadataIv))
        cipher.updateAAD(metadataAad)
        cipher.doFinal(metadataAuthTag)
    }

    private fun buildMetadataAad(
        fileName: String,
        header: ByteArray,
    ): ByteArray = METADATA_AAD_DOMAIN + fileName.toByteArray(Charsets.UTF_8) + header

    /** Binds payload ciphertext to its filename, header, and authenticated metadata record. */
    private fun buildPayloadAad(
        fileName: String,
        header: ByteArray,
        metadataIv: ByteArray,
        metadataAuthTag: ByteArray,
    ): ByteArray =
        PAYLOAD_AAD_DOMAIN +
            fileName.toByteArray(Charsets.UTF_8) +
            header +
            metadataIv +
            metadataAuthTag

    /** Writes and durably syncs one metadata-bound authenticated envelope. */
    @Throws(GeneralSecurityException::class, IOException::class)
    private fun writeEncrypted(
        output: FileOutputStream,
        fileName: String,
        plaintext: ByteArray,
        ciphertextTag: String?,
    ) {
        val header = envelopeHeaderBytes(ciphertextTag, plaintext.size)
        val metadataAad = buildMetadataAad(fileName, header)
        val metadataCipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        metadataCipher.init(Cipher.ENCRYPT_MODE, keyProvider.getOrCreate())
        val metadataIv = metadataCipher.iv
        if (metadataIv.size != IV_BYTES) {
            throw GeneralSecurityException("AES-GCM provider returned a ${metadataIv.size}-byte metadata IV")
        }
        metadataCipher.updateAAD(metadataAad)
        val metadataAuthTag = metadataCipher.doFinal()
        if (metadataAuthTag.size != GCM_TAG_BYTES) {
            throw GeneralSecurityException("AES-GCM provider returned a ${metadataAuthTag.size}-byte metadata tag")
        }

        output.write(header)
        output.write(metadataIv)
        output.write(metadataAuthTag)
        val payloadAad = buildPayloadAad(fileName, header, metadataIv, metadataAuthTag)
        if (header[ENVELOPE_MAGIC.size] == CHUNKED_ENVELOPE_VERSION) {
            writeChunkedPayload(output, plaintext, payloadAad)
        } else {
            writeLegacyPayload(output, plaintext, payloadAad)
        }
        // Make the complete envelope durable before the single publication
        // rename. If the rename itself is lost in a power failure, recovery
        // sees the old envelope; if it lands, the new envelope is complete.
        output.fd.sync()
    }

    /** Encrypts a small payload as one legacy-compatible authenticated message. */
    private fun writeLegacyPayload(
        output: FileOutputStream,
        plaintext: ByteArray,
        payloadAad: ByteArray,
    ) {
        val cipher = newPayloadCipher(Cipher.ENCRYPT_MODE, payloadAad)
        output.write(cipher.iv)
        output.write(cipher.doFinal(plaintext))
    }

    /** Encrypts a large payload in independently authenticated, bounded-size chunks. */
    private fun writeChunkedPayload(
        output: FileOutputStream,
        plaintext: ByteArray,
        payloadAad: ByteArray,
    ) {
        var offset = 0
        var chunkIndex = 0
        while (offset < plaintext.size) {
            val chunkBytes = minOf(ENCRYPTION_CHUNK_BYTES, plaintext.size - offset)
            val cipher = newPayloadCipher(Cipher.ENCRYPT_MODE, buildChunkAad(payloadAad, chunkIndex, chunkBytes))
            output.write(cipher.iv)
            output.write(cipher.doFinal(plaintext, offset, chunkBytes))
            offset += chunkBytes
            chunkIndex += 1
        }
    }

    /** Creates a payload cipher bound to the envelope metadata and optional supplied IV. */
    private fun newPayloadCipher(
        mode: Int,
        aad: ByteArray,
        iv: ByteArray? = null,
    ): Cipher =
        Cipher.getInstance(CIPHER_TRANSFORMATION).also { cipher ->
            if (iv == null) {
                cipher.init(mode, keyProvider.getOrCreate())
                if (cipher.iv.size != IV_BYTES) {
                    throw GeneralSecurityException("AES-GCM provider returned a ${cipher.iv.size}-byte payload IV")
                }
            } else {
                cipher.init(mode, keyProvider.getOrCreate(), GCMParameterSpec(TAG_BITS, iv))
            }
            cipher.updateAAD(aad)
        }

    /** Binds each chunk's index and plaintext length into its authentication domain. */
    private fun buildChunkAad(
        payloadAad: ByteArray,
        chunkIndex: Int,
        plaintextBytes: Int,
    ): ByteArray =
        payloadAad +
            ByteBuffer
                .allocate(Int.SIZE_BYTES * 2)
                .putInt(chunkIndex)
                .putInt(plaintextBytes)
                .array()

    /** Authenticates one envelope into a bounded byte array for byte-oriented callers. */
    @Throws(GeneralSecurityException::class, IOException::class)
    private fun readEncrypted(
        file: File,
        fileName: String,
    ): ByteArray =
        FileInputStream(file).use { input ->
            val authenticated = readAndAuthenticateMetadata(input, fileName)
            val payloadAad =
                buildPayloadAad(
                    fileName,
                    authenticated.envelope.bytes,
                    authenticated.metadataIv,
                    authenticated.metadataAuthTag,
                )
            when (authenticated.envelope.version) {
                CHUNKED_ENVELOPE_VERSION ->
                    readChunkedPayload(input, authenticated.envelope.plaintextBytes!!, payloadAad)
                else -> readLegacyPayload(input, file.length(), authenticated.envelope.headerBytes, payloadAad)
            }
        }

    /** Authenticates an envelope and streams its plaintext into an owner-private file. */
    @Throws(GeneralSecurityException::class, IOException::class)
    private fun readEncryptedTo(
        file: File,
        fileName: String,
        output: FileOutputStream,
        cancellationCheck: () -> Unit,
    ) {
        FileInputStream(file).use { input ->
            val authenticated = readAndAuthenticateMetadata(input, fileName)
            val payloadAad =
                buildPayloadAad(
                    fileName,
                    authenticated.envelope.bytes,
                    authenticated.metadataIv,
                    authenticated.metadataAuthTag,
                )
            when (authenticated.envelope.version) {
                CHUNKED_ENVELOPE_VERSION ->
                    readChunkedPayloadTo(
                        input,
                        output,
                        authenticated.envelope.plaintextBytes!!,
                        payloadAad,
                        cancellationCheck,
                    )
                else ->
                    readLegacyPayloadTo(
                        input,
                        output,
                        file.length(),
                        authenticated.envelope.headerBytes,
                        payloadAad,
                        cancellationCheck,
                    )
            }
        }
    }

    /** Verifies and streams every large-entry chunk while honoring cancellation boundaries. */
    private fun readChunkedPayloadTo(
        input: InputStream,
        output: FileOutputStream,
        plaintextBytes: Long,
        payloadAad: ByteArray,
        cancellationCheck: () -> Unit,
    ) {
        var written = 0L
        var chunkIndex = 0
        while (written < plaintextBytes) {
            cancellationCheck()
            val chunkBytes = minOf(ENCRYPTION_CHUNK_BYTES.toLong(), plaintextBytes - written).toInt()
            val payloadIv = input.readExactly(IV_BYTES)
            val ciphertext = input.readExactly(chunkBytes + GCM_TAG_BYTES)
            val decrypted =
                newPayloadCipher(
                    Cipher.DECRYPT_MODE,
                    buildChunkAad(payloadAad, chunkIndex, chunkBytes),
                    payloadIv,
                ).doFinal(ciphertext)
            if (decrypted.size != chunkBytes) throw IOException("invalid decrypted cache chunk length")
            output.write(decrypted)
            written += decrypted.size
            chunkIndex += 1
        }
        requirePayloadExhausted(input)
        if (written != plaintextBytes) throw IOException("invalid decrypted cache payload length")
    }

    /** Authenticates a legacy payload before publishing it into the lease output. */
    private fun readLegacyPayloadTo(
        input: InputStream,
        output: FileOutputStream,
        fileBytes: Long,
        headerBytes: Int,
        payloadAad: ByteArray,
        cancellationCheck: () -> Unit,
    ) {
        val ciphertextBytes = fileBytes - headerBytes - METADATA_AUTH_BYTES - IV_BYTES
        val plaintextBytes = validateLegacyPayloadSize(ciphertextBytes)
        ensurePlaintextAllocationFits(Math.addExact(ciphertextBytes, plaintextBytes))
        val payloadIv = input.readExactly(IV_BYTES)
        cancellationCheck()
        val ciphertext = input.readExactly(ciphertextBytes.toInt())
        requirePayloadExhausted(input)
        cancellationCheck()
        val plaintext = newPayloadCipher(Cipher.DECRYPT_MODE, payloadAad, payloadIv).doFinal(ciphertext)
        validatePlaintext(plaintext)
        if (plaintext.size.toLong() != plaintextBytes) throw IOException("invalid decrypted cache payload length")
        output.write(plaintext)
    }

    /** Authenticates a bounded legacy payload into a caller-owned byte array. */
    private fun readLegacyPayload(
        input: InputStream,
        fileBytes: Long,
        headerBytes: Int,
        payloadAad: ByteArray,
    ): ByteArray {
        val ciphertextBytes = fileBytes - headerBytes - METADATA_AUTH_BYTES - IV_BYTES
        val plaintextBytes = validateLegacyPayloadSize(ciphertextBytes)
        ensurePlaintextAllocationFits(Math.addExact(ciphertextBytes, plaintextBytes))
        val payloadIv = input.readExactly(IV_BYTES)
        val ciphertext = input.readExactly(ciphertextBytes.toInt())
        requirePayloadExhausted(input)
        return newPayloadCipher(Cipher.DECRYPT_MODE, payloadAad, payloadIv)
            .doFinal(ciphertext)
            .also(::validatePlaintext)
    }

    /** Derives and validates the plaintext length of a single-message legacy payload. */
    private fun validateLegacyPayloadSize(ciphertextBytes: Long): Long {
        if (ciphertextBytes < GCM_TAG_BYTES) throw IOException("truncated encrypted cache payload")
        val plaintextBytes = ciphertextBytes - GCM_TAG_BYTES
        if (plaintextBytes > minOf(LEGACY_SINGLE_PAYLOAD_MAX_BYTES.toLong(), entryByteLimit)) {
            throw IOException("legacy encrypted cache payload exceeds safe limit")
        }
        return plaintextBytes
    }

    /** Authenticates a chunked entry into one bounded byte array for legacy callers. */
    private fun readChunkedPayload(
        input: InputStream,
        plaintextBytes: Long,
        payloadAad: ByteArray,
    ): ByteArray {
        ensurePlaintextAllocationFits(plaintextBytes)
        val plaintext = plaintextAllocator(plaintextBytes.toInt())
        var offset = 0
        var chunkIndex = 0
        while (offset < plaintext.size) {
            val chunkBytes = minOf(ENCRYPTION_CHUNK_BYTES, plaintext.size - offset)
            val payloadIv = input.readExactly(IV_BYTES)
            val ciphertext = input.readExactly(chunkBytes + GCM_TAG_BYTES)
            val decrypted =
                newPayloadCipher(
                    Cipher.DECRYPT_MODE,
                    buildChunkAad(payloadAad, chunkIndex, chunkBytes),
                    payloadIv,
                ).doFinal(ciphertext)
            if (decrypted.size != chunkBytes) throw IOException("invalid decrypted cache chunk length")
            System.arraycopy(decrypted, 0, plaintext, offset, chunkBytes)
            offset += chunkBytes
            chunkIndex += 1
        }
        requirePayloadExhausted(input)
        validatePlaintext(plaintext)
        return plaintext
    }

    /** Rejects trailing unauthenticated or undeclared bytes after the expected payload. */
    private fun requirePayloadExhausted(input: InputStream) {
        if (input.read() >= 0) throw IOException("encrypted cache payload exceeds entry limit")
    }

    /** Enforces entry and live-heap budgets before allocating plaintext storage. */
    private fun ensurePlaintextAllocationFits(plaintextBytes: Long) {
        val allocationLimit = minOf(entryByteLimit, Int.MAX_VALUE.toLong())
        if (plaintextBytes !in 1L..allocationLimit) {
            throw IOException("decrypted cache payload exceeds entry limit")
        }
        if (plaintextBytes > availablePlaintextAllocationBytes()) {
            throw IOException("decrypted cache payload exceeds memory budget")
        }
    }

    /** Enforces non-empty, bounded plaintext after authenticated decryption. */
    private fun validatePlaintext(plaintext: ByteArray) {
        if (plaintext.isEmpty()) throw IOException("empty decrypted cache payload")
        if (plaintext.size.toLong() > entryByteLimit) throw IOException("decrypted cache payload exceeds entry limit")
    }

    @Throws(IOException::class)
    private fun InputStream.readExactly(size: Int): ByteArray {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = read(bytes, offset, size - offset)
            if (read < 0) throw IOException("truncated encrypted cache entry")
            offset += read
        }
        return bytes
    }

    private fun Throwable.isAuthenticationFailure(): Boolean = generateSequence<Throwable>(this) { it.cause }.any { it is AEADBadTagException }

    /** Detects an authentication failure through wrapped I/O causes. */
    private fun IOException.isAuthenticationFailure(): Boolean = (this as Throwable).isAuthenticationFailure()

    /** Identifies structural failures that make an encrypted envelope unsafe to retain. */
    private fun IOException.isMalformedEnvelope(): Boolean =
        message == "invalid cache envelope magic" ||
            message == "unsupported cache envelope version" ||
            message == "truncated encrypted cache entry" ||
            message == "truncated encrypted cache payload" ||
            message == "empty decrypted cache payload" ||
            message == "decrypted cache payload exceeds entry limit" ||
            message == "invalid decrypted cache chunk length" ||
            message == "encrypted cache payload exceeds entry limit"

    /** Removes a corrupt envelope only if it is still the entry that failed authentication. */
    private fun evictPoisonedEntry(
        fileName: String,
        entry: Entry,
        generationAtLookup: Long,
    ) {
        synchronized(this) {
            if (!cacheLifetime.isCurrent(generationAtLookup) || index[fileName] !== entry) return
            if (entry.file.exists() && !entry.file.delete()) return
            runCatching { legacyTagFileFor(entry.file, SUFFIX).delete() }
            index.remove(fileName)
            residentBytes -= entry.size
        }
    }

    // contains() runs inside composition for every media tile, so repeated
    // lookups of the same key must not re-allocate a digest and re-hash on
    // the main thread during fast scrolls. Access-ordered and bounded. The
    // epoch (guarded by the memo's monitor) makes clear() authoritative: a
    // lookup that was hashing while clear() ran must not re-insert its key,
    // or account/blob identifiers would stay reachable after a wipe.
    private val fileNameMemo = LinkedHashMap<String, String>(16, 0.75f, true)
    private val fileNameMemoLifetime = StalenessGuard()

    /** Reports memo occupancy for bounded-key-retention regression tests. */
    internal fun memoizedFileNameKeyCount(): Int = synchronized(fileNameMemo) { fileNameMemo.size }

    /** Hashes privacy-sensitive logical keys into bounded, memoized disk filenames. */
    private fun fileNameFor(key: String): String {
        val epochAtLookup =
            synchronized(fileNameMemo) {
                fileNameMemo[key]?.let { return it }
                fileNameMemoLifetime.capture()
            }
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(key.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(HEX[(b.toInt() ushr 4) and 0x0F])
            sb.append(HEX[b.toInt() and 0x0F])
        }
        sb.append(SUFFIX)
        val fileName = sb.toString()
        afterFileNameHashed()
        synchronized(fileNameMemo) {
            if (fileNameMemoLifetime.isCurrent(epochAtLookup)) {
                fileNameMemo[key] = fileName
                if (fileNameMemo.size > FILE_NAME_MEMO_MAX_KEYS) {
                    val eldest = fileNameMemo.keys.iterator()
                    eldest.next()
                    eldest.remove()
                }
            }
        }
        return fileName
    }

    private data class AuthenticatedMetadata(
        val envelope: EnvelopeHeader,
        val metadataIv: ByteArray,
        val metadataAuthTag: ByteArray,
    )

    private data class PutContext(
        val tmp: File,
        val output: FileOutputStream,
        val existingSnapshot: Entry?,
        val unresolvedAtStart: Boolean,
    )

    private data class Entry(
        val file: File,
        val size: Int,
        val plaintextSize: Long,
        val tag: String? = null,
    )

    private data class HydratedIndex(
        val index: LinkedHashMap<String, Entry>,
        val residentBytes: Long,
        val unresolvedEnvelopes: Set<String>,
    )

    private companion object {
        const val SUFFIX = ".enc"
        const val LEGACY_SUFFIX = ".bin"
        const val TMP_SUFFIX = ".tmp"
        const val TAG_SUFFIX = ".tag"
        const val LEGACY_ENVELOPE_VERSION: Byte = 2
        const val CHUNKED_ENVELOPE_VERSION: Byte = 3
        const val MAX_TAG_BYTES = 255
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val GCM_TAG_BYTES = 16
        const val TAG_BITS = GCM_TAG_BYTES * Byte.SIZE_BITS
        const val METADATA_AUTH_BYTES = IV_BYTES + GCM_TAG_BYTES
        const val PAYLOAD_CRYPTO_OVERHEAD_BYTES = IV_BYTES + GCM_TAG_BYTES
        const val ENVELOPE_CRYPTO_OVERHEAD_BYTES = METADATA_AUTH_BYTES + PAYLOAD_CRYPTO_OVERHEAD_BYTES
        const val FIXED_ENVELOPE_HEADER_BYTES = 6
        const val MIN_ENVELOPE_BYTES = FIXED_ENVELOPE_HEADER_BYTES + ENVELOPE_CRYPTO_OVERHEAD_BYTES + 1L

        // Large entries use independently authenticated chunks. This keeps
        // read-time crypto scratch bounded without paying thousands of
        // provider initializations for a supported 64 MiB attachment.
        const val ENCRYPTION_CHUNK_BYTES = 256 * 1024
        const val LEGACY_SINGLE_PAYLOAD_MAX_BYTES = 1024 * 1024
        const val DEFAULT_MAX_ENTRY_BYTES: Long = 16L * 1024L * 1024L
        const val FILE_NAME_MEMO_MAX_KEYS = 512
        val ENVELOPE_MAGIC = byteArrayOf('W'.code.toByte(), 'N'.code.toByte(), 'D'.code.toByte(), 'C'.code.toByte())
        val METADATA_AAD_DOMAIN = "WN-DC-META".toByteArray(Charsets.UTF_8)
        val PAYLOAD_AAD_DOMAIN = "WN-DC-PAY".toByteArray(Charsets.UTF_8)
        val TMP_COUNTER = AtomicLong()

        /** Reports allocatable plaintext bytes after retaining process safety headroom. */
        fun defaultAvailablePlaintextAllocationBytes(): Long {
            val runtime = Runtime.getRuntime()
            val available = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()
            return plaintextAllocationBudget(available, runtime.maxMemory())
        }

        val HEX =
            charArrayOf(
                '0',
                '1',
                '2',
                '3',
                '4',
                '5',
                '6',
                '7',
                '8',
                '9',
                'a',
                'b',
                'c',
                'd',
                'e',
                'f',
            )
    }
}
