package dev.ipf.darkmatter.updates

import android.content.Context
import java.io.File

object AppSelfUpdateStorage {
    const val CACHE_DIR_NAME = "app_updates"
    const val STALE_MAX_AGE_MS: Long = 24L * 60L * 60L * 1000L

    private val activeDownloadLock = Any()
    private val activeDownloadPaths = mutableSetOf<String>()

    fun updatesDirectory(context: Context): File = File(context.cacheDir, CACHE_DIR_NAME).apply { mkdirs() }

    fun apkFileForOperation(
        context: Context,
        version: String,
        generation: Long,
    ): File = File(updatesDirectory(context), "darkmatter-$version-$generation.apk")

    fun deleteFile(file: File?) {
        if (file == null) return
        runCatching { file.delete() }
    }

    internal fun registerActiveDownload(destination: File) {
        synchronized(activeDownloadLock) {
            activeDownloadPaths += destination.absolutePath
            activeDownloadPaths += partialFile(destination).absolutePath
        }
    }

    internal fun unregisterActiveDownload(destination: File) {
        synchronized(activeDownloadLock) {
            activeDownloadPaths -= destination.absolutePath
            activeDownloadPaths -= partialFile(destination).absolutePath
        }
    }

    fun sweepStaleApks(
        context: Context,
        maxAgeMillis: Long = STALE_MAX_AGE_MS,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val directory = updatesDirectory(context)
        if (!directory.isDirectory) return
        val cutoff = nowMillis - maxAgeMillis
        synchronized(activeDownloadLock) {
            directory.listFiles()?.forEach { entry ->
                if (!entry.isFile || entry.absolutePath in activeDownloadPaths) return@forEach
                if (entry.name.endsWith(".part") || entry.lastModified() < cutoff) {
                    deleteFile(entry)
                }
            }
        }
    }

    private fun partialFile(destination: File): File = File(destination.parentFile, "${destination.name}.part")
}
