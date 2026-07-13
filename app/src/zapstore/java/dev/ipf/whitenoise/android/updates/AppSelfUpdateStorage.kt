package dev.ipf.whitenoise.android.updates

import android.content.Context
import java.io.File

object AppSelfUpdateStorage {
    const val CACHE_DIR_NAME = "app_updates"
    const val STALE_MAX_AGE_MS: Long = 24L * 60L * 60L * 1000L

    fun updatesDirectory(context: Context): File = File(context.cacheDir, CACHE_DIR_NAME).apply { mkdirs() }

    fun apkFileForVersion(
        context: Context,
        version: String,
    ): File = File(updatesDirectory(context), "darkmatter-$version.apk")

    fun deleteFile(file: File?) {
        if (file == null) return
        runCatching { file.delete() }
    }

    fun deletePartialDownloads(directory: File) {
        if (!directory.isDirectory) return
        directory.listFiles()?.forEach { entry ->
            if (entry.isFile && entry.name.endsWith(".part")) {
                deleteFile(entry)
            }
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
        directory.listFiles()?.forEach { entry ->
            if (entry.isFile && entry.lastModified() < cutoff) {
                deleteFile(entry)
            }
        }
        deletePartialDownloads(directory)
    }
}
