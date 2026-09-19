package dev.ipf.whitenoise.android.ui.settings

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * Streams a staged diagnostic archive into a user-selected document.
 *
 * The copy runs on IO and streams rather than reading the archive into memory, because an export
 * grows with however much history the device has recorded. An incomplete destination is removed, or
 * truncated where the provider does not support deletion, so a failed save never leaves a file that
 * looks like a complete export. No path, name or destination URI reaches a log or an error report.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun saveAuditLogArchive(
    archive: File,
    openOutput: () -> OutputStream?,
    discardOutput: () -> Unit,
) = withContext(Dispatchers.IO) {
    try {
        val output = openOutput() ?: throw IOException("The document provider did not open an output stream")
        output.use { destination ->
            archive.inputStream().buffered().use { source -> source.copyTo(destination) }
            destination.flush()
        }
    } catch (failure: Throwable) {
        // Cleanup runs for its effect on the destination; a cleanup that itself fails is attached
        // rather than replacing the reason the save failed.
        try {
            discardOutput()
        } catch (cleanupFailure: Throwable) {
            failure.addSuppressed(cleanupFailure)
        }
        throw failure
    }
}

/** Removes an incomplete document, or truncates it when the provider does not support deletion. */
internal fun discardAuditLogArchiveDocument(
    resolver: ContentResolver,
    uri: Uri,
) {
    val deleted =
        try {
            DocumentsContract.deleteDocument(resolver, uri)
        } catch (_: Exception) {
            false
        }
    if (!deleted) {
        val output =
            resolver.openOutputStream(uri, "wt")
                ?: throw IOException("Could not clear the incomplete export")
        output.close()
    }
}
