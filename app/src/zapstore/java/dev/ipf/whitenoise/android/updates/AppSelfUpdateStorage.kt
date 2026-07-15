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
    ): File {
        // Defence in depth: the version is CalVer-validated upstream, but never
        // let a path separator reach the filename regardless of the caller, so a
        // crafted version can't escape the updates directory.
        val safeVersion = version.filter { it.isDigit() || it == '.' }
        return File(updatesDirectory(context), "darkmatter-$safeVersion.apk")
    }

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
