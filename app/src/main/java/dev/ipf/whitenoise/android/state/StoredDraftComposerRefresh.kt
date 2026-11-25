package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.SendSummaryFfi

/** Refreshes the exact conversation's restored shelf when stored files have been durably accepted. */
internal fun ConversationController.recordStoredDraftConsumption() =
    appState.retainedConversationStates.recordNativeComposerCleanup(boundAccountRef, group.groupIdHex)

/** Marks a recovered native send accepted and refreshes any reopened shelf that still displays its files. */
internal fun ConversationController.recordRecoveredStoredDraft(
    key: String,
    summary: SendSummaryFfi,
) {
    recordOptimisticSendAcceptance(key, summary)
    recordStoredDraftConsumption()
}

/** Only the text generation captured at the send gesture can authorize removing a caption. */
internal fun ConversationController.ownsStoredDraftCaption(retained: RetainedMediaUpload): Boolean {
    val captured = retained.draftSendClearToken ?: return false
    return captured.accountRef == boundAccountRef &&
        captured.groupIdHex == group.groupIdHex &&
        appState.messageDraftRepository.coordinated.isTextCurrent(
            captured.accountRef,
            captured.groupIdHex,
            captured.generation,
        )
}

/**
 * Admits [retained]'s uploaded [references] under the commit lock its caller holds. When MDK refuses a send of stored
 * draft files for a stale epoch, its Retry must upload the files again, so the send copies them out of the draft before
 * the lock is released and another send of this chat can clear the draft.
 */
internal suspend fun ConversationController.admitRetainedMedia(
    account: String,
    references: List<MediaAttachmentReferenceFfi>,
    retained: RetainedMediaUpload,
    token: String,
): SendSummaryFfi =
    try {
        appState
            .marmotIo(MarmotTraceSection.MEDIA_SEND) {
                val ownsCaption = { ownsStoredDraftCaption(retained) }
                val consuming = retained.draftUploadRevision.takeIf { ownsCaption() }
                val admission = DraftFilesAdmission(consuming, token, ownsCaption)
                publishRetainedMedia(account, group.groupIdHex, references, retained, admission)
            }.also { if (retained.isDraftStored) recordStoredDraftConsumption() }
    } catch (
        @Suppress("TooGenericExceptionCaught") failure: Throwable,
    ) {
        // The same refusal makes the Retry drop the references and upload again, see forgetUploadAfter.
        if (retained.isDraftStored && !retained.ownsDraftCopies && isStaleMediaReferenceEpoch(failure)) {
            keepDraftFiles(account, retained)
        }
        throw failure
    }
