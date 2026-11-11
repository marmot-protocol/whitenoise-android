package dev.ipf.whitenoise.android.state

import android.content.Context
import android.net.Uri
import dev.ipf.whitenoise.android.share.TrustedShareSource
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * The diagnostic archives this process staged and shared, so White Noise can be picked as the share
 * target of its own export (#3556) while every other URI on its FileProvider stays rejected.
 *
 * Trust comes only from this process's memory, never from the inbound intent:
 * - [record] runs when [prepareAuditLogArchive] finishes and keeps the archive's resolved path, length
 *   and SHA-256 of the bytes it wrote.
 * - [bind] runs when that archive is offered to the share chooser and keeps the exact URI string
 *   issued for it. The URI carries the session's random UUID directory, so it cannot be guessed.
 * - [resolve] admits an inbound URI only when it equals a bound URI string — so a user-prefixed
 *   authority, another provider root or any other file never matches — and the archive is still the
 *   recorded regular file inside the export directory. The importer then reads the file directly, not
 *   through the provider, and keeps the copy only if its length and digest equal the recorded ones,
 *   so a replaced or rotated archive is rejected.
 *
 * Admission rests on knowing the exact URI string, not on holding a read grant, since the importer
 * reads the file itself and the app always holds access to its own provider. A binding is therefore
 * single-use: the first successful admission retires it, so an app the user shared the archive with
 * cannot replay the URI to stage it again. A failed or cancelled import leaves it bound. Even the one
 * admission reaches only that archive, and the draft still needs the user to pick a chat and press
 * Send. After process death the registry is empty, so a late or recovered intent gets the ordinary
 * recoverable rejection rather than a crash.
 */
internal object StagedDiagnosticExports {
    private class Staged(
        val archive: File,
        val realPath: Path,
        val size: Long,
        val sha256: ByteArray,
    ) {
        var uri: String? = null
    }

    private val lock = Any()
    private val staged = ArrayDeque<Staged>()

    /** Records a completed archive, keeping as many as the export directory retains. */
    fun record(
        archive: File,
        sha256: ByteArray,
    ) {
        val entry = Staged(archive.absoluteFile.normalize(), archive.toPath().toRealPath(), archive.length(), sha256)
        synchronized(lock) {
            staged.addLast(entry)
            while (staged.size > RETAINED_AUDIT_EXPORT_SESSIONS) staged.removeFirst()
        }
    }

    /** Binds the URI issued for a recorded archive; an archive this process did not record stays unbound. */
    fun bind(
        archive: File,
        uri: Uri,
    ) {
        val key = archive.absoluteFile.normalize()
        synchronized(lock) { staged.lastOrNull { it.archive == key }?.uri = uri.toString() }
    }

    /**
     * Returns the verified archive for an exactly bound [uri] on [expectedAuthority], or null when the URI
     * is unknown, stale, or its file is missing, replaced or no longer confined to [shareRoot].
     */
    @Suppress("ReturnCount") // Each provenance and confinement guard fails closed on its own.
    fun resolve(
        uri: Uri,
        expectedAuthority: String,
        shareRoot: File,
    ): TrustedShareSource? {
        if (uri.scheme != "content" || uri.authority != expectedAuthority) return null
        val text = uri.toString()
        val entry = synchronized(lock) { staged.lastOrNull { it.uri == text } } ?: return null
        val lexicalRoot = shareRoot.toPath().toAbsolutePath().normalize()
        val realRoot = runCatching { lexicalRoot.toRealPath() }.getOrNull() ?: return null
        val file = confinedRegularAuditFile(entry.archive, lexicalRoot, realRoot) ?: return null
        if (file.toPath() != entry.realPath || file.length() != entry.size) return null
        return TrustedShareSource(
            name = file.name,
            mime = auditLogExportMimeType(file),
            size = entry.size,
            sha256 = entry.sha256.copyOf(),
            open = { Files.newInputStream(entry.realPath, LinkOption.NOFOLLOW_LINKS) },
            onAdmitted = { synchronized(lock) { if (entry.uri == text) entry.uri = null } },
        )
    }

    /** Forgets every staged archive, as **Clear Diagnostic Logs** removes their files. */
    fun clear() = synchronized(lock) { staged.clear() }
}

/** The production lookup: this package's FileProvider authority and the audit export directory. */
internal fun stagedDiagnosticExportSource(
    context: Context,
    uri: Uri,
): TrustedShareSource? =
    StagedDiagnosticExports.resolve(
        uri,
        "${context.packageName}.fileprovider",
        File(context.cacheDir, AUDIT_LOG_SHARE_DIRECTORY),
    )
