package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.whitenoise.android.media.editor.DraftPreparedPhoto
import dev.ipf.whitenoise.android.media.editor.MessageDraftMutationResult
import dev.ipf.whitenoise.android.media.editor.MessageDraftRepository
import dev.ipf.whitenoise.android.media.editor.editorDigest
import dev.ipf.whitenoise.android.state.PendingAttachment
import dev.ipf.whitenoise.android.ui.conversation.media.PendingMediaSlot
import dev.ipf.whitenoise.android.ui.conversation.media.isLegacyRestore

internal fun legacyOccurrenceIndex(
    slots: List<PendingMediaSlot>,
    slot: PendingMediaSlot,
): Int? =
    if (slot.isLegacyRestore()) {
        slots.takeWhile { it.id != slot.id }.count { it.isLegacyRestore() && it.uri == slot.uri }
    } else {
        null
    }

internal suspend fun stageGenericAttachment(
    drafts: MessageDraftRepository,
    groupIdHex: String,
    accountRef: String,
    attachmentId: String,
    pending: PendingAttachment,
): DraftPreparedPhoto? {
    val attachment = pending.toMessageDraftAttachment(attachmentId)
    val admission =
        drafts.addAttachment(accountRef, groupIdHex, attachment)
    val committed =
        when (admission) {
            is MessageDraftMutationResult.Success -> attachment
            MessageDraftMutationResult.DuplicateAttachment ->
                drafts
                    .draft(accountRef, groupIdHex)
                    .getOrNull()
                    ?.mediaAttachments
                    ?.firstOrNull { it.id == attachmentId }
            else -> null
        } ?: return null
    return DraftPreparedPhoto(
        committed,
        committed.editorDigest(),
        restoredFromNative =
            admission == MessageDraftMutationResult.DuplicateAttachment &&
                committed.isComposerVisual() &&
                !committed.isComposerDocument(),
    )
}
