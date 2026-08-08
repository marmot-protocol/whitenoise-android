package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import dev.ipf.whitenoise.android.media.MediaCacheDirs
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal const val IMAGE_EDITOR_MAX_OUTPUT_BYTES = 24L * 1024L * 1024L
internal const val IMAGE_EDITOR_ORPHAN_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L

/**
 * Encode the final bounded render once, then publish it by same-directory move.
 * A failed/cancelled write never replaces a live staged attachment and leaves no
 * half-file for the FileProvider to expose.
 */
internal fun writeEditedBitmap(
    cacheRoot: File,
    bitmap: Bitmap,
    maxOutputBytes: Long = IMAGE_EDITOR_MAX_OUTPUT_BYTES,
): File? {
    val validBitmap =
        !bitmap.isRecycled &&
            withinImageEditorPixelLimit(bitmap.width, bitmap.height)
    if (!validBitmap || maxOutputBytes <= 0L) return null

    var temporary: File? = null
    var published: File? = null
    return try {
        val directory = File(cacheRoot, MediaCacheDirs.IMAGE_EDITOR)
        if (!directory.exists() && !directory.mkdirs()) throw IOException("Editor cache unavailable")
        temporary = File.createTempFile("render-", ".tmp", directory)
        restrictToOwner(temporary)
        val encoded =
            FileOutputStream(temporary).use { output ->
                val success = bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                output.fd.sync()
                success
            }
        val encodedSize = temporary.length()
        if (!encoded || encodedSize !in 1..maxOutputBytes) throw IOException("Invalid editor output")

        published = File(directory, "edited-${UUID.randomUUID()}.png")
        movePublished(temporary, published)
        restrictToOwner(published)
        published.also { published = null }
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    } finally {
        temporary?.let { runCatching { it.delete() } }
        published?.let { runCatching { it.delete() } }
    }
}

private fun movePublished(
    temporary: File,
    published: File,
) {
    try {
        Files.move(
            temporary.toPath(),
            published.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temporary.toPath(), published.toPath())
    }
}

internal fun ownedEditorFileForUri(
    cacheRoot: File,
    expectedAuthority: String,
    uri: Uri,
): File? {
    val correctProvider = uri.scheme == "content" && uri.authority == expectedAuthority
    val segments = uri.pathSegments
    val correctRoot = segments.size == 2 && segments.firstOrNull() == MediaCacheDirs.IMAGE_EDITOR
    val name = segments.getOrNull(1).orEmpty()
    return if (!correctProvider || !correctRoot || !isSafeEditorFilename(name)) {
        null
    } else {
        runCatching {
            val directory = File(cacheRoot, MediaCacheDirs.IMAGE_EDITOR).canonicalFile
            File(directory, name)
                .canonicalFile
                .takeIf { candidate -> candidate.parentFile == directory && candidate.isFile }
        }.getOrNull()
    }
}

private fun isSafeEditorFilename(name: String): Boolean {
    val reserved = name.isBlank() || name == "." || name == ".."
    val containsSeparator = name.contains('/') || name.contains('\\')
    return !reserved && !containsSeparator
}

internal fun deleteOwnedEditorUri(
    context: Context,
    uri: Uri,
) {
    val file =
        runCatching {
            ownedEditorFileForUri(
                cacheRoot = context.cacheDir,
                expectedAuthority = "${context.packageName}.fileprovider",
                uri = uri,
            )
        }.getOrNull()
    if (file != null) runCatching { file.delete() }
}

internal fun sweepStaleImageEditorFiles(
    cacheRoot: File,
    maxAgeMillis: Long = IMAGE_EDITOR_ORPHAN_MAX_AGE_MS,
    nowMillis: Long = System.currentTimeMillis(),
) {
    val directory = File(cacheRoot, MediaCacheDirs.IMAGE_EDITOR)
    val cutoff = nowMillis - maxAgeMillis.coerceAtLeast(0L)
    directory.listFiles().orEmpty().forEach { entry ->
        val partial = entry.name.endsWith(".tmp")
        val stale = entry.lastModified() < cutoff
        if (entry.isFile && (partial || stale)) runCatching { entry.delete() }
    }
}

private fun restrictToOwner(file: File) {
    // cacheDir is app-private already; these permissions also keep a future
    // shared-parent configuration from broadening plaintext access by accident.
    runCatching {
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setExecutable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
    }
}
