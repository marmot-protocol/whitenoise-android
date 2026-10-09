package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MessageDraftAttachmentFfi
import dev.ipf.marmotkit.MessageDraftRevisionFfi
import dev.ipf.marmotkit.SelectedMessageDraftAttachmentFfi
import dev.ipf.marmotkit.SelectedMessageDraftFfi

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
    val matchedAttachmentIds = matchingReplyDraftAttachmentIds(account, selected, attachments)
    val emptySlot = draft == null || (draft.content.isBlank() && draft.mediaAttachments.isEmpty())
    // Sending trims the caption; whitespace retained by the composer does not represent a newer message.
    val conflictingContent = !emptySlot && (matchedAttachmentIds == null || draft.content.trim() != caption.orEmpty())
    val conflictingReply = draft?.replyToMessageIdHex != null && draft.replyToMessageIdHex != target
    if (conflictingContent || conflictingReply) return null
    val staged =
        attachments.mapIndexed { index, attachment ->
            MessageDraftAttachmentFfi(
                id =
                    matchedAttachmentIds?.get(index) ?: java.util.UUID
                        .randomUUID()
                        .toString(),
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

/**
 * Maps each outgoing pick to a distinct native attachment using revision-bound bytes and metadata.
 * Picker staging order can differ from send order; consuming matches preserves duplicate occurrences.
 */
private fun MarmotInterface.matchingReplyDraftAttachmentIds(
    account: String,
    selected: SelectedMessageDraftFfi,
    attachments: List<PendingAttachment>,
): List<String>? {
    val descriptors = selected.draft?.mediaAttachments ?: return null
    return if (descriptors.size == attachments.size && descriptors.map { it.id }.distinct().size == descriptors.size) {
        val remaining = attachments.withIndex().toMutableList()
        val matches = arrayOfNulls<String>(attachments.size)
        descriptors.forEach { descriptor ->
            val bytes = messageDraftAttachmentIfRevision(account, selected.revision, descriptor.id)
            val index = remaining.indexOfFirst { descriptor.matches(it.value, bytes) }
            if (index >= 0) {
                val matched = remaining.removeAt(index)
                matches[matched.index] = descriptor.id
            }
        }
        matches.takeIf { it.all { id -> id != null } }?.filterNotNull()
    } else {
        null
    }
}

/** A same-name replacement or a metadata edit cannot be mistaken for a reordered original pick. */
private fun SelectedMessageDraftAttachmentFfi.matches(
    attachment: PendingAttachment,
    bytes: ByteArray?,
): Boolean {
    val sameMetadata =
        fileName == attachment.fileName && mediaType == attachment.mediaType && dim == attachment.dim
    return sameMetadata && thumbhash == attachment.thumbhash && bytes?.contentEquals(attachment.plaintextBytes) == true
}
