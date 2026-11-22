package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MediaFileTransferControlFfi
import dev.ipf.marmotkit.SelectedMessageDraftContentFfi

/** A send whose attachments are files the composer's draft stores, and the caption it carries. */
internal data class DraftFilesUpload(
    val attachments: List<PendingAttachment>,
    val caption: String?,
)

/**
 * Uploads a send of stored draft files from the copies the draft holds, by id, so nothing is read from the pick again.
 * Admission is left to the draft send, as for a matching draft in [uploadOrAdmitComposerMediaFilesWithToken]: MarmotKit
 * admits uploaded references under a client token only through the draft, so the draft must hold exactly these files,
 * in order and without a reply, or the send fails before anything uploads. The caption is saved first, so the upload
 * and the draft send that follows share one revision.
 */
internal suspend fun MarmotInterface.uploadComposerDraftFilesWithToken(
    account: String,
    group: String,
    upload: DraftFilesUpload,
    control: MediaFileTransferControlFfi,
    token: String,
): DurableComposerMediaUpload {
    recoveredLocalSend(account, group, token)?.let { recovered -> return recoveredComposerUpload(recovered) }
    val selected = selectedMessageDraft(account, group)
    val ids =
        checkNotNull(selected.draft?.let { draftFileIdsFor(it, upload.attachments) }) {
            "the draft no longer holds exactly this send's stored files"
        }
    val revision =
        checkNotNull(saveDraftForSend(account, selected, upload.caption.orEmpty(), null)) {
            "the draft moved before its stored files uploaded"
        }
    return DurableComposerMediaUpload(
        upload = uploadMessageDraftAttachments(account, revision, ids, null, control),
        acceptance = null,
        recoveredWithoutUpload = false,
    )
}

/**
 * The draft's attachment ids when it holds exactly [attachments], every one a stored file under its own id, in order,
 * with matching names and types and no reply target; otherwise null.
 */
internal fun draftFileIdsFor(
    draft: SelectedMessageDraftContentFfi,
    attachments: List<PendingAttachment>,
): List<String>? {
    val held = draft.mediaAttachments.map { it.id }
    val sent = attachments.map { it.draftFile?.attachmentId }
    val describes = draftDescribesUpload(draft, attachments.map { it.fileName to it.mediaType })
    return held.takeIf { draft.replyToMessageIdHex == null && sent == held && describes }
}
