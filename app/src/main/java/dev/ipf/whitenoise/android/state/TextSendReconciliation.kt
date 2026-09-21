package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi

internal fun shouldInsertSentOptimisticMessage(
    confirmedId: String,
    projectedMessageIds: Set<String>,
): Boolean = confirmedId !in projectedMessageIds

/** Publish succeeded without an id and its temp bubble still awaits the engine echo (#1315). */
internal fun textSendAwaitingEchoConfirmation(
    summaryMessageIds: List<String>,
    optimisticStillPresent: Boolean,
): Boolean = summaryMessageIds.isEmpty() && optimisticStillPresent

internal fun acceptedPendingTextAwaitingProjection(
    acceptDisposition: SendAcceptDispositionFfi,
    confirmedId: String,
    projectedMessageIds: Set<String>,
): Boolean =
    acceptDisposition == SendAcceptDispositionFfi.ACCEPTED_PENDING &&
        confirmedId !in projectedMessageIds

/**
 * Drives native settlement for an already-durable text intent without issuing another semantic send.
 * A convergence failure leaves the optimistic row pending for a later authoritative projection; cancellation
 * still escapes so account and process lifetimes retain ownership of the suspended work.
 */
@Suppress("TooGenericExceptionCaught") // Native convergence may surface any non-cancellation gateway failure.
internal suspend fun runAcceptedPendingTextConvergence(
    acceptedPending: Boolean,
    converge: suspend () -> Unit,
    onFailure: (Throwable) -> Unit = {},
) {
    if (!acceptedPending) return
    try {
        converge()
    } catch (throwable: Throwable) {
        rethrowIfCancellation(throwable)
        onFailure(throwable)
    }
}

data class SuccessfulTextSendReconciliation(
    val confirmedId: String,
    val confirmed: AppMessageRecordFfi,
    val awaitingEcho: Boolean,
    /** MDK durably accepted the intent but has not assigned a published event id. */
    val acceptedPending: Boolean,
    val insertedSent: Boolean,
) {
    /** Keep the optimistic bubble until MDK's durable projection settles it. */
    val awaitingProjection: Boolean
        get() = awaitingEcho || acceptedPending
}

/**
 * Shared optimistic-state transition after a successful text/reply publish.
 * Used by the initial send path and [ConversationController.retryFailedSend] so
 * empty-summary late-echo semantics stay identical (#1315).
 */
internal fun reconcileSuccessfulTextSend(
    summaryMessageIds: List<String>,
    acceptDisposition: SendAcceptDispositionFfi,
    optimisticKey: String,
    tempId: String,
    optimisticRecord: AppMessageRecordFfi,
    optimisticMessages: MutableMap<String, TimelineMessage>,
    messageById: MutableMap<String, AppMessageRecordFfi>,
    projectedMessageIds: Set<String>,
    timelineOrder: ULong,
    acceptedPendingTextOptimisticIdsByMessageId: MutableMap<String, String>? = null,
): SuccessfulTextSendReconciliation {
    val retentionAtSendSeconds = optimisticMessages[optimisticKey]?.retentionAtSendSeconds
    val hasConfirmedId = summaryMessageIds.isNotEmpty()
    val confirmedId = summaryMessageIds.firstOrNull() ?: tempId
    // The authoritative projection can beat the accepted-pending FFI return.
    // In that ordering there is nothing left to await or bridge: settle through
    // the normal confirmed path using the exact canonical id already projected.
    val acceptedPending =
        acceptedPendingTextAwaitingProjection(
            acceptDisposition = acceptDisposition,
            confirmedId = confirmedId,
            projectedMessageIds = projectedMessageIds,
        )
    val awaitingEcho =
        !acceptedPending &&
            textSendAwaitingEchoConfirmation(
                summaryMessageIds,
                optimisticStillPresent = optimisticKey in optimisticMessages,
            )
    val confirmed = optimisticRecord.copy(messageIdHex = confirmedId)
    if ((hasConfirmedId || awaitingEcho) && confirmedId.isNotEmpty()) {
        messageById[confirmedId] = confirmed
    }
    rememberAcceptedPendingTextOptimisticId(
        acceptedPending = acceptedPending,
        confirmedId = confirmedId,
        tempId = tempId,
        acceptedPendingTextOptimisticIdsByMessageId = acceptedPendingTextOptimisticIdsByMessageId,
    )
    if (!awaitingEcho && !acceptedPending) {
        optimisticMessages.remove(optimisticKey)
        if (confirmedId != tempId) messageById.remove(tempId)
    }
    val insertedSent =
        !acceptedPending &&
            (
                awaitingEcho ||
                    (hasConfirmedId && shouldInsertSentOptimisticMessage(confirmedId, projectedMessageIds))
            )
    if (insertedSent) {
        val sentKey = if (awaitingEcho) optimisticKey else "msg:$confirmedId"
        optimisticMessages[sentKey] =
            TimelineMessage(
                sentKey,
                confirmed,
                MessageStatus.Sent,
                timelineOrder = timelineOrder,
                retentionAtSendSeconds = retentionAtSendSeconds,
            )
    }
    return SuccessfulTextSendReconciliation(
        confirmedId = confirmedId,
        confirmed = confirmed,
        awaitingEcho = awaitingEcho,
        acceptedPending = acceptedPending,
        insertedSent = insertedSent,
    )
}

private fun rememberAcceptedPendingTextOptimisticId(
    acceptedPending: Boolean,
    confirmedId: String,
    tempId: String,
    acceptedPendingTextOptimisticIdsByMessageId: MutableMap<String, String>?,
) {
    if (acceptedPending && confirmedId.isNotEmpty()) {
        acceptedPendingTextOptimisticIdsByMessageId?.set(confirmedId, tempId)
    }
}
