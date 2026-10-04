package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.marmotkit.SelectedMessageDraftFfi
import dev.ipf.whitenoise.android.core.MessageProjector

/** Captured caller intent and native revision for one logical media admission. */
internal data class ComposerMediaSendContext(
    val clientToken: String? = null,
    val replyTargetMessageIdHex: String? = null,
    val replyDraft: SelectedMessageDraftFfi? = null,
)

/** The native draft is the only typed media-reply admission route in the pinned API. */
internal class MediaReplyDraftUnavailableException(
    cause: Throwable? = null,
) : IllegalStateException(null, cause)

/** Never inherit a newer reply or silently submit an unsupported reply as plain media. */
internal fun requireMediaReplyDraft(
    capturedTarget: String?,
    draftTarget: String?,
    matchingAttachments: Boolean,
) {
    if ((capturedTarget != null && !matchingAttachments) || (matchingAttachments && draftTarget != capturedTarget)) {
        throw MediaReplyDraftUnavailableException()
    }
}

/** Local optimistic presentation only; MDK constructs and validates the outgoing reply relationship. */
internal fun mediaReplyPresentationTags(target: String?): List<MessageTagFfi> =
    target?.let { listOf(MessageProjector.eventTag(it), MessageProjector.quoteTag(it)) }.orEmpty()

/** Reject unsupported replies before accepting the composer or consuming a reviewed recording. */
internal suspend fun WhiteNoiseAppState.captureMediaReplyDraft(
    account: String,
    group: String,
    attachments: List<PendingAttachment>,
    target: String,
): SelectedMessageDraftFfi? =
    marmotIo {
        val selected = selectedDraftOrNull(account, group)
        val draft = selected?.draft ?: return@marmotIo null
        val descriptorsMatch =
            draft.mediaAttachments.size == attachments.size &&
                draft.mediaAttachments.zip(attachments).all { (descriptor, attachment) ->
                    descriptor.fileName == attachment.fileName && descriptor.mediaType == attachment.mediaType
                }
        selected.takeIf { draft.replyToMessageIdHex == target && descriptorsMatch }
    }
