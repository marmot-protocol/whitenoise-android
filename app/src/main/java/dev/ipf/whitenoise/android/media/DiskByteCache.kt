package dev.ipf.whitenoise.android.media

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

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
 * which Android does not back up to cloud by default. Each entry's filename
 * is `sha256(key).bin`; the original key is not recoverable from disk (no
 * stable account/group/messageId leak via `ls`).
 *
 * An entry may carry an optional `.tag` sidecar holding the attachment's
 * ciphertext SHA-256. It exists so the disappearing-message sweep can evict an
 * expired attachment by ciphertext hash even when its message isn't currently
 * loaded (the in-memory hash→key map only covers loaded rows). The tag is the
 * ciphertext hash — not the cache key — so the "key not recoverable from disk"
 * guarantee is preserved.
 *
 * Index state is synchronized on `this`, but expensive read/write/hydration
 * disk I/O is kept outside that monitor so main-thread probes don't block on
 * background cache work. `clear()` is the exception: sign-out/account-switch
 * wipes intentionally hold the monitor to preserve the privacy guarantee.
 *
 * Eviction is LRU by access order via `LinkedHashMap(accessOrder=true)`.
 * On `init`, the directory is scanned and the in-memory index is
 * repopulated using file `lastModified` as the proxy for recency.
 */
class DiskByteCache(
    private val cacheDir: File,
    private val maxBytes: Long,
    maxEntryBytes: Long = DEFAULT_MAX_ENTRY_BYTES,
) {
    // accessOrder = true → LinkedHashMap iterates in LRU order for eviction.
    private val index = LinkedHashMap<String, Entry>(8, 0.75f, true)
    private var residentBytes: Long = 0L
    private var hydrated = false
    private val hydrationLock = Any()

    // Bumped on every clear(). A deferred put() captures this at schedule time
    // and is rejected if a wipe intervened, so decrypted plaintext from a
    // signed-out session can't be re-persisted after sign-out. See #154.
    private var generation = 0
    private val entryByteLimit = minOf(maxBytes, maxEntryBytes).coerceAtLeast(1L)

    // No directory I/O in the constructor: the scan + per-file stat are
    // deferred to the first cache operation so they don't run on the main
    // thread at app launch (the cache is constructed eagerly as an AppState
    // field). First access happens on Dispatchers.IO. See #100.
    private fun ensureHydrated() {
        if (synchronized(this) { hydrated }) return
        synchronized(hydrationLock) {
            val generationAtStart =
                synchronized(this) {
                    if (hydrated) null else generation
                } ?: return
            cacheDir.mkdirs()
            val snapshot = buildHydratedIndex()
            synchronized(this) install@{
                if (hydrated || generation != generationAtStart) return@install
                index.clear()
                index.putAll(snapshot.index)
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

    fun get(key: String): ByteArray? {
        val hashed = fileNameFor(key)
        // Look up (and LRU-promote) the entry under the lock, then read the
        // file OUTSIDE it. Holding the monitor across readBytes() serialized
        // every concurrent media load and blocked clear() for the duration of
        // disk I/O. See #99.
        ensureHydrated()
        val (entry, generationAtLookup) =
            synchronized(this) {
                (index[hashed] ?: return null) to generation
            }
        return try {
            val bytes = entry.file.readBytes()
            synchronized(this) {
                // A concurrent clear() (sign-out / account switch) bumps
                // `generation` and deletes files under this lock, but an
                // already-open read still succeeds after the unlink on POSIX —
                // so re-check before handing back plaintext for a session that
                // was wiped mid-read. Mirrors put()'s write-side guard (#154).
                // See #376.
                if (generation != generationAtLookup || index[hashed] !== entry) return null
            }
            // The post-restart LRU rebuild uses file lastModified as the recency
            // proxy, so a read must touch it or frequently-read entries look stale
            // and get evicted first after a restart. Best-effort, and deliberately
            // outside the monitor because setLastModified is blocking disk I/O.
            entry.file.setLastModified(System.currentTimeMillis())
            bytes
        } catch (_: IOException) {
            // File vanished (manual delete, OS cache reap, FS corruption).
            // Drop the index entry and report miss; the caller will re-fetch.
            synchronized(this) {
                // Only evict if a concurrent put() hasn't already replaced it.
                if (index[hashed] === entry) {
                    index.remove(hashed)
                    residentBytes -= entry.size
                }
            }
            // Deliberately do NOT unlink the `.tag` here: a concurrent put()
            // could recreate this key's `.bin`+`.tag` between the lock release
            // and the delete, and removing the fresh tag would strand its
            // plaintext past expiry. If the `.bin` truly vanished, the orphaned
            // `.tag` is swept on the next rehydrate.
            null
        }
    }

    /** Wipe generation to capture when scheduling a deferred [put]. */
    @Synchronized
    fun generation(): Int = generation

    fun put(
        key: String,
        bytes: ByteArray,
        expectedGeneration: Int,
        ciphertextTag: String? = null,
    ) {
        if (bytes.isEmpty()) return
        if (bytes.size.toLong() > entryByteLimit) return
        // Reject a write whose session was wiped while it sat queued: clear()
        // bumps `generation` under this same lock, so a put scheduled before
        // the wipe skips here and no plaintext lands after sign-out. See #154.
        synchronized(this) {
            if (expectedGeneration != generation) return
        }
        // Hydrate before writing `.tmp` files so rehydrate's orphan sweep
        // doesn't delete in-flight temps on first access. Hydration itself may
        // scan the directory and read `.tag` files, so keep it off `this` to
        // avoid blocking main-thread contains() probes during scroll.
        ensureHydrated()
        synchronized(this) {
            if (expectedGeneration != generation) return
        }
        cacheDir.mkdirs()
        val hashed = fileNameFor(key)
        val file = File(cacheDir, hashed)
        // Unique `.tmp` names so concurrent puts for the same key (possible while
        // this thread is outside the monitor) don't clobber each other.
        val tmp = uniqueTmpFile(hashed.removeSuffix(SUFFIX), "bin")
        // Atomic write: write to a sibling `.tmp` file then rename onto the
        // final path. A power loss or kill mid-`writeBytes` would otherwise
        // leave a truncated `.bin` that `rehydrateIndex` indexes with the
        // wrong size; subsequent `readBytes()` returns truncated bytes that
        // a decoder treats as corrupt. Done outside the monitor so a main-thread
        // `contains()` isn't blocked behind multi-MB writes. See #1033.
        try {
            tmp.writeBytes(bytes)
        } catch (_: IOException) {
            runCatching { tmp.delete() }
            // Disk full / permission error. L1 still holds the bytes; this
            // entry just won't survive restart. Silent fail is acceptable.
            return
        }
        // When a ciphertext tag is required (disappearing-message media), it is
        // the only thing that lets the expiry sweep wipe this entry by hash after
        // a restart, so the write FAILS CLOSED on it: persist the `.tag`
        // (atomically, temp + rename) BEFORE the `.bin` is renamed into place. A
        // crash then leaves at most an orphan `.tag` (swept on rehydrate) or a
        // complete pair — never a decrypted `.bin` without the tag that authorizes
        // its later deletion. If the tag can't be persisted, drop the `.bin`.
        // The expensive tag write happens outside the monitor; the final tag
        // rename is part of the short commit phase so concurrent puts for the
        // same key cannot publish a mismatched `.bin`/`.tag` pair.
        val tagFile = if (ciphertextTag != null) tagFileFor(file) else null
        val tagTmp =
            if (ciphertextTag != null && tagFile != null) {
                val tmpTag =
                    uniqueTmpFile(
                        tagFile.name.removeSuffix(TAG_SUFFIX),
                        "tag",
                    )
                val tagWritten =
                    runCatching {
                        tmpTag.writeText(ciphertextTag)
                    }.isSuccess
                if (!tagWritten) {
                    runCatching { tmpTag.delete() }
                    runCatching { tmp.delete() }
                    return
                }
                tmpTag
            } else {
                null
            }
        // Stale `.tag` for THIS key: deleted unguarded because the key is live
        // again (this put re-added it), so the liveness guard would wrongly skip
        // it — but the new entry has no tag at that path, so it must go.
        val staleTagFiles = mutableListOf<File>()
        // Evicted OTHER keys: routed through the liveness guard so a concurrent
        // same-key re-put's fresh file isn't unlinked by this deferred delete.
        val evicted = mutableListOf<Pair<String, List<File>>>()
        synchronized(this) {
            // A concurrent clear() (sign-out / account switch) may have run while
            // we were writing — abort and drop temp artifacts rather than
            // re-persisting plaintext for a wiped session. See #154, #1033.
            if (expectedGeneration != generation) {
                abortPut(tmp, tagTmp)
                return
            }
            val existing = index.remove(hashed)
            if (existing != null) {
                residentBytes -= existing.size
                // Do NOT delete existing.file: a same-key replace shares the
                // destination path (deterministic hashed name), which
                // `tmp.renameTo(file)` below overwrites in place. Scheduling it
                // for the deferred delete removed the freshly-written bytes
                // (regression from moving deletes off the monitor). Clean up
                // only a STALE tag, and only when this put writes no new tag to
                // that same path — a new tag would have overwritten it above.
                if (ciphertextTag == null) staleTagFiles += tagFileFor(file)
            }
            if (tagTmp != null && tagFile != null && !tagTmp.renameTo(tagFile)) {
                abortPut(tmp, tagTmp)
                return
            }
            if (!tmp.renameTo(file)) {
                runCatching { tmp.delete() }
                // Couldn't place the `.bin`; drop the tag we just wrote so no orphan
                // sidecar points at a nonexistent entry.
                tagFile?.let { runCatching { it.delete() } }
                return
            }
            val size = bytes.size
            index[hashed] = Entry(file, size, ciphertextTag)
            residentBytes += size
            evicted += evictedEntryFiles()
        }
        deleteFiles(staleTagFiles)
        deleteStaleEntries(evicted)
    }

    /** Immediate write at the current generation. Deferred/background writes
     *  that must honor a sign-out wipe should capture [generation] at schedule
     *  time and use the three-arg overload instead. */
    fun put(
        key: String,
        bytes: ByteArray,
    ) {
        val currentGeneration = synchronized(this) { generation }
        put(key, bytes, currentGeneration)
    }

    /**
     * Drop a single entry — delete its backing file and index row. Used by the
     * disappearing-message sweep to evict an expired attachment's decrypted
     * plaintext from disk once the engine reports it secure-deleted, so it isn't
     * recoverable from the L2 cache after expiry. No-op if absent.
     */
    fun remove(key: String) {
        ensureHydrated()
        val hashed = fileNameFor(key)
        val stale =
            synchronized(this) {
                val entry = index.remove(hashed) ?: return
                residentBytes -= entry.size
                listOf(hashed to listOf(entry.file, tagFileFor(entry.file)))
            }
        deleteStaleEntries(stale)
    }

    /**
     * Evict every entry whose ciphertext tag is in [ciphertextTags] — deleting
     * its backing file, sidecar, and index row. Unlike [remove], this matches
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
                removed
            }
        deleteStaleEntries(stale)
        return removed
    }

    private fun deleteFiles(files: List<File>) {
        files.forEach { runCatching { it.delete() } }
    }

    /**
     * Delete each removed entry's files outside the monitor (keeping bulk
     * unlinks off the lock, per #1069), but skip any key a concurrent put() has
     * re-created — its path now holds fresh bytes, and unlinking those would
     * drop the just-written entry. One lock acquisition snapshots which keys are
     * live again; the unlinks then run unlocked.
     */
    private fun deleteStaleEntries(stale: List<Pair<String, List<File>>>) {
        if (stale.isEmpty()) return
        val recreated =
            synchronized(this) {
                stale.mapNotNullTo(HashSet()) { (hashed, _) -> hashed.takeIf(index::containsKey) }
            }
        stale.forEach { (hashed, files) ->
            if (hashed !in recreated) files.forEach { runCatching { it.delete() } }
        }
    }

    private fun filesFor(entry: Entry): List<File> = listOf(entry.file, tagFileFor(entry.file))

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

    @Synchronized
    fun clear() {
        // Bump first so any put scheduled against the prior generation is
        // rejected even if it grabs this lock right after the wipe. See #154.
        generation++
        // Hold the lock for the whole wipe. Deleting outside it (an earlier
        // #99 attempt) let a concurrent put() recreate a `.bin` that the orphan
        // sweep then removed — a race. clear() runs on sign-out/account-switch,
        // so briefly blocking get()/put() is fine, and it keeps the privacy
        // guarantee that ALL of this account's media (including orphan `.bin`s)
        // is wiped. The #99 win — get() not holding the lock across readBytes —
        // is unaffected, since that's in get(), not here.
        index.values.forEach { entry ->
            runCatching { entry.file.delete() }
            runCatching { tagFileFor(entry.file).delete() }
        }
        index.clear()
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
                    (it.name.endsWith(SUFFIX) || it.name.endsWith(TMP_SUFFIX) || it.name.endsWith(TAG_SUFFIX))
            }?.forEach { runCatching { it.delete() } }
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

    private fun abortPut(
        tmp: File,
        tagTmp: File?,
    ) {
        runCatching { tmp.delete() }
        tagTmp?.let { runCatching { it.delete() } }
    }

    private fun buildHydratedIndex(): HydratedIndex {
        val hydratedIndex = LinkedHashMap<String, Entry>(8, 0.75f, true)
        var hydratedBytes = 0L
        val allFiles = cacheDir.listFiles()?.filter { it.isFile } ?: return HydratedIndex(hydratedIndex, hydratedBytes)
        // Sweep stranded `.tmp` files from a prior crash so the byte cap
        // matches what's actually on disk.
        for (file in allFiles) {
            if (file.name.endsWith(TMP_SUFFIX)) {
                runCatching { file.delete() }.onFailure {
                    android.util.Log.w("DiskByteCache", "failed to delete orphan ${file.name}", it)
                }
            }
        }
        val files =
            allFiles
                .filter { it.name.endsWith(SUFFIX) }
                .sortedBy { it.lastModified() }
        for (file in files) {
            val size = file.length()
            if (size <= 0 || size > Int.MAX_VALUE || size > entryByteLimit) {
                runCatching { file.delete() }
                runCatching { tagFileFor(file).delete() }
                continue
            }
            // Recover the persisted ciphertext tag so hash-based eviction works
            // for entries cached in a previous session.
            val tag =
                tagFileFor(file)
                    .takeIf { it.exists() }
                    ?.let { runCatching { it.readText() }.getOrNull() }
                    ?.takeIf { it.isNotBlank() }
            hydratedIndex[file.name] = Entry(file, size.toInt(), tag)
            hydratedBytes += size
        }
        // Hot-trim if total resident exceeds cap (e.g., cap was reduced
        // since the previous run; or disk filled out-of-band).
        val iterator = hydratedIndex.entries.iterator()
        while (iterator.hasNext() && hydratedBytes > maxBytes) {
            val (_, entry) = iterator.next()
            runCatching { entry.file.delete() }
            runCatching { tagFileFor(entry.file).delete() }
            hydratedBytes -= entry.size
            iterator.remove()
        }
        // Drop any orphaned `.tag` sidecar whose `.bin` is gone, so they don't
        // accumulate after entries are evicted out-of-band.
        allFiles
            .filter { it.name.endsWith(TAG_SUFFIX) }
            .forEach { tagFile ->
                val binName = tagFile.name.removeSuffix(TAG_SUFFIX) + SUFFIX
                if (!hydratedIndex.containsKey(binName)) runCatching { tagFile.delete() }
            }
        return HydratedIndex(hydratedIndex, hydratedBytes)
    }

    // Sibling sidecar that stores an entry's ciphertext tag: `<sha256>.tag`
    // next to `<sha256>.bin`.
    private fun tagFileFor(binFile: File): File = File(binFile.parentFile, binFile.name.removeSuffix(SUFFIX) + TAG_SUFFIX)

    private fun fileNameFor(key: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(key.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(HEX[(b.toInt() ushr 4) and 0x0F])
            sb.append(HEX[b.toInt() and 0x0F])
        }
        sb.append(SUFFIX)
        return sb.toString()
    }

    private data class Entry(
        val file: File,
        val size: Int,
        val tag: String? = null,
    )

    private data class HydratedIndex(
        val index: LinkedHashMap<String, Entry>,
        val residentBytes: Long,
    )

    private companion object {
        const val SUFFIX = ".bin"
        const val TMP_SUFFIX = ".tmp"
        const val TAG_SUFFIX = ".tag"
        const val DEFAULT_MAX_ENTRY_BYTES: Long = 16L * 1024L * 1024L
        val TMP_COUNTER = AtomicLong()
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
