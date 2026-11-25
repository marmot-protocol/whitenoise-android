package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.marmotkit.MediaFileTransferControlFfi
import dev.ipf.marmotkit.MessageDraftAttachmentFileRequestFfi
import dev.ipf.whitenoise.android.media.editor.DraftPreparedPhoto
import dev.ipf.whitenoise.android.media.editor.MessageDraftMutationResult
import dev.ipf.whitenoise.android.media.editor.MessageDraftRepository
import dev.ipf.whitenoise.android.media.editor.RevisionedMessageDraft
import dev.ipf.whitenoise.android.media.editor.editorDigest
import dev.ipf.whitenoise.android.media.editor.hasInlineBody
import dev.ipf.whitenoise.android.state.PendingAttachment
import dev.ipf.whitenoise.android.state.hasVerifiedDraftPdfMetadata
import dev.ipf.whitenoise.android.ui.conversation.media.FileBackedSendStaging
import dev.ipf.whitenoise.android.ui.conversation.media.StagedUploadSource
import dev.ipf.whitenoise.android.ui.conversation.media.closeQuietly
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Runs one draft read, handing [read] a staging that records each private copy it makes. Every copy except the
 * returned attachment's own file is closed, so a read cancelled or failing after its copy finished leaves none behind;
 * the caller owns the returned attachment's file.
 */
internal suspend fun ownedDraftRead(read: suspend (FileBackedSendStaging) -> PendingAttachment?): PendingAttachment? {
    val copies = CopyOnWriteArrayList<StagedUploadSource>()
    var delivered: PendingAttachment? = null
    try {
        delivered = read(FileBackedSendStaging { copies += it })
        return delivered
    } finally {
        val unowned = copies.filter { it !== delivered?.sourceFile }
        if (unowned.isNotEmpty()) withContext(NonCancellable + Dispatchers.IO) { unowned.forEach { it.closeQuietly() } }
    }
}

/** The most bytes one read of a stored draft file asks MarmotKit for, its own per-call ceiling. */
internal const val DRAFT_FILE_READ_CHUNK_BYTES = 1024 * 1024

/**
 * Keeps a pick too large for memory in the native draft as a file-backed attachment, so it survives leaving the
 * chat and a process restart. [pending] carries the private, metadata-free copy the reader made; MarmotKit copies
 * it into the account's encrypted media store and that private copy is deleted whatever the outcome. Returns the
 * draft's body-less entry, or null when the file requires PDF consent or the draft cannot keep it.
 */
internal suspend fun stageFileBackedAttachment(
    drafts: MessageDraftRepository,
    target: DraftAttachmentTarget,
    pending: PendingAttachment,
    newControl: () -> MediaFileTransferControlFfi = ::MediaFileTransferControlFfi,
): DraftPreparedPhoto? {
    val source = requireNotNull(pending.sourceFile) { "only a pick read into a file is staged as a file" }
    try {
        // A refused PDF can still be sent from its picker through the ordinary consent path.
        if (!withContext(Dispatchers.IO) { source.file.hasVerifiedDraftPdfMetadata() }) return null
        val request =
            MessageDraftAttachmentFileRequestFfi(
                id = target.attachmentId,
                sourcePath = source.file.absolutePath,
                expectedSize = source.byteCount.toULong(),
                fileName = pending.fileName,
                mediaType = pending.mediaType,
                dim = pending.dim,
                thumbhash = pending.thumbhash,
                durationSeconds = null,
                waveformSamples = emptyList(),
            )
        val admission = drafts.files.add(target.accountRef, target.groupIdHex, request, newControl())
        return preparedAfterStaging(drafts, target, admission, source.byteCount)
    } finally {
        withContext(NonCancellable + Dispatchers.IO) { source.closeQuietly() }
    }
}

/**
 * The draft entry a staging left: the fresh copy of [stagedBytes], or what an earlier staging of the same id left,
 * read again for its stored size. Null when the draft refused the file.
 */
private suspend fun preparedAfterStaging(
    drafts: MessageDraftRepository,
    target: DraftAttachmentTarget,
    admission: MessageDraftMutationResult,
    stagedBytes: Long,
): DraftPreparedPhoto? {
    val duplicate = admission == MessageDraftMutationResult.DuplicateAttachment
    val read =
        when (admission) {
            is MessageDraftMutationResult.Success -> RevisionedMessageDraft(null, admission.draft)
            MessageDraftMutationResult.DuplicateAttachment ->
                drafts.files.revisioned(target.accountRef, target.groupIdHex).getOrNull()
            else -> null
        }
    val storedBytes =
        if (duplicate) {
            read
                ?.fileBacked
                ?.get(target.attachmentId)
                ?.plaintextSize
                ?.toLong() ?: 0L
        } else {
            stagedBytes
        }
    return read?.draft?.mediaAttachments?.firstOrNull { it.id == target.attachmentId }?.let { held ->
        DraftPreparedPhoto(
            held,
            held.editorDigest(),
            restoredFromNative = duplicate && !held.hasInlineBody(),
            storedBytes = if (held.hasInlineBody()) 0L else storedBytes,
        )
    }
}

/**
 * Gives each body-less entry the plaintext size of the file the draft stores for it, from one revisioned read, so a
 * send can upload it from the draft by id.
 */
internal fun <K> Map<K, DraftPreparedPhoto>.withStoredSizes(read: RevisionedMessageDraft): Map<K, DraftPreparedPhoto> =
    mapValues { (_, photo) ->
        val stored = read.fileBacked[photo.attachment.id]?.takeIf { !photo.attachment.hasInlineBody() }
        stored?.let { photo.copy(storedBytes = it.plaintextSize.toLong()) } ?: photo
    }

/**
 * The prepared items a collection send may reuse. A stored draft file is left out, so the collection reads its
 * shelf file: each chunk is admitted by an upload that sends, and MarmotKit admits stored files under a token only
 * through the whole draft.
 */
internal fun <K> Map<K, PendingAttachment>.withoutStoredFiles(): Map<K, PendingAttachment> =
    filterValues { attachment ->
        attachment.draftFile == null
    }

/** The account, chat and stable attachment id one staged pick is written under. */
internal data class DraftAttachmentTarget(
    val accountRef: String,
    val groupIdHex: String,
    val attachmentId: String,
)

/**
 * Appends chunks from [read] to [target] until an empty chunk marks the end, checking for cancellation between
 * chunks so a closed composer stops a long restore. Returns the bytes written, or -1 when [read] reports the
 * attachment gone.
 */
internal suspend fun copyDraftFileBody(
    target: File,
    read: suspend (offset: Long) -> ByteArray?,
): Long =
    target.outputStream().use { output ->
        var offset = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val chunk = read(offset) ?: return@use -1L
            if (chunk.isEmpty()) break
            output.write(chunk)
            offset += chunk.size
        }
        offset
    }
