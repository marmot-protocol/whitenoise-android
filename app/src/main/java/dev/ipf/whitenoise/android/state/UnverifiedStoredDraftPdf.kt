package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MediaFileUploadRequestFfi
import dev.ipf.marmotkit.MessageDraftRevisionFfi
import dev.ipf.whitenoise.android.media.pdf.ArrayPdfBytes
import dev.ipf.whitenoise.android.media.pdf.PdfMetadataResult
import dev.ipf.whitenoise.android.media.pdf.PdfMetadataScrubber
import dev.ipf.whitenoise.android.media.pdf.looksLikePdf
import dev.ipf.whitenoise.android.ui.conversation.DRAFT_FILE_READ_CHUNK_BYTES
import dev.ipf.whitenoise.android.ui.conversation.media.prepareStagingDirectory
import dev.ipf.whitenoise.android.ui.conversation.replaceWithDraftFileBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A stored draft does not carry consent to send private PDF metadata. */
internal class UnverifiedStoredDraftPdf : IllegalStateException("Stored PDF metadata is unverified")

/** Whether the exact file needs no PDF consent, regardless of its filename or declared media type. Call off Main. */
internal fun File.hasVerifiedDraftPdfMetadata(): Boolean =
    when (PdfMetadataScrubber.inspect(this)) {
        PdfMetadataResult.NotPdf, PdfMetadataResult.Clean -> true
        else -> false
    }

/** Checks the native bytes under the same revision used for upload, never the picker URI or a shelf cache. */
internal suspend fun MarmotInterface.verifySelectedDraftPdfMetadata(
    account: String,
    revision: MessageDraftRevisionFfi,
    attachments: List<PendingAttachment>,
    directory: File,
) {
    for (attachment in attachments) {
        val file = checkNotNull(attachment.draftFile)
        val headerSize = minOf(file.byteCount, 1024L).toInt()
        val header =
            readMessageDraftAttachment(account, revision, file.attachmentId, 0uL, headerSize.toUInt())
                ?: throw UnverifiedStoredDraftPdf()
        if (header.size != headerSize) throw UnverifiedStoredDraftPdf()
        if (looksLikePdf(ArrayPdfBytes(header))) verifyNativePdf(account, revision, file, directory)
    }
}

/** Reads the exact selected file in bounded chunks and owns the private plaintext copy until it is deleted. */
private suspend fun MarmotInterface.verifyNativePdf(
    account: String,
    revision: MessageDraftRevisionFfi,
    file: DraftStoredFile,
    directory: File,
) = withContext(Dispatchers.IO) {
    prepareStagingDirectory(directory)
    val snapshot = File.createTempFile("draft-pdf-", ".pdf", directory)
    try {
        val complete =
            replaceWithDraftFileBody(snapshot, file.byteCount) { offset ->
                readMessageDraftAttachment(
                    account,
                    revision,
                    file.attachmentId,
                    offset.toULong(),
                    DRAFT_FILE_READ_CHUNK_BYTES.toUInt(),
                )
            }
        if (!complete || !snapshot.hasVerifiedDraftPdfMetadata()) throw UnverifiedStoredDraftPdf()
    } finally {
        snapshot.delete()
    }
}

/** Rechecks the exact retry copy before upload, including copies made by an earlier failing attempt. */
internal suspend fun verifyCopiedDraftPdfMetadata(request: MediaFileUploadRequestFfi) =
    withContext(Dispatchers.IO) {
        for (attachment in request.attachments) {
            val file = File(attachment.sourcePath)
            if (file.length().toULong() != attachment.expectedSize || !file.hasVerifiedDraftPdfMetadata()) {
                throw UnverifiedStoredDraftPdf()
            }
        }
    }
