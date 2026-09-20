package dev.ipf.whitenoise.android.state

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val AUDIT_LOG_SHARE_DIRECTORY = "audit_logs"
private val unsafeAuditFileName = Regex("[^A-Za-z0-9._-]")

/**
 * How many staged export sessions survive a new export, including the one being written.
 *
 * A shared archive is handed to the recipient as a content URI, not as bytes, so the file must
 * still exist when that app gets round to opening it. Deleting every prior session on the next
 * export — which is what the export did before — could pull the archive out from under a recipient
 * that had not read it yet. Retaining the immediately previous session covers that hand-off while
 * still bounding how much forensic data sits in cache; **Clear Diagnostic Logs** removes all of it.
 */
internal const val RETAINED_AUDIT_EXPORT_SESSIONS = 2

/** Name of the single archive a manual export produces. Carries no account, group or device detail. */
internal const val AUDIT_LOG_ARCHIVE_NAME = "white-noise-diagnostic-logs.zip"

/** The archive's media type, so a recipient sees one archive rather than opaque attachments. */
internal const val AUDIT_LOG_ARCHIVE_MIME_TYPE = "application/zip"

/**
 * Stages engine-owned audit logs as one archive in the app cache for an explicit export.
 *
 * Symlinks and non-regular files are rejected so a compromised path cannot make the FileProvider
 * expose an unrelated file, and entry names are sanitised to safe, unique, relative names so an
 * archive can never carry an absolute path or a parent traversal to whatever opens it.
 *
 * The export is complete or it fails: a source that cannot be confined aborts the whole archive
 * rather than yielding a partial one that looks complete. The staging directory is replaced on each
 * export so previously exported forensic data does not accumulate in cache.
 */
@Suppress("TooGenericExceptionCaught") // Any failure must clear this export's partial archive before rethrowing.
internal fun prepareAuditLogArchive(
    cacheDir: File,
    allowedSourceRoot: File,
    sourcePaths: List<String>,
): File {
    require(sourcePaths.isNotEmpty()) { "At least one audit log is required" }
    val shareRoot = File(cacheDir, AUDIT_LOG_SHARE_DIRECTORY)
    check(shareRoot.mkdirs() || shareRoot.isDirectory) { "Unable to prepare audit log export" }
    pruneAuditLogShareSessions(shareRoot, RETAINED_AUDIT_EXPORT_SESSIONS - 1)
    val shareDirectory = File(shareRoot, UUID.randomUUID().toString())
    check(shareDirectory.mkdir()) { "Unable to prepare audit log export" }

    val allowedLexicalRoot = allowedSourceRoot.toPath().toAbsolutePath().normalize()
    val allowedRealRoot = allowedLexicalRoot.toRealPath()
    val archive = File(shareDirectory, AUDIT_LOG_ARCHIVE_NAME)
    try {
        val usedNames = mutableSetOf<String>()
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            sourcePaths.forEach { sourcePath ->
                val source =
                    confinedRegularAuditFile(
                        candidate = File(sourcePath),
                        allowedLexicalRoot = allowedLexicalRoot,
                        allowedRealRoot = allowedRealRoot,
                    ) ?: throw IOException("An audit log could not be included")
                zip.putNextEntry(ZipEntry(uniqueAuditFileName(safeAuditEntryName(source.name), usedNames)))
                source.inputStream().buffered().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    } catch (failure: Throwable) {
        // A partial archive must never be presented as a complete export, so only this export's own
        // directory goes. The retained previous session stays: it may still back a share URI whose
        // recipient has not opened it yet, and this export failing is no reason to break that one.
        runCatching { shareDirectory.deleteRecursively() }
        throw failure
    }
    archive.setWritable(false, false)
    return archive
}

/** A safe, relative entry name: no separators, traversal or provider-significant characters. */
private fun safeAuditEntryName(sourceName: String): String =
    sourceName
        .replace(unsafeAuditFileName, "_")
        .trim('.', '_')
        .ifBlank { "audit-log.jsonl" }

@Suppress("ReturnCount") // Every path/symlink/confinement guard fails closed before copying.
private fun confinedRegularAuditFile(
    candidate: File,
    allowedLexicalRoot: Path,
    allowedRealRoot: Path,
): File? {
    val lexical = candidate.toPath().toAbsolutePath().normalize()
    val containmentRoot =
        when {
            lexical.startsWith(allowedLexicalRoot) -> allowedLexicalRoot
            lexical.startsWith(allowedRealRoot) -> allowedRealRoot
            else -> return null
        }

    var current = containmentRoot
    for (component in containmentRoot.relativize(lexical)) {
        current = current.resolve(component)
        if (Files.isSymbolicLink(current)) return null
    }

    // Every descendant component, including the file itself, was checked above.
    // Resolve only now so an allowed-root alias such as macOS /var -> /private/var
    // is compared in the same namespace as [allowedRealRoot].
    val real = runCatching { lexical.toRealPath() }.getOrNull() ?: return null
    if (!real.startsWith(allowedRealRoot) || !Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) return null
    return real.toFile()
}

/**
 * Keeps the [retain] newest session directories and removes the rest, plus any stray file directly
 * under the share root. Symlinked entries are unlinked rather than followed.
 */
private fun pruneAuditLogShareSessions(
    shareRoot: File,
    retain: Int,
) {
    val entries = shareRoot.listFiles().orEmpty()
    entries.filterNot { it.isDirectory }.forEach { it.delete() }
    entries
        .filter { it.isDirectory && !Files.isSymbolicLink(it.toPath()) }
        // Newest first. Names break a tie so two sessions created inside one filesystem
        // timestamp tick still prune in a stable order rather than arbitrarily.
        .sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
        .drop(retain.coerceAtLeast(0))
        .forEach { it.deleteRecursively() }
    entries.filter { Files.isSymbolicLink(it.toPath()) }.forEach { it.delete() }
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

/** Creates a read-only, user-initiated share of the single archive, logging no name or content. */
internal fun auditLogShareIntent(
    context: Context,
    archive: File,
): Intent {
    val uri =
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            archive,
        )
    val clipData = ClipData.newUri(context.contentResolver, "Diagnostic logs", uri)
    clipData.description.extras =
        PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }

    return Intent(Intent.ACTION_SEND).apply {
        type = AUDIT_LOG_ARCHIVE_MIME_TYPE
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(ClipDescription.EXTRA_IS_SENSITIVE, true)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        this.clipData = clipData
    }
}

internal fun auditLogShareChooserIntent(
    context: Context,
    archive: File,
    title: String,
): Intent {
    val send = auditLogShareIntent(context, archive)
    return Intent.createChooser(send, title).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = send.clipData
    }
}
