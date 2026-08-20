package dev.ipf.whitenoise.android.state

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import androidx.core.content.FileProvider
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.UUID

private const val AUDIT_LOG_SHARE_DIRECTORY = "audit_logs"
private val unsafeAuditFileName = Regex("[^A-Za-z0-9._-]")

/**
 * Stages engine-owned audit logs in the app cache for an explicit Android share.
 * Symlinks and non-regular files are rejected so a compromised path cannot make
 * the FileProvider expose an unrelated file. The directory is replaced for each
 * export so previously shared forensic data does not accumulate in cache.
 */
internal fun prepareAuditLogShareFiles(
    cacheDir: File,
    allowedSourceRoot: File,
    sourcePaths: List<String>,
): List<File> {
    val shareRoot = File(cacheDir, AUDIT_LOG_SHARE_DIRECTORY)
    clearPreparedAuditLogShares(cacheDir)
    check(shareRoot.mkdirs() || shareRoot.isDirectory) {
        "Unable to prepare audit log export"
    }
    val shareDirectory = File(shareRoot, UUID.randomUUID().toString())
    check(shareDirectory.mkdir()) { "Unable to prepare audit log export" }

    val allowedRoot = allowedSourceRoot.toPath().toRealPath()
    val usedNames = mutableSetOf<String>()
    return sourcePaths.mapNotNull { sourcePath ->
        val source = confinedRegularAuditFile(File(sourcePath), allowedRoot) ?: return@mapNotNull null

        val safeBaseName =
            source.name
                .replace(unsafeAuditFileName, "_")
                .trim('.', '_')
                .ifBlank { "audit-log.jsonl" }
        val destinationName = uniqueAuditFileName(safeBaseName, usedNames)
        val destination = File(shareDirectory, destinationName)
        source.copyTo(destination, overwrite = false)
        destination.setWritable(false, false)
        destination
    }
}

@Suppress("ReturnCount") // Every path/symlink/confinement guard fails closed before copying.
private fun confinedRegularAuditFile(
    candidate: File,
    allowedRoot: Path,
): File? {
    val lexical = candidate.toPath().toAbsolutePath().normalize()
    if (!lexical.startsWith(allowedRoot)) return null

    var current = allowedRoot
    for (component in allowedRoot.relativize(lexical)) {
        current = current.resolve(component)
        if (Files.isSymbolicLink(current)) return null
    }

    val real = runCatching { lexical.toRealPath(LinkOption.NOFOLLOW_LINKS) }.getOrNull() ?: return null
    if (!real.startsWith(allowedRoot) || !Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) return null
    return real.toFile()
}

internal fun clearPreparedAuditLogShares(cacheDir: File): Boolean {
    val shareRoot = File(cacheDir, AUDIT_LOG_SHARE_DIRECTORY)
    if (!shareRoot.exists() && !Files.isSymbolicLink(shareRoot.toPath())) return false
    val removed =
        if (Files.isSymbolicLink(shareRoot.toPath())) shareRoot.delete() else shareRoot.deleteRecursively()
    check(removed && !shareRoot.exists()) { "Unable to clear audit log exports" }
    return true
}

private fun uniqueAuditFileName(
    preferredName: String,
    usedNames: MutableSet<String>,
): String {
    if (usedNames.add(preferredName)) return preferredName
    val extensionIndex = preferredName.lastIndexOf('.').takeIf { it > 0 } ?: preferredName.length
    val stem = preferredName.substring(0, extensionIndex)
    val extension = preferredName.substring(extensionIndex)
    var suffix = 2
    while (true) {
        val candidate = "$stem-$suffix$extension"
        if (usedNames.add(candidate)) return candidate
        suffix += 1
    }
}

/** Creates a read-only, user-initiated share intent without logging file names or contents. */
internal fun auditLogShareIntent(
    context: Context,
    files: List<File>,
): Intent {
    require(files.isNotEmpty()) { "At least one audit log is required" }
    val uris =
        ArrayList(
            files.map { file ->
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
            },
        )
    val clipData = ClipData.newUri(context.contentResolver, "Audit logs", uris.first())
    clipData.description.extras =
        PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    uris.drop(1).forEach { clipData.addItem(ClipData.Item(it)) }

    return Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "application/octet-stream"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        putExtra(ClipDescription.EXTRA_IS_SENSITIVE, true)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        this.clipData = clipData
    }
}

internal fun auditLogShareChooserIntent(
    context: Context,
    files: List<File>,
    title: String,
): Intent {
    val send = auditLogShareIntent(context, files)
    return Intent.createChooser(send, title).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = send.clipData
    }
}
