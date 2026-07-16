package dev.ipf.whitenoise.android.media

import androidx.annotation.VisibleForTesting
import java.io.File
import java.io.IOException

/** Byte-bounded LRU janitor for decrypted voice/video files. */
internal object AttachmentPlaintextCache {
    internal const val VOICE_MAX_DIRECTORY_BYTES: Long = 64L * 1024L * 1024L
    internal const val VIDEO_MAX_DIRECTORY_BYTES: Long = 128L * 1024L * 1024L

    private val trimLock = Any()
    private val activePublicationPaths = mutableSetOf<String>()

    @Throws(IOException::class)
    fun requireEntryWithinLimit(
        finalFile: File,
        entryBytes: Long,
    ) {
        val maxBytes = maximumDirectoryBytes(finalFile.parentFile) ?: return
        if (entryBytes > maxBytes) {
            throw IOException("attachment cache entry exceeds ${maxBytes}B limit")
        }
    }

    fun onPublished(finalFile: File) {
        val directory = finalFile.parentFile ?: return
        val maxBytes = maximumDirectoryBytes(directory) ?: return
        touch(finalFile)
        trimDirectoryToByteCap(directory, maxBytes, protectedFile = finalFile)
    }

    fun touch(
        file: File,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        synchronized(trimLock) {
            if (file.isFile) runCatching { file.setLastModified(nowMillis) }
        }
    }

    internal fun protectPublicationFile(file: File) {
        synchronized(trimLock) { activePublicationPaths.add(file.absolutePath) }
    }

    internal fun unprotectPublicationFile(file: File) {
        synchronized(trimLock) { activePublicationPaths.remove(file.absolutePath) }
    }

    fun trimKnownDirectories(cacheRoot: File) {
        trimDirectoryToByteCap(File(cacheRoot, MediaCacheDirs.VOICE), VOICE_MAX_DIRECTORY_BYTES)
        trimDirectoryToByteCap(File(cacheRoot, MediaCacheDirs.VIDEO), VIDEO_MAX_DIRECTORY_BYTES)
    }

    @VisibleForTesting
    internal fun trimDirectoryToByteCap(
        directory: File,
        maxBytes: Long,
        protectedFile: File? = null,
    ): Long =
        synchronized(trimLock) {
            if (maxBytes < 0L || !directory.isDirectory) return@synchronized 0L
            val files =
                directory
                    .listFiles()
                    ?.filter { it.isFile }
                    .orEmpty()
            var totalBytes = files.fold(0L) { total, file -> saturatingAdd(total, file.length()) }
            if (totalBytes <= maxBytes) return@synchronized totalBytes

            val protectedPaths = HashSet(activePublicationPaths)
            protectedFile?.absolutePath?.let(protectedPaths::add)
            files
                .sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
                .forEach { file ->
                    if (totalBytes <= maxBytes) return@forEach
                    if (file.absolutePath in protectedPaths) return@forEach
                    val length = file.length()
                    if (runCatching { file.delete() }.getOrDefault(false)) {
                        totalBytes = (totalBytes - length).coerceAtLeast(0L)
                    }
                }
            totalBytes
        }

    private fun maximumDirectoryBytes(directory: File?): Long? =
        when (directory?.name) {
            MediaCacheDirs.VOICE -> VOICE_MAX_DIRECTORY_BYTES
            MediaCacheDirs.VIDEO -> VIDEO_MAX_DIRECTORY_BYTES
            else -> null
        }

    private fun saturatingAdd(
        first: Long,
        second: Long,
    ): Long = if (second > Long.MAX_VALUE - first) Long.MAX_VALUE else first + second
}
