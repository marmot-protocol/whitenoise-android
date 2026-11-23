package dev.ipf.whitenoise.android.ui.conversation.media

import dev.ipf.whitenoise.android.media.pdf.PdfMetadataResult
import dev.ipf.whitenoise.android.media.pdf.PdfMetadataScrubber
import dev.ipf.whitenoise.android.state.PendingAttachment
import dev.ipf.whitenoise.android.ui.conversation.DocumentReadFailure
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

// A staged snapshot is owner-read-only once staged, so the scrub lends the owner write bit for the rewrite only.
private const val SNAPSHOT_READ_ONLY = "r--------"
private const val SNAPSHOT_SCRUBBING = "rw-------"

/**
 * Removes private metadata from freshly read Files [bytes] the caller owns: a video's as [documentVideoAction]
 * decides, then a PDF's (#3377). Returns whether they may be sent. A PDF whose metadata must stay, because it is
 * signed, encrypted or cannot be rewritten exactly, keeps its original bytes; the send asks before it goes.
 */
internal fun removeDocumentMetadataInPlace(
    mediaType: String,
    bytes: ByteArray,
): Boolean = removeDocumentVideoMetadataInPlace(mediaType, bytes).also { if (it) PdfMetadataScrubber.scrub(bytes) }

/** Removes private metadata from this staged Files snapshot in place, as [removeDocumentMetadataInPlace] does. */
internal fun StagedUploadSource.removeDocumentMetadata(mediaType: String): Boolean =
    removeDocumentVideoMetadata(mediaType).also { if (it) scrubPdfMetadata() }

/**
 * Removes PDF metadata from this staged snapshot in place. The size is unchanged, so the budget that admitted the
 * snapshot stays exact, and the snapshot is always returned to owner-read-only.
 */
private fun StagedUploadSource.scrubPdfMetadata() {
    val path = file.toPath()
    try {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(SNAPSHOT_SCRUBBING))
        PdfMetadataScrubber.scrub(file)
    } catch (_: IOException) {
        // Unchanged: a PDF that still carries metadata is caught by the send's check.
    } finally {
        runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(SNAPSHOT_READ_ONLY)) }
    }
}

/**
 * Re-checks prepared Files drafts before a send reuses them: videos as [removePreparedDocumentVideoMetadata]
 * does, then PDFs, including drafts an older app version saved before PDFs were cleaned. A draft's bytes are
 * shared with its native draft, so a PDF is cleaned on a copy. Call off Main.
 */
internal fun <K> removePreparedDocumentMetadata(prepared: Map<K, PendingAttachment>): PreparedVideoCheck<K> {
    val checked = removePreparedDocumentVideoMetadata(prepared)
    return checked.copy(ready = checked.ready.mapValues { (_, attachment) -> attachment.withoutPdfMetadata() })
}

private fun PendingAttachment.withoutPdfMetadata(): PendingAttachment {
    val clean = if (sourceFile != null) null else PdfMetadataScrubber.cleanedCopyOrNull(plaintextBytes)
    return if (clean == null || clean === plaintextBytes) this else copy(plaintextBytes = clean)
}

/** What this attachment's content says about PDF metadata. Reads its staged file when it has one. Call off Main. */
internal fun PendingAttachment.pdfMetadata(): PdfMetadataResult =
    sourceFile?.let { PdfMetadataScrubber.inspect(it.file) } ?: PdfMetadataScrubber.inspect(plaintextBytes)

/**
 * Drops the attachments from [from] on that are PDFs still carrying metadata, adding
 * [DocumentReadFailure.PDF_METADATA] for each: one whose removal failed, and one that keeps it without the person's
 * choice to send it as it is. Returns the in-memory bytes it dropped, so the caller can give them back to its budget.
 * Call off Main.
 */
internal fun MutableList<PendingAttachment>.dropUnconfirmedPdfMetadata(
    from: Int,
    failures: MutableSet<DocumentReadFailure>,
): Long {
    var dropped = 0L
    val iterator = listIterator(from)
    while (iterator.hasNext()) {
        val attachment = iterator.next()
        val metadata = attachment.pdfMetadata()
        val unconfirmed = metadata is PdfMetadataResult.Refused && !attachment.keepsPdfMetadata
        if (metadata is PdfMetadataResult.Removable || unconfirmed) {
            iterator.remove()
            failures += DocumentReadFailure.PDF_METADATA
            dropped += attachment.inMemoryBytes?.size ?: 0
        }
    }
    return dropped
}
