package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import android.net.Uri
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MediaFileTransferControlFfi
import dev.ipf.marmotkit.MessageDraftAttachmentFfi
import dev.ipf.marmotkit.MessageDraftAttachmentFileRequestFfi
import dev.ipf.whitenoise.android.media.editor.DraftPreparedPhoto
import dev.ipf.whitenoise.android.media.editor.MessageDraftMutationResult
import dev.ipf.whitenoise.android.media.editor.MessageDraftRepository
import dev.ipf.whitenoise.android.media.editor.RevisionedMessageDraft
import dev.ipf.whitenoise.android.media.editor.editorDigest
import dev.ipf.whitenoise.android.media.editor.hasInlineBody
import dev.ipf.whitenoise.android.state.PendingAttachment
import dev.ipf.whitenoise.android.ui.conversation.media.closeQuietly
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** The most bytes one read of a stored draft file asks MarmotKit for, its own per-call ceiling. */
internal const val DRAFT_FILE_READ_CHUNK_BYTES = 1024 * 1024

/**
 * Keeps a pick too large for memory in the native draft as a file-backed attachment, so it survives leaving the
 * chat and a process restart. [pending] carries the private, metadata-free copy the reader made; MarmotKit copies
 * it into the account's encrypted media store and that private copy is deleted whatever the outcome. Returns the
 * draft's body-less entry, or null when the draft refused the file (no room, too large, or the draft moved).
 */
internal suspend fun stageFileBackedAttachment(
    drafts: MessageDraftRepository,
    target: DraftAttachmentTarget,
    pending: PendingAttachment,
    newControl: () -> MediaFileTransferControlFfi = ::MediaFileTransferControlFfi,
): DraftPreparedPhoto? {
    val source = requireNotNull(pending.sourceFile) { "only a pick read into a file is staged as a file" }
    try {
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
 * Gives one restored native attachment a shelf file. Inline bytes are written out; a file-backed body is streamed
 * from MarmotKit under [read]'s revision.
 */
internal suspend fun materializeRestoredAttachment(
    context: Context,
    drafts: MessageDraftRepository,
    accountRef: String,
    read: RevisionedMessageDraft,
    attachment: MessageDraftAttachmentFfi,
): Uri? =
    if (attachment.hasInlineBody()) {
        materializeDraftAttachment(context, attachment)
    } else {
        materializeFileBackedDraftAttachment(context, drafts, accountRef, read, attachment)
    }

/**
 * Writes a file-backed draft attachment's body to the composer's presentation file for [attachment], streaming it
 * from MarmotKit's media store in bounded chunks so the shelf can show it and a send can read it like a pick. A
 * file already there with the stored size is reused, so reopening a chat does not copy a large file again. Returns
 * null when the body cannot be read under [base]'s revision or does not fit on the device.
 */
internal suspend fun materializeFileBackedDraftAttachment(
    context: Context,
    drafts: MessageDraftRepository,
    accountRef: String,
    base: RevisionedMessageDraft,
    attachment: MessageDraftAttachmentFfi,
): Uri? {
    val revision = base.revision
    val size = base.fileBacked[attachment.id]?.plaintextSize?.toLong()
    val file = draftPresentationFile(context, attachment)
    val ready =
        when {
            revision == null || size == null || file == null -> false
            file.isFile && file.length() == size -> true
            else ->
                replaceWithDraftFileBody(file, size) { offset ->
                    drafts.files.readChunk(accountRef, revision, attachment.id, offset, DRAFT_FILE_READ_CHUNK_BYTES)
                }
        }
    return file?.takeIf { ready }?.let { draftPresentationUri(context, it, attachment) }
}

/**
 * Streams a stored body into a sibling partial file and moves it over [file] only once all [size] bytes arrived,
 * so a shelf never shows a truncated copy. Reports whether [file] now holds the body.
 */
@Suppress("SwallowedException") // A disk or native read failure only means the shelf skips this attachment.
private suspend fun replaceWithDraftFileBody(
    file: File,
    size: Long,
    read: suspend (offset: Long) -> ByteArray?,
): Boolean {
    val partial = File(file.parentFile, "${file.name}.partial")
    val copied =
        try {
            copyDraftFileBody(partial, read) == size
        } catch (unreadable: IOException) {
            false
        } catch (refused: MarmotKitException) {
            false
        }
    val moved = copied && partial.renameTo(file)
    if (!moved) partial.delete()
    return moved
}

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
