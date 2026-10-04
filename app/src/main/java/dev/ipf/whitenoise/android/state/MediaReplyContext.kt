package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotInterface
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
    val missingDraft = capturedTarget != null && !matchingAttachments
    val changedTarget = matchingAttachments && draftTarget != capturedTarget
    if (missingDraft || changedTarget) {
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
    target: String?,
): SelectedMessageDraftFfi? =
    marmotIo {
        val selected = selectedDraftOrNull(account, group)
        val draft = selected?.draft
        val descriptorsMatch =
            draft != null &&
                draft.mediaAttachments.size == attachments.size &&
                draft.mediaAttachments.zip(attachments).all { (descriptor, attachment) ->
                    descriptor.fileName == attachment.fileName && descriptor.mediaType == attachment.mediaType
                }
        if (!descriptorsMatch) {
            requireMediaReplyDraft(target, null, false)
            null
        } else {
            checkNotNull(selected)
            checkNotNull(draft)
            // Reply selection is UI state; the coalescing draft writer owns text only.
            // Bind the captured selection here through MDK, without waiting for a text debounce.
            val revision =
                saveDraftForSend(account, selected, draft.content, target)
                    ?: throw MediaReplyDraftUnavailableException()
            SelectedMessageDraftFfi(revision, draft.copy(replyToMessageIdHex = target))
        }
    }

/** Refresh only the same accepted content; never consume a newer user's draft to repair a stale revision. */
internal fun MarmotInterface.currentMediaReplyDraft(
    account: String,
    group: String,
    context: ComposerMediaSendContext,
    caption: String?,
): SelectedMessageDraftFfi? {
    val selected = selectedDraftOrNull(account, group)
    val captured = context.replyDraft?.draft ?: return selected
    val current = selected?.draft
    val sameIntent =
        current != null &&
            current.replyToMessageIdHex == captured.replyToMessageIdHex &&
            current.mediaAttachments == captured.mediaAttachments
    val sameContent = current?.content == captured.content || current?.content == caption.orEmpty()
    if (!sameIntent || !sameContent) {
        throw MediaReplyDraftUnavailableException()
    }
    return selected
}
