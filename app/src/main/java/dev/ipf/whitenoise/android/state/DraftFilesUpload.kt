package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaFileTransferControlFfi
import dev.ipf.marmotkit.MessageDraftRevisionFfi
import dev.ipf.marmotkit.SelectedMessageDraftContentFfi
import dev.ipf.marmotkit.SendSummaryFfi

/** A send whose attachments are files the composer's draft stores, and the caption it carries. */
internal data class DraftFilesUpload(
    val attachments: List<PendingAttachment>,
    val caption: String?,
)

/**
 * Uploads a send of stored draft files from the copies the draft holds, by id, so nothing is read from the pick again.
 * Admission is left to the draft send, as for a matching draft in [uploadOrAdmitComposerMediaFilesWithToken]: MarmotKit
 * admits uploaded references under a client token only through the draft, so the draft must hold exactly these files,
 * in order and without a reply, or the send fails before anything uploads. The caption is saved first, and the
 * revision the files upload under is returned, so the draft send admits the references under that same revision.
 */
internal suspend fun MarmotInterface.uploadComposerDraftFilesWithToken(
    account: String,
    group: String,
    upload: DraftFilesUpload,
    control: MediaFileTransferControlFfi,
    token: String,
): DurableComposerMediaUpload {
    recoveredLocalSend(account, group, token)?.let { recovered -> return recoveredComposerUpload(recovered) }
    val (revision, ids) =
        checkNotNull(draftFilesRevision(account, group, upload)) {
            "the draft no longer holds exactly this send's stored files"
        }
    return DurableComposerMediaUpload(
        upload = uploadMessageDraftAttachments(account, revision, ids, null, control),
        acceptance = null,
        recoveredWithoutUpload = false,
        draftRevision = revision,
    )
}

/**
 * Publishes a retained send. Stored draft files are admitted through [sendComposerDraftFiles] under the revision they
 * uploaded from; every other send goes through [sendComposerMedia] as before.
 */
internal suspend fun MarmotInterface.publishRetainedMedia(
    account: String,
    group: String,
    references: List<MediaAttachmentReferenceFfi>,
    retained: RetainedMediaUpload,
    token: String,
): SendSummaryFfi =
    if (retained.isDraftStored) {
        val revision =
            checkNotNull(retained.draftUploadRevision) { "stored draft files have no upload revision to admit under" }
        val upload = DraftFilesUpload(retained.attachments, retained.caption)
        sendComposerDraftFiles(account, group, references, upload, DraftFilesAdmission(revision, token))
    } else {
        sendComposerMedia(account, group, references, retained.caption, token, replyRevision = retained.replyRevision)
    }

/** The draft revision stored files uploaded under, and the client token their send is admitted with. */
internal data class DraftFilesAdmission(
    val revision: MessageDraftRevisionFfi,
    val token: String,
)

/**
 * Admits uploaded stored files through the draft under the revision they uploaded from. If a save after the upload
 * moved the draft, the send is admitted under the new revision only when that draft still holds exactly the same
 * stored files: the same ids, sizes, names and types, with no reply. Otherwise the revision conflict stands, the
 * retained upload forgets its references, and a retry uploads again.
 */
internal suspend fun MarmotInterface.sendComposerDraftFiles(
    account: String,
    group: String,
    references: List<MediaAttachmentReferenceFfi>,
    upload: DraftFilesUpload,
    admission: DraftFilesAdmission,
): SendSummaryFfi {
    val admit: suspend (MessageDraftRevisionFfi) -> SendSummaryFfi = { revision ->
        admitLocalSend(account, group, admission.token) {
            sendMessageDraftWithClientToken(account, revision, references, admission.token)
        }
    }
    return try {
        admit(admission.revision)
    } catch (moved: MarmotKitException.MessageDraftRevisionConflict) {
        admit(draftFilesRevision(account, group, upload)?.first ?: throw moved)
    }
}

/**
 * Reads the selected draft and, when it holds exactly [upload]'s stored files, saves the send's caption and returns the
 * revision to use with those files' draft ids; null when the draft no longer matches or moved during the save.
 */
private fun MarmotInterface.draftFilesRevision(
    account: String,
    group: String,
    upload: DraftFilesUpload,
): Pair<MessageDraftRevisionFfi, List<String>>? {
    val selected = selectedMessageDraft(account, group)
    val ids = selected.draft?.let { draftFileIdsFor(it, upload.attachments) }
    val revision = ids?.let { saveDraftForSend(account, selected, upload.caption.orEmpty(), null) }
    return revision?.let { it to ids }
}

/**
 * The draft's attachment ids when it holds exactly [attachments] and each is a stored file under its own id with the
 * same size, in order, with matching names and types and no reply target; otherwise null. An inline attachment under a
 * stored file's id never matches.
 */
internal fun draftFileIdsFor(
    draft: SelectedMessageDraftContentFfi,
    attachments: List<PendingAttachment>,
): List<String>? {
    val sameFiles =
        draft.mediaAttachments.size == attachments.size &&
            draft.mediaAttachments.zip(attachments).all { (descriptor, attachment) ->
                val stored = attachment.draftFile
                descriptor.fileBacked &&
                    stored != null &&
                    stored.attachmentId == descriptor.id &&
                    stored.byteCount == descriptor.plaintextSize.toLong()
            }
    val describes = draftDescribesUpload(draft, attachments.map { it.fileName to it.mediaType })
    return draft.mediaAttachments.map { it.id }.takeIf { draft.replyToMessageIdHex == null && sameFiles && describes }
}
