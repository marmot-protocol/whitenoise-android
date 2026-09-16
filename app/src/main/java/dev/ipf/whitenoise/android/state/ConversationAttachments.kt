package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.core.IndexedAttachment
import dev.ipf.whitenoise.android.core.IndexedRejection
import dev.ipf.whitenoise.android.core.MessageAttachments

/** Accepted attachments of a timeline row keyed by protocol index; the projected outcome list wins. */
fun ConversationController.attachmentsFor(item: TimelineMessage): List<IndexedAttachment> =
    item.projected?.media?.let(MessageAttachments::accepted) ?: attachmentsFor(item.record)

/** Rejected attachment slots of a projected row, empty for optimistic rows that MarmotKit has not projected. */
fun ConversationController.rejectedAttachmentsFor(item: TimelineMessage): List<IndexedRejection> =
    item.projected
        ?.media
        ?.let(MessageAttachments::rejected)
        .orEmpty()

/** Accepted references in protocol order, for callers that never derive an attachment index from position. */
fun ConversationController.mediaReferencesFor(item: TimelineMessage): List<MediaAttachmentReferenceFfi> {
    val attachments = attachmentsFor(item)
    return attachments.map { it.value }
}

/** Accepted references of a record in protocol order; see [attachmentsFor] for index-aware callers. */
fun ConversationController.mediaReferencesFor(record: AppMessageRecordFfi): List<MediaAttachmentReferenceFfi> {
    val attachments = attachmentsFor(record)
    return attachments.map { it.value }
}
