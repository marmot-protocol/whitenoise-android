package dev.ipf.whitenoise.android.media.editor

import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MessageDraftAttachmentFfi
import dev.ipf.marmotkit.MessageDraftFfi

/** One conditional write against the currently selected revision, or an unconditional save on legacy gateways. */
internal fun MessageDraftGateway.saveAgainstSelected(
    accountRef: String,
    groupIdHex: String,
    content: String,
    replyToMessageIdHex: String?,
    mediaAttachments: List<MessageDraftAttachmentFfi>,
): MessageDraftFfi {
    val selected =
        selected(accountRef, groupIdHex)
            ?: return save(accountRef, groupIdHex, content, replyToMessageIdHex, mediaAttachments)
    return saveIfRevision(accountRef, groupIdHex, selected.revision, content, replyToMessageIdHex, mediaAttachments)
}

/** One conditional clear against the currently selected revision, or an unconditional delete on legacy gateways. */
internal fun MessageDraftGateway.deleteAgainstSelected(
    accountRef: String,
    groupIdHex: String,
) {
    val selected = selected(accountRef, groupIdHex) ?: return delete(accountRef, groupIdHex)
    clearIfRevision(accountRef, groupIdHex, selected.revision)
}

/** True when a mutation lost a revision race and should be re-run against the fresh selected draft. */
internal fun MessageDraftMutationResult.isRevisionConflict(): Boolean =
    this is MessageDraftMutationResult.Failure && cause is MarmotKitException.MessageDraftRevisionConflict
