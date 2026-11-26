package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.SendSummaryFfi
import dev.ipf.whitenoise.android.ui.conversation.finishCollectionDraftCleanup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val storedDraftAdmissionLocks = KeyedMutexPool()

/** Coordinates journal recovery with native admission, without waiting for an attachment transfer. */
internal suspend fun <T> withStoredDraftAdmission(
    account: String,
    group: String,
    block: suspend () -> T,
): T = storedDraftAdmissionLocks.withLock("$account|$group", block)

/** Captures this send's composer generation and any collection files it must preserve. */
internal fun WhiteNoiseAppState.captureDraftForSend(
    accountRef: String?,
    groupIdHex: String,
): DraftSendClearToken? =
    composerDraftExpansionBridge.captureForSend(accountRef, groupIdHex)?.let {
        it.copy(keepAttachments = hasCollectionDrafts(it.accountRef, it.groupIdHex))
    }

/** Clears only the draft and geometry generation durably accepted by MDK. */
internal fun WhiteNoiseAppState.clearDraftAfterSuccessfulSend(
    pendingClear: DraftSendClearToken,
    textOnly: Boolean = false,
) {
    val keep =
        textOnly ||
            pendingClear.keepAttachments ||
            hasCollectionDrafts(pendingClear.accountRef, pendingClear.groupIdHex)
    composerDraftExpansionBridge.clearAfterDurableAcceptance(pendingClear, keepAttachments = keep)
}

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
    if (retained.isDraftStored) {
        withStoredDraftAdmission(account, group.groupIdHex) {
            admitMediaWithDraftRecovery(account, references, retained, token)
        }
    } else {
        admitMediaWithDraftRecovery(account, references, retained, token)
    }

/** Publishes media within any caller-owned draft admission fence. */
private suspend fun ConversationController.admitMediaWithDraftRecovery(
    account: String,
    references: List<MediaAttachmentReferenceFfi>,
    retained: RetainedMediaUpload,
    token: String,
): SendSummaryFfi {
    val cleanup = recordStoredDraftAdmission(account, retained, token)
    return try {
        appState
            .marmotIo(MarmotTraceSection.MEDIA_SEND) {
                val ownsCaption = { ownsStoredDraftCaption(retained) }
                val consuming = retained.draftUploadRevision.takeIf { ownsCaption() }
                val admission = DraftFilesAdmission(consuming, token, ownsCaption)
                publishRetainedMedia(account, group.groupIdHex, references, retained, admission)
            }.also {
                if (cleanup != null) settleStoredDraftAdmission(cleanup)
            }
    } catch (
        @Suppress("TooGenericExceptionCaught") failure: Throwable,
    ) {
        cleanup?.let { record ->
            appState.attachmentCollections.update(record.id) { it.copy(paused = true) }
        }
        // The same refusal makes the Retry drop the references and upload again, see forgetUploadAfter.
        if (retained.isDraftStored && !retained.ownsDraftCopies && isStaleMediaReferenceEpoch(failure)) {
            keepDraftFiles(account, retained)
        }
        throw failure
    }
}

/** Records exact stored-file IDs with the client token before sending to MDK; retries use the same key. */
private suspend fun ConversationController.recordStoredDraftAdmission(
    account: String,
    retained: RetainedMediaUpload,
    token: String,
): AttachmentCollectionOperation? {
    if (!retained.isDraftStored) return null
    val items =
        retained.attachments.mapNotNull { attachment ->
            attachment.draftFile?.let { file ->
                CollectionItem(
                    file.attachmentId,
                    "",
                    CollectionItemKind.DOCUMENT,
                    CollectionItemState.UPLOADING,
                    CollectionStoredDraft(file.attachmentId, file.byteCount, attachment.mediaType),
                )
            }
        }
    val registry = appState.attachmentCollections
    val groupIdHex = group.groupIdHex
    val admission = registry.admissions.begin(account, groupIdHex)
    val epoch = appState.mediaUploadSessionEpoch()
    val now = registry.now()
    val chunk = CollectionChunk(token, 0, items.map { it.id }, false, token, CollectionChunkState.UPLOADING)
    val record =
        AttachmentCollectionOperation(
            "stored-$token",
            account,
            groupIdHex,
            items,
            listOf(chunk),
            now,
            now,
            cleanupOnly = true,
        )
    try {
        val recorded =
            withContext(Dispatchers.IO) {
                registry.start(record, admission) {
                    shouldAcceptMediaUploadForAccount(
                        account,
                        epoch,
                        appState.activeAccountRef,
                        appState.mediaUploadSessionEpoch(),
                    )
                }
            }
        check(recorded) { "Could not record stored draft send before admission" }
        return record
    } finally {
        registry.admissions.release(admission)
    }
}

/** Completes a recorded admission, leaving the record recoverable until exact-ID cleanup succeeds. */
private suspend fun ConversationController.settleStoredDraftAdmission(record: AttachmentCollectionOperation) {
    val registry = appState.attachmentCollections
    registry.update(record.id) {
        it.withChunkState(
            record.chunks.single().id,
            CollectionChunkState.ACCEPTED,
            CollectionItemState.PUBLISHED,
            registry.now(),
        )
    }
    appState.finishCollectionDraftCleanup(record.id)
}
