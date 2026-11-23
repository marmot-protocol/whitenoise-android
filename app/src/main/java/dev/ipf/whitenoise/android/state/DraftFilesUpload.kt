package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaFileTransferControlFfi
import dev.ipf.marmotkit.MediaFileUploadRequestFfi
import dev.ipf.marmotkit.MessageDraftAttachmentInputFfi
import dev.ipf.marmotkit.MessageDraftRevisionFfi
import dev.ipf.marmotkit.SelectedMessageDraftAttachmentFfi
import dev.ipf.marmotkit.SelectedMessageDraftContentFfi
import dev.ipf.marmotkit.SendSummaryFfi

/** How many times consuming a sent send's leftovers reads a draft again after another save moved it. */
private const val MAX_SENT_DRAFT_CONSUME_ATTEMPTS = 3

/** A send whose attachments are files the composer's draft stores, and the caption it carries. */
internal data class DraftFilesUpload(
    val attachments: List<PendingAttachment>,
    val caption: String?,
)

/**
 * Uploads a send of stored draft files from the copies the draft holds, by id, so nothing is read from the pick again.
 * The draft must still hold each of them as a stored file of the same size, name and type, or the send fails before
 * anything uploads; anything newer the draft holds, text typed or attachments added since, is left alone and nothing
 * is saved. The returned revision is the draft the send may consume on admission, and only when it held exactly this
 * send: these files, in order, under the send's caption and with no reply.
 */
internal suspend fun MarmotInterface.uploadComposerDraftFilesWithToken(
    account: String,
    group: String,
    upload: DraftFilesUpload,
    control: MediaFileTransferControlFfi,
    token: String,
): DurableComposerMediaUpload {
    recoveredDraftFilesSend(account, group, upload, token)?.let { return it }
    val selected = selectedMessageDraft(account, group)
    val draft = selected.draft
    val ids =
        checkNotNull(draft?.let { heldDraftFileIds(it, upload.attachments) }) {
            "the draft no longer holds this send's stored files"
        }
    return DurableComposerMediaUpload(
        upload = uploadMessageDraftAttachments(account, selected.revision, ids, null, control),
        acceptance = null,
        recoveredWithoutUpload = false,
        draftRevision = selected.revision.takeIf { draftIsExactly(draft, upload) },
    )
}

/**
 * Uploads the private copies a failed send of stored draft files took of them, through [request], so a retry needs
 * nothing the draft still holds. Admission stays with [sendComposerDraftFiles], as for an upload from the draft.
 */
internal suspend fun MarmotInterface.uploadOwnedDraftCopiesWithToken(
    account: String,
    group: String,
    upload: DraftFilesUpload,
    request: MediaFileUploadRequestFfi,
    control: MediaFileTransferControlFfi,
    token: String,
): DurableComposerMediaUpload {
    require(!request.send) { "owned draft copies upload before admission" }
    recoveredDraftFilesSend(account, group, upload, token)?.let { return it }
    return DurableComposerMediaUpload(
        upload = uploadMediaFiles(account, group, request, control),
        acceptance = null,
        recoveredWithoutUpload = false,
    )
}

/** A send MDK already owns under [token], with what it left in the draft consumed, or null when there is none. */
private fun MarmotInterface.recoveredDraftFilesSend(
    account: String,
    group: String,
    upload: DraftFilesUpload,
    token: String,
): DurableComposerMediaUpload? =
    recoveredLocalSend(account, group, token)?.let { recovered ->
        consumeSentDraftFiles(account, group, upload)
        recoveredComposerUpload(recovered)
    }

/**
 * Publishes a retained send. Stored draft files are admitted through [sendComposerDraftFiles], consuming the draft
 * they uploaded from only while it still says exactly what they send; every other send goes through
 * [sendComposerMedia] as before.
 */
internal suspend fun MarmotInterface.publishRetainedMedia(
    account: String,
    group: String,
    references: List<MediaAttachmentReferenceFfi>,
    retained: RetainedMediaUpload,
    token: String,
): SendSummaryFfi =
    if (retained.isDraftStored) {
        val upload = DraftFilesUpload(retained.attachments, retained.caption)
        val admission = DraftFilesAdmission(retained.draftUploadRevision, token)
        sendComposerDraftFiles(account, group, references, upload, admission)
    } else {
        sendComposerMedia(account, group, references, retained.caption, token, replyRevision = retained.replyRevision)
    }

/** The draft revision a send of stored files may consume, if any, and the client token it is admitted with. */
internal data class DraftFilesAdmission(
    val consuming: MessageDraftRevisionFfi?,
    val token: String,
)

/**
 * Admits uploaded stored files as the send's own snapshot, its caption and references, under its token. MarmotKit
 * consumes the draft [DraftFilesAdmission.consuming] names only while it is unchanged; a draft saved since, such as
 * text typed during a slow upload, is never overwritten or consumed. What the send left in such a draft is then
 * removed by [consumeSentDraftFiles].
 */
internal suspend fun MarmotInterface.sendComposerDraftFiles(
    account: String,
    group: String,
    references: List<MediaAttachmentReferenceFfi>,
    upload: DraftFilesUpload,
    admission: DraftFilesAdmission,
): SendSummaryFfi {
    val summary =
        admitLocalSend(account, group, admission.token) {
            sendPreparedMediaWithClientToken(
                account,
                group,
                references,
                upload.caption,
                admission.token,
                admission.consuming,
            )
        }
    consumeSentDraftFiles(account, group, upload)
    return summary
}

/**
 * Removes what an admitted send of stored files left in the selected draft: each of its files the draft still holds,
 * and the caption while the draft's text is still exactly it. Text typed since, a reply and attachments added since
 * stay. A draft left empty is cleared. Best effort: the send is already admitted, so a failure keeps the draft as is.
 */
@Suppress("ReturnCount", "SwallowedException") // Admitted either way: a draft that cannot be tidied stays as it is.
internal fun MarmotInterface.consumeSentDraftFiles(
    account: String,
    group: String,
    upload: DraftFilesUpload,
) {
    repeat(MAX_SENT_DRAFT_CONSUME_ATTEMPTS) {
        val selected = selectedDraftOrNull(account, group) ?: return
        val draft = selected.draft ?: return
        val sent = draft.mediaAttachments.filter { sentFileOf(it, upload.attachments) }.mapTo(HashSet()) { it.id }
        if (sent.isEmpty()) return
        val kept = draft.mediaAttachments.filterNot { it.id in sent }
        val text = draft.content.takeUnless { it.trim() == upload.caption.orEmpty().trim() }.orEmpty()
        try {
            if (kept.isEmpty() && text.isBlank() && draft.replyToMessageIdHex == null) {
                clearMessageDraftIfRevision(account, selected.revision)
            } else {
                val inputs =
                    kept.map { MessageDraftAttachmentInputFfi.Retained(it.id) } +
                        sent.map { MessageDraftAttachmentInputFfi.Removed(it) }
                saveMessageDraftInputsIfRevision(account, selected.revision, text, draft.replyToMessageIdHex, inputs)
            }
            return
        } catch (moved: MarmotKitException.MessageDraftRevisionConflict) {
            // Another save won the race: read the draft it left and try again.
        } catch (refused: MarmotKitException) {
            return
        }
    }
}

/** Whether [descriptor] is one of [attachments]' stored files: the same id, still stored, at the same size. */
private fun sentFileOf(
    descriptor: SelectedMessageDraftAttachmentFfi,
    attachments: List<PendingAttachment>,
): Boolean =
    attachments.any { attachment ->
        val stored = attachment.draftFile
        stored != null && isStoredFile(descriptor, stored)
    }

/** Whether [descriptor] is [stored]: the same id, still a stored file, of the same size. */
internal fun isStoredFile(
    descriptor: SelectedMessageDraftAttachmentFfi,
    stored: DraftStoredFile,
): Boolean =
    descriptor.fileBacked &&
        stored.attachmentId == descriptor.id &&
        stored.byteCount == descriptor.plaintextSize.toLong()

/**
 * The draft ids of [attachments] when the draft holds each of them as a stored file of the same size, name and type,
 * whatever else it holds; otherwise null. An inline attachment under a stored file's id never matches.
 */
internal fun heldDraftFileIds(
    draft: SelectedMessageDraftContentFfi,
    attachments: List<PendingAttachment>,
): List<String>? {
    val ids =
        attachments.mapNotNull { attachment ->
            val stored = attachment.draftFile
            draft.mediaAttachments
                .firstOrNull { descriptor ->
                    stored != null &&
                        isStoredFile(descriptor, stored) &&
                        descriptor.fileName == attachment.fileName &&
                        descriptor.mediaType == attachment.mediaType
                }?.id
        }
    return ids.takeIf { it.size == attachments.size }
}

/**
 * Whether [draft] says exactly what [upload] sends, so MarmotKit may consume it on admission: these stored files and
 * nothing else, in order, under the caption, with no reply.
 */
internal fun draftIsExactly(
    draft: SelectedMessageDraftContentFfi?,
    upload: DraftFilesUpload,
): Boolean =
    draft != null &&
        draft.replyToMessageIdHex == null &&
        draft.content.trim() == upload.caption.orEmpty().trim() &&
        heldDraftFileIds(draft, upload.attachments) == draft.mediaAttachments.map { it.id }
