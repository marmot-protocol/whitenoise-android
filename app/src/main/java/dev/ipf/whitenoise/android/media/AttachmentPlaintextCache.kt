package dev.ipf.whitenoise.android.media

import androidx.annotation.VisibleForTesting
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Byte-bounded LRU janitor for decrypted attachment files. */
internal object AttachmentPlaintextCache {
    internal const val VOICE_MAX_DIRECTORY_BYTES: Long = 64L * 1024L * 1024L
    internal const val VIDEO_MAX_DIRECTORY_BYTES: Long = 128L * 1024L * 1024L

    // MarmotKit accepts encrypted attachments up to 512 MiB. The shared-file
    // handoff must admit their (smaller) verified plaintext, including APKs;
    // the janitor still bounds the whole directory and evicts older files.
    internal const val SHARED_MAX_DIRECTORY_BYTES: Long = 512L * 1024L * 1024L

    // The largest single entry any of these directories admits: MarmotKit's attachment ceiling, so a large audio
    // file or video that was sent or received can still be played and saved. A directory keeps at most one entry
    // larger than its budget, beside the budget itself (see trimDirectoryToByteCap).
    internal const val ENTRY_MAX_BYTES: Long = 512L * 1024L * 1024L

    // How long an app that was handed a shared-cache file may take to read it. The app-start sweep waits as long
    // before deleting shared files, and a size trim does not evict the newest of them sooner.
    internal const val SHARED_READER_WINDOW_MILLIS: Long = 10L * 60L * 1000L

    private val trimLock = Any()

    // Path refcounts of files being published or held in use. Concurrent, so a hold taken on the main thread never
    // waits for a trim deleting large files, and a trim reads it again just before each deletion.
    private val activePublicationPathCounts = ConcurrentHashMap<String, Int>()

    /** Refuses one entry larger than [ENTRY_MAX_BYTES] for any of the bounded attachment cache directories. */
    @Throws(IOException::class)
    fun requireEntryWithinLimit(
        finalFile: File,
        entryBytes: Long,
    ) {
        if (maximumDirectoryBytes(finalFile.parentFile) == null) return
        if (entryBytes > ENTRY_MAX_BYTES) {
            throw IOException("attachment cache entry exceeds ${ENTRY_MAX_BYTES}B limit")
        }
    }

    fun onPublished(finalFile: File) {
        val directory = finalFile.parentFile ?: return
        val maxBytes = maximumDirectoryBytes(directory) ?: return
        touch(finalFile)
        trimDirectoryToByteCap(directory, maxBytes, protectedFile = finalFile)
    }

    /** Releases publication protection and trims without exposing a race between the two operations. */
    internal fun finishPublication(finalFile: File) {
        synchronized(trimLock) {
            unprotectPublicationFile(finalFile)
            onPublished(finalFile)
        }
    }

    fun touch(
        file: File,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        synchronized(trimLock) {
            if (file.isFile) runCatching { file.setLastModified(nowMillis) }
        }
    }

    // Runs between a trim's decision to evict a file and its deletion, so a test can interleave a hold there.
    @VisibleForTesting
    @Volatile
    internal var beforeEvictionForTests: ((File) -> Unit)? = null

    /**
     * Whether [file], which the caller already holds, is present. It is read after any trim in progress has
     * finished, so a file found present is kept until the hold is released, even when the hold landed while that
     * trim was deciding what to delete. Call it off the main thread.
     */
    internal fun isHeldFilePresent(file: File): Boolean = synchronized(trimLock) { file.isFile }

    /**
     * Adds one publication or in-use hold on [file]'s path, which no trim that starts afterwards evicts until it is
     * released. A trim already deciding may still delete it, so a holder confirms it with [isHeldFilePresent].
     */
    internal fun protectPublicationFile(file: File) {
        activePublicationPathCounts.merge(file.absolutePath, 1, Int::plus)
    }

    /** Releases one hold on [file]'s path, forgetting the path once its last hold is released. */
    internal fun unprotectPublicationFile(file: File) {
        activePublicationPathCounts.computeIfPresent(file.absolutePath) { _, count -> (count - 1).takeIf { it > 0 } }
    }

    fun trimKnownDirectories(cacheRoot: File) {
        trimDirectoryToByteCap(File(cacheRoot, MediaCacheDirs.VOICE), VOICE_MAX_DIRECTORY_BYTES)
        trimDirectoryToByteCap(File(cacheRoot, MediaCacheDirs.VIDEO), VIDEO_MAX_DIRECTORY_BYTES)
        trimDirectoryToByteCap(File(cacheRoot, MediaCacheDirs.SHARED), SHARED_MAX_DIRECTORY_BYTES)
    }

    /**
     * Evicts unpinned files of [directory] until it fits [maxBytes], and returns the bytes left. Pinned files are
     * never evicted: those being published or held in use, [protectedFile], the kept oversized entry, and the newest
     * shared-cache files, up to the budget, younger than [SHARED_READER_WINDOW_MILLIS], which an app may still be
     * reading. A pinned file larger than the budget does not count against it, so its neighbours are held to the
     * budget rather than flushed. Holds are read again just before each deletion, while the budget is set from the
     * pins at the start, so a hold that lands mid-trim on a large file counts off the budget from the next trim.
     *
     * With [keepOversized], one finished entry larger than the whole budget is kept: [protectedFile] when that is
     * the oversized one, otherwise the most recently used oversized entry. So a large video stays while smaller
     * files come and go, and a newer large one replaces it. A displaced oversized entry is evicted before any
     * smaller file. A budget of zero keeps nothing that is not being published or held.
     */
    @VisibleForTesting
    internal fun trimDirectoryToByteCap(
        directory: File,
        maxBytes: Long,
        protectedFile: File? = null,
        keepOversized: Boolean = true,
        nowMillis: Long = System.currentTimeMillis(),
    ): Long =
        synchronized(trimLock) {
            if (maxBytes < 0L || !directory.isDirectory) return@synchronized 0L
            // One snapshot of each file's size and age, so the order cannot shift while it is sorted.
            val entries =
                directory
                    .listFiles()
                    ?.filter { it.isFile }
                    ?.map(::CacheEntry)
                    .orEmpty()
            val totalBytes = entries.fold(0L) { total, entry -> saturatingAdd(total, entry.length) }
            if (totalBytes <= maxBytes) return@synchronized totalBytes

            val kept =
                if (keepOversized && maxBytes > 0L) keptOversizedEntry(entries, maxBytes, protectedFile) else null
            val pinnedPaths =
                setOfNotNull(protectedFile?.absolutePath, kept?.path) +
                    readerWindowPaths(directory, entries, maxBytes, nowMillis)
            val pinned = { entry: CacheEntry ->
                entry.path in pinnedPaths || activePublicationPathCounts.containsKey(entry.path)
            }
            val cap =
                entries
                    .filter { it.length > maxBytes && pinned(it) }
                    .fold(maxBytes) { sum, entry -> saturatingAdd(sum, entry.length) }
            val ordered =
                entries.sortedWith(
                    compareByDescending<CacheEntry> { it.length > maxBytes }
                        .thenBy { it.modified }
                        .thenBy { it.file.name },
                )
            evictInOrder(ordered, totalBytes, cap) { entry -> !pinned(entry) }
        }

    private fun maximumDirectoryBytes(directory: File?): Long? =
        when (directory?.name) {
            MediaCacheDirs.VOICE -> VOICE_MAX_DIRECTORY_BYTES
            MediaCacheDirs.VIDEO -> VIDEO_MAX_DIRECTORY_BYTES
            MediaCacheDirs.SHARED -> SHARED_MAX_DIRECTORY_BYTES
            else -> null
        }
}

// An interrupted publication's temp file, never a finished entry worth keeping beyond the budget.
private const val TEMP_FILE_SUFFIX = ".tmp"

/** One cache file with the size and age a trim read once, so its order is stable while it is sorted. */
private class CacheEntry(
    source: File,
) {
    val file: File = source
    val path: String = file.absolutePath
    val length: Long = file.length()
    val modified: Long = file.lastModified()
}

/** The one finished entry over [maxBytes] that a trim keeps: [protectedFile] if oversized, else the newest. */
private fun keptOversizedEntry(
    entries: List<CacheEntry>,
    maxBytes: Long,
    protectedFile: File?,
): CacheEntry? {
    val oversized = entries.filter { it.length > maxBytes && !it.file.name.endsWith(TEMP_FILE_SUFFIX) }
    return oversized.firstOrNull { it.path == protectedFile?.absolutePath }
        ?: oversized.maxWithOrNull(compareBy<CacheEntry> { it.modified }.thenBy { it.file.name })
}

/**
 * The shared-cache files an app may still be reading: those younger than the reader window, newest first, up to
 * [maxBytes] in all, so the window never lets the shared cache outgrow its budget.
 */
private fun readerWindowPaths(
    directory: File,
    entries: List<CacheEntry>,
    maxBytes: Long,
    nowMillis: Long,
): Set<String> {
    if (maxBytes <= 0L || directory.name != MediaCacheDirs.SHARED) return emptySet()
    val since = nowMillis - AttachmentPlaintextCache.SHARED_READER_WINDOW_MILLIS
    var windowBytes = 0L
    return entries
        .filter { it.modified > since }
        .sortedByDescending { it.modified }
        .takeWhile { entry ->
            windowBytes = saturatingAdd(windowBytes, entry.length)
            windowBytes <= maxBytes
        }.mapTo(HashSet()) { it.path }
}

/** Adds two byte counts, stopping at [Long.MAX_VALUE] instead of overflowing. */
private fun saturatingAdd(
    first: Long,
    second: Long,
): Long = if (second > Long.MAX_VALUE - first) Long.MAX_VALUE else first + second

/**
 * Keeps [file] out of every attachment cache trim while a player, Save or Share reads it, so a publication elsewhere
 * in its directory cannot delete it from under them. Each hold is released by one [releaseCacheFileInUse]. A holder
 * that reads the file confirms it first with [AttachmentPlaintextCache.isHeldFilePresent].
 */
internal fun holdCacheFileInUse(file: File) = AttachmentPlaintextCache.protectPublicationFile(file)

/** Ends one [holdCacheFileInUse] of [file]. */
internal fun releaseCacheFileInUse(file: File) = AttachmentPlaintextCache.unprotectPublicationFile(file)

/**
 * Deletes [ordered] entries that are [evictable], asked just before each deletion, in order, until the [startBytes]
 * they total fits [cap], and returns the bytes left.
 */
private fun evictInOrder(
    ordered: List<CacheEntry>,
    startBytes: Long,
    cap: Long,
    evictable: (CacheEntry) -> Boolean,
): Long {
    var totalBytes = startBytes
    for (entry in ordered) {
        if (totalBytes <= cap) break
        if (evictable(entry)) {
            AttachmentPlaintextCache.beforeEvictionForTests?.invoke(entry.file)
            if (runCatching { entry.file.delete() }.getOrDefault(false)) {
                totalBytes = (totalBytes - entry.length).coerceAtLeast(0L)
            }
        }
    }
    return totalBytes
}

/**
 * Drops the one large entry each playback cache keeps beyond its budget, unless it is in use, so a large send that
 * needs the space is not refused for a file the cache can recreate.
 */
internal fun dropKeptOversizedCacheEntries(cacheRoot: File) {
    AttachmentPlaintextCache.trimDirectoryToByteCap(
        File(cacheRoot, MediaCacheDirs.VOICE),
        AttachmentPlaintextCache.VOICE_MAX_DIRECTORY_BYTES,
        keepOversized = false,
    )
    AttachmentPlaintextCache.trimDirectoryToByteCap(
        File(cacheRoot, MediaCacheDirs.VIDEO),
        AttachmentPlaintextCache.VIDEO_MAX_DIRECTORY_BYTES,
        keepOversized = false,
    )
}
