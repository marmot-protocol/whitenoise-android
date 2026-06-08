package dev.ipf.darkmatter.media

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
    private val index = LinkedHashMap<String, Entry>(8, 0.75f, /* accessOrder = */ true)
    private var residentBytes: Long = 0L
    private var hydrated = false

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

    @Synchronized
    fun get(key: String): ByteArray? {
        ensureHydrated()
        val hashed = fileNameFor(key)
        val entry = index[hashed] ?: return null
        return try {
            entry.file.readBytes()
        } catch (_: IOException) {
            // File vanished (manual delete, OS cache reap, FS corruption).
            // Drop the index entry and report miss; the caller will re-fetch.
            index.remove(hashed)
            residentBytes -= entry.size
            null
        }
    }

    @Synchronized
    fun put(key: String, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        ensureHydrated()
        val hashed = fileNameFor(key)
        val existing = index.remove(hashed)
        if (existing != null) {
            residentBytes -= existing.size
            runCatching { existing.file.delete() }
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
            if (!tmp.renameTo(file)) {
                runCatching { tmp.delete() }
                return
            }
        } catch (_: IOException) {
            runCatching { tmp.delete() }
            // Disk full / permission error. L1 still holds the bytes; this
            // entry just won't survive restart. Silent fail is acceptable.
            return
        }
        val size = bytes.size
        index[hashed] = Entry(file, size)
        residentBytes += size
        evictUntilUnderCap()
    }

    @Synchronized
    fun clear() {
        ensureHydrated()
        index.values.forEach { runCatching { it.file.delete() } }
        index.clear()
        residentBytes = 0L
        // Catch any orphans that aren't in the index — e.g. a prior crash
        // mid-put left a `.tmp`, or an entry whose index row was lost.
        // Scoped to OUR file-naming convention (`.bin` + `.tmp`) so a
        // future co-tenant in the same dir survives sign-out.
        cacheDir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && (it.name.endsWith(SUFFIX) || it.name.endsWith(TMP_SUFFIX)) }
            ?.forEach { runCatching { it.delete() } }
    }

    fun size(): Int = synchronized(this) { ensureHydrated(); index.size }
    fun residentBytes(): Long = synchronized(this) { ensureHydrated(); residentBytes }

    private fun evictUntilUnderCap() {
        if (residentBytes <= maxBytes) return
        val it = index.entries.iterator()
        while (it.hasNext() && residentBytes > maxBytes) {
            val (_, entry) = it.next()
            runCatching { entry.file.delete() }
            residentBytes -= entry.size
            it.remove()
        }
    }

    private fun rehydrateIndex() {
        val files = cacheDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(SUFFIX) }
            ?.sortedBy { it.lastModified() }
            ?: return
        for (file in files) {
            val size = file.length()
            if (size <= 0 || size > Int.MAX_VALUE) {
                runCatching { file.delete() }
                continue
            }
            index[file.name] = Entry(file, size.toInt())
            residentBytes += size
        }
        // Hot-trim if total resident exceeds cap (e.g., cap was reduced
        // since the previous run; or disk filled out-of-band).
        evictUntilUnderCap()
    }

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

    private data class Entry(val file: File, val size: Int)

    private companion object {
        const val SUFFIX = ".bin"
        const val TMP_SUFFIX = ".tmp"
        val HEX = charArrayOf(
            '0', '1', '2', '3', '4', '5', '6', '7',
            '8', '9', 'a', 'b', 'c', 'd', 'e', 'f',
        )
    }
}
