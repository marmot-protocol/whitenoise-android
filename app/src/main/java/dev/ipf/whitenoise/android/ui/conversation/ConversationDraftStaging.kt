package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.whitenoise.android.media.editor.DraftPreparedPhoto
import dev.ipf.whitenoise.android.media.editor.MessageDraftMutationResult
import dev.ipf.whitenoise.android.media.editor.MessageDraftRepository
import dev.ipf.whitenoise.android.media.editor.editorDigest
import dev.ipf.whitenoise.android.media.editor.stagedPhotoAttachmentId
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

/**
 * Adds one video or document pick to the native draft under [attachmentId]. A pick read into a private file
 * becomes a file-backed attachment; any other is written inline.
 */
internal suspend fun stageGenericAttachment(
    drafts: MessageDraftRepository,
    groupIdHex: String,
    accountRef: String,
    attachmentId: String,
    pending: PendingAttachment,
): DraftPreparedPhoto? =
    if (pending.sourceFile != null) {
        stageFileBackedAttachment(drafts, DraftAttachmentTarget(accountRef, groupIdHex, attachmentId), pending)
    } else {
        stageInlineAttachment(drafts, DraftAttachmentTarget(accountRef, groupIdHex, attachmentId), pending)
    }

/** Writes [pending]'s bytes into the draft, recovering the authoritative copy of an id the draft already holds. */
private suspend fun stageInlineAttachment(
    drafts: MessageDraftRepository,
    target: DraftAttachmentTarget,
    pending: PendingAttachment,
): DraftPreparedPhoto? {
    val (accountRef, groupIdHex, attachmentId) = target
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

/** Adds one shelf video, or an image that cannot be edited, under its slot's stable attachment id. */
internal suspend fun stageVisualDraft(
    drafts: MessageDraftRepository,
    groupIdHex: String,
    accountRef: String,
    slot: PendingMediaSlot,
    pending: PendingAttachment,
): DraftPreparedPhoto? =
    stageGenericAttachment(
        drafts,
        groupIdHex,
        accountRef,
        stagedPhotoAttachmentId(accountRef, groupIdHex, slot.id),
        pending,
    )
