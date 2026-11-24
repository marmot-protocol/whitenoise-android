package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi

/**
 * Retries a failed media send [current] from its retained compressed bytes through the shared upload path. If the
 * bytes were evicted or lost, [performMediaUpload] flips the row back to Failed and prompts a re-attach.
 */
internal suspend fun ConversationController.retryFailedMediaSend(
    account: String,
    key: String,
    current: TimelineMessage,
) {
    val mediaOrder = retriedTimelineOrder(current.timelineOrder) { nextOptimisticTimelineOrder() }
    val mediaTempId = current.record.messageIdHex
    optimisticMessages[key] =
        current.copy(
            status = MessageStatus.Pending,
            timelineOrder = mediaOrder,
        )
    optimisticSendPhases[key] = OptimisticSendPhase.PRE_ACCEPTANCE
    // Re-mark this slot as "in flight" — if the previous attempt's
    // Failed branch had drained the bytes via a dispose-time clear,
    // this would still no-op (performMediaUpload bails on missing
    // bytes), but normally the bytes are retained for retry.
    activeUploadKeys.add(key)
    appState.trackInFlightMediaUpload(conversationAccountRef, group.groupIdHex, key)
    publishTimelineFromIndexes()
    // The retry is a fresh pending send from the list's point of view:
    // flip the row's failed preview back to pending and re-bump its
    // position. performMediaUpload owns the terminal state.
    appState.applyOptimisticSentPreview(
        conversationAccountRef,
        group.groupIdHex,
        retryPreview(mediaTempId, current.record),
    )
    appState.pendingSendDiagnostics.startMediaSend(mediaTempId, manualRetry = true)
    performMediaUpload(account, key, mediaTempId, mediaOrder, current.record)
}

/**
 * Flips a failed text send [current] back to Pending under its existing key and client token [tempId], with
 * [refreshedRecord] at [order], and re-bumps its chat-list row like a new send.
 */
internal fun ConversationController.markFailedTextSendPending(
    key: String,
    tempId: String,
    current: TimelineMessage,
    refreshedRecord: AppMessageRecordFfi,
    order: ULong,
) {
    discardedDuringRetry.remove(key)
    optimisticSendPhases[key] = OptimisticSendPhase.PRE_ACCEPTANCE
    optimisticMessages[key] =
        current.copy(
            record = refreshedRecord,
            status = MessageStatus.Pending,
            timelineOrder = order,
        )
    messageById[tempId] = refreshedRecord
    publishTimelineFromIndexes()
    appState.applyOptimisticSentPreview(
        conversationAccountRef,
        group.groupIdHex,
        retryPreview(tempId, refreshedRecord),
    )
}

/**
 * Settles a retried text send that MDK already committed as [committedProjection] without publishing it again:
 * the optimistic row hands its display position, retention and chat-list preview to the projected row.
 */
internal fun ConversationController.settleRetryFromCommittedProjection(
    key: String,
    tempId: String,
    committedProjection: TimelineMessageRecordFfi,
) {
    optimisticSendPhases[key] = OptimisticSendPhase.ACCEPTED_PENDING
    preserveOptimisticDisplayPosition(committedProjection.messageIdHex, tempId)
    completeDurableAcceptance(key)
    transferRetentionAtSend(tempId, committedProjection.messageIdHex)
    appState.commitOptimisticSentPreview(
        accountRef = conversationAccountRef,
        groupIdHex = group.groupIdHex,
        optimisticMessageIdHex = tempId,
        confirmedMessageIdHex = committedProjection.messageIdHex,
    )
    optimisticMessages.remove(key)
    messageById.remove(tempId)
    val projectedRecord = upsertProjectedRecord(committedProjection)
    handoffPendingMessageEdit(tempId, committedProjection.messageIdHex, ready = true)
    invalidatedProjectionIdsMatchingMessage(timelineRecords, projectedRecord)
        .forEach(::removeProjectedRecord)
    if (discardedDuringRetry.remove(key)) {
        publishTimelineFromIndexes()
        optimisticSendPhases.remove(key)
        trimCancelledSendTombstones()
        return
    }
    publishTimelineFromIndexes()
    optimisticSendPhases.remove(key)
}
