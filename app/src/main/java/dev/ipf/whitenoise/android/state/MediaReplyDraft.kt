package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MessageDraftAttachmentFfi
import dev.ipf.marmotkit.MessageDraftRevisionFfi

/**
 * Stages a reply only into an empty slot or the exact media already selected by this composer.
 * The opaque revision is retained by the send: upload completion must never select a newer draft.
 * A conflicting draft is left intact so the caller can keep the recording and reply choice.
 */
internal fun MarmotInterface.stageMediaReply(
    account: String,
    group: String,
    target: String,
    caption: String?,
    attachments: List<PendingAttachment>,
): MessageDraftRevisionFfi? {
    val selected = selectedMessageDraft(account, group)
    val draft = selected.draft
    val sameAttachments =
        draft?.mediaAttachments?.let { descriptors ->
            descriptors.size == attachments.size &&
                descriptors.zip(attachments).all { (descriptor, attachment) ->
                    descriptor.fileName == attachment.fileName && descriptor.mediaType == attachment.mediaType &&
                        descriptor.dim == attachment.dim && descriptor.thumbhash == attachment.thumbhash &&
                        messageDraftAttachmentIfRevision(account, selected.revision, descriptor.id)
                            ?.contentEquals(attachment.plaintextBytes) == true
                }
        } == true
    val emptySlot = draft == null || (draft.content.isBlank() && draft.mediaAttachments.isEmpty())
    if (!emptySlot && (!sameAttachments || draft?.content != caption.orEmpty())) return null
    if (draft?.replyToMessageIdHex != null && draft.replyToMessageIdHex != target) return null
    val staged =
        attachments.mapIndexed { index, attachment ->
            MessageDraftAttachmentFfi(
                id = if (sameAttachments) draft!!.mediaAttachments[index].id else java.util.UUID.randomUUID().toString(),
                fileName = attachment.fileName,
                mediaType = attachment.mediaType,
                plaintext = attachment.plaintextBytes,
                dim = attachment.dim,
                thumbhash = attachment.thumbhash,
                durationSeconds = null,
                waveformSamples = emptyList(),
            )
        }
    return saveMessageDraftIfRevision(account, selected.revision, caption.orEmpty(), target, staged).revision
}
