package dev.ipf.whitenoise.android.media

import java.io.File
import java.io.IOException
import java.security.MessageDigest

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
 * Synchronized on `this` because file I/O happens on whatever thread the
 * caller is on (typically `Dispatchers.IO` for `get`/`put`, main for
 * `clear` during sign-out).
 *
 * Eviction is LRU by access order via `LinkedHashMap(accessOrder=true)`.
 * On `init`, the directory is scanned and the in-memory index is
 * repopulated using file `lastModified` as the proxy for recency.
 */
class DiskByteCache(
    private val cacheDir: File,
    private val maxBytes: Long,
) {
    // accessOrder = true → LinkedHashMap iterates in LRU order for eviction.
    private val index = LinkedHashMap<String, Entry>(8, 0.75f, true)
    private var residentBytes: Long = 0L
    private var hydrated = false

    // Bumped on every clear(). A deferred put() captures this at schedule time
    // and is rejected if a wipe intervened, so decrypted plaintext from a
    // signed-out session can't be re-persisted after sign-out. See #154.
    private var generation = 0

    // No directory I/O in the constructor: the scan + per-file stat are
    // deferred to the first cache operation so they don't run on the main
    // thread at app launch (the cache is constructed eagerly as an AppState
    // field). First access happens on Dispatchers.IO. See #100.
    private fun ensureHydrated() {
        if (hydrated) return
        cacheDir.mkdirs()
        rehydrateIndex()
        hydrated = true
    }

    /**
     * Yes/no probe — true iff bytes for [key] are currently indexed on
     * disk. Doesn't read the file, doesn't promote LRU, doesn't fall
     * through to anywhere. Lets a caller decide UI affordances (e.g. show
     * a download chevron only on miss) without paying the read cost.
     */
    fun contains(key: String): Boolean {
        val hashed = fileNameFor(key)
        return synchronized(this) {
            ensureHydrated()
            index.containsKey(hashed)
        }
    }

    fun get(key: String): ByteArray? {
        val hashed = fileNameFor(key)
        // Look up (and LRU-promote) the entry under the lock, then read the
        // file OUTSIDE it. Holding the monitor across readBytes() serialized
        // every concurrent media load and blocked clear() for the duration of
        // disk I/O. See #99.
        val (entry, generationAtLookup) =
            synchronized(this) {
                ensureHydrated()
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
                    runCatching { tagFileFor(entry.file).delete() }
                }
            }
            null
        }
    }

    /** Wipe generation to capture when scheduling a deferred [put]. */
    @Synchronized
    fun generation(): Int = generation

    @Synchronized
    fun put(
        key: String,
        bytes: ByteArray,
        expectedGeneration: Int,
        ciphertextTag: String? = null,
    ) {
        if (bytes.isEmpty()) return
        // Reject a write whose session was wiped while it sat queued: clear()
        // bumps `generation` under this same lock, so a put scheduled before
        // the wipe skips here and no plaintext lands after sign-out. See #154.
        if (expectedGeneration != generation) return
        ensureHydrated()
        val hashed = fileNameFor(key)
        val existing = index.remove(hashed)
        if (existing != null) {
            residentBytes -= existing.size
            runCatching { existing.file.delete() }
            runCatching { tagFileFor(existing.file).delete() }
        }
        // Atomic write: write to a sibling `.tmp` file then rename onto the
        // final path. A power loss or kill mid-`writeBytes` would otherwise
        // leave a truncated `.bin` that `rehydrateIndex` indexes with the
        // wrong size; subsequent `readBytes()` returns truncated bytes that
        // a decoder treats as corrupt.
        val file = File(cacheDir, hashed)
        val tmp = File(cacheDir, "$hashed$TMP_SUFFIX")
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
        if (ciphertextTag != null) {
            val tagFile = tagFileFor(file)
            val tagTmp = File(cacheDir, "${tagFile.name}$TMP_SUFFIX")
            val tagPersisted =
                runCatching {
                    tagTmp.writeText(ciphertextTag)
                    tagTmp.renameTo(tagFile)
                }.getOrDefault(false)
            if (!tagPersisted) {
                runCatching { tagTmp.delete() }
                runCatching { tmp.delete() }
                return
            }
        }
        if (!tmp.renameTo(file)) {
            runCatching { tmp.delete() }
            // Couldn't place the `.bin`; drop the tag we just wrote so no orphan
            // sidecar points at a nonexistent entry.
            if (ciphertextTag != null) runCatching { tagFileFor(file).delete() }
            return
        }
        val size = bytes.size
        index[hashed] = Entry(file, size, ciphertextTag)
        residentBytes += size
        evictUntilUnderCap()
    }

    /** Immediate write at the current generation. Deferred/background writes
     *  that must honor a sign-out wipe should capture [generation] at schedule
     *  time and use the three-arg overload instead. */
    @Synchronized
    fun put(
        key: String,
        bytes: ByteArray,
    ) = put(key, bytes, generation)

    /**
     * Drop a single entry — delete its backing file and index row. Used by the
     * disappearing-message sweep to evict an expired attachment's decrypted
     * plaintext from disk once the engine reports it secure-deleted, so it isn't
     * recoverable from the L2 cache after expiry. No-op if absent.
     */
    @Synchronized
    fun remove(key: String) {
        ensureHydrated()
        val entry = index.remove(fileNameFor(key)) ?: return
        residentBytes -= entry.size
        runCatching { entry.file.delete() }
        runCatching { tagFileFor(entry.file).delete() }
    }

    /**
     * Evict every entry whose ciphertext tag is in [ciphertextTags] — deleting
     * its backing file, sidecar, and index row. Unlike [remove], this matches
     * by the persisted ciphertext hash rather than the cache key, so the
     * disappearing-message sweep can wipe expired attachments from disk even
     * when their message isn't currently loaded (and thus has no entry in the
     * in-memory hash→key reference map). Returns the number of entries removed.
     */
    @Synchronized
    fun removeByCiphertextTags(ciphertextTags: Set<String>): Int {
        if (ciphertextTags.isEmpty()) return 0
        ensureHydrated()
        var removed = 0
        val iterator = index.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            if (entry.tag != null && entry.tag in ciphertextTags) {
                runCatching { entry.file.delete() }
                runCatching { tagFileFor(entry.file).delete() }
                residentBytes -= entry.size
                iterator.remove()
                removed++
            }
        }
        return removed
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
        ensureHydrated()
        index.values.forEach {
            runCatching { it.file.delete() }
            runCatching { tagFileFor(it.file).delete() }
        }
        index.clear()
        residentBytes = 0L
        // Catch any orphans that aren't in the index — e.g. a prior crash
        // mid-put left a `.tmp`, or an entry whose index row was lost. Scoped to
        // OUR naming (`.bin` + `.tmp`) so a future co-tenant survives sign-out.
        cacheDir
            .listFiles()
            ?.asSequence()
            ?.filter {
                it.isFile &&
                    (it.name.endsWith(SUFFIX) || it.name.endsWith(TMP_SUFFIX) || it.name.endsWith(TAG_SUFFIX))
            }?.forEach { runCatching { it.delete() } }
    }

    fun size(): Int =
        synchronized(this) {
            ensureHydrated()
            index.size
        }

    fun residentBytes(): Long =
        synchronized(this) {
            ensureHydrated()
            residentBytes
        }

    private fun evictUntilUnderCap() {
        if (residentBytes <= maxBytes) return
        val it = index.entries.iterator()
        while (it.hasNext() && residentBytes > maxBytes) {
            val (_, entry) = it.next()
            runCatching { entry.file.delete() }
            runCatching { tagFileFor(entry.file).delete() }
            residentBytes -= entry.size
            it.remove()
        }
    }

    private fun rehydrateIndex() {
        val allFiles = cacheDir.listFiles()?.filter { it.isFile } ?: return
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
            if (size <= 0 || size > Int.MAX_VALUE) {
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
            index[file.name] = Entry(file, size.toInt(), tag)
            residentBytes += size
        }
        // Drop any orphaned `.tag` sidecar whose `.bin` is gone, so they don't
        // accumulate after entries are evicted out-of-band.
        allFiles
            .filter { it.name.endsWith(TAG_SUFFIX) }
            .forEach { tagFile ->
                val binName = tagFile.name.removeSuffix(TAG_SUFFIX) + SUFFIX
                if (!index.containsKey(binName)) runCatching { tagFile.delete() }
            }
        // Hot-trim if total resident exceeds cap (e.g., cap was reduced
        // since the previous run; or disk filled out-of-band).
        evictUntilUnderCap()
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

    private companion object {
        const val SUFFIX = ".bin"
        const val TMP_SUFFIX = ".tmp"
        const val TAG_SUFFIX = ".tag"
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
