package dev.ipf.whitenoise.android.state

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.ChatListMessageDeliveryStateFfi
import dev.ipf.marmotkit.ChatListMessagePreviewFfi
import dev.ipf.marmotkit.DeletionSourceFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.marmotkit.SendSummaryFfi
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.diagnostics.PerformancePhase
import dev.ipf.whitenoise.android.diagnostics.PerformanceResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Removes one optimistic send and all Android-owned presentation state.
 * The phase entry is deliberately retained while cancellation is in
 * flight so a late parser/upload/retry continuation cannot enter MDK.
 */
internal fun ConversationController.removeCancelledOptimisticSend(
    optimisticKey: String,
    tempId: String,
) = discardOptimisticSend(optimisticKey, tempId, OptimisticSendDiscard.CANCELLED)

/**
 * Drops one optimistic send from Android presentation state. [discard]
 * names the intended differences between cancellation, abandonment and
 * the in-flight discard paths; traces, host attempts, membership and
 * tombstones stay with each caller.
 */
internal fun ConversationController.discardOptimisticSend(
    optimisticKey: String,
    tempId: String,
    discard: OptimisticSendDiscard,
) {
    if (discard.releasesEditsAndFailureNotice) {
        pendingMessageEditHandoff.abandon(pendingEditKey(tempId))
        optimisticEdits.remove(tempId)
    }
    optimisticMessages.remove(optimisticKey)
    durableAcceptanceCallbacks.remove(optimisticKey)
    messageById.remove(tempId)
    retainedMediaUploads.remove(optimisticKey)
    activeUploadKeys.remove(optimisticKey)
    if (discard.rollsBackPreview) {
        retentionAtSendByMessageId.remove(tempId)
        rollbackOptimisticChatListPreview(tempId)
    }
    if (discard.removesPhase) optimisticSendPhases.remove(optimisticKey)
    when (discard.diagnostic) {
        DiscardedSendDiagnostic.FORGET -> appState.pendingSendDiagnostics.forget(tempId)
        DiscardedSendDiagnostic.COMPLETE_DROPPED ->
            appState.pendingSendDiagnostics.complete(
                tempId,
                PerformancePhase.SEND_COMPLETE,
                PerformanceResult.DROPPED,
            )
        DiscardedSendDiagnostic.LEAVE_TRACKED -> Unit
    }
    if (discard.releasesEditsAndFailureNotice) {
        appState.dismissSendFailureNotice(sendFailureAttempt(optimisticKey))
    }
    publishTimelineFromIndexes()
}

/** Resolves a projected row to its local optimistic send token, if still retained. */
internal fun ConversationController.optimisticSendKey(message: AppMessageRecordFfi): String? {
    val directKey = "msg:${message.messageIdHex}"
    if (directKey in optimisticMessages || directKey in optimisticSendPhases) return directKey
    return optimisticMessages.entries.firstOrNull { (_, item) -> item.record == message }?.key
}

/**
 * Competes with native admission under the group commit lock. Cancelling
 * before admission removes the optimistic row and wakes its sleeping retry;
 * once native owns the send, the canonical pending row remains intact.
 */
@Suppress("ReturnCount") // Distinct unavailable-account/key exits precede the single lock-arbitrated result.
internal suspend fun ConversationController.cancelOptimisticSendResult(
    message: AppMessageRecordFfi,
    optimisticKeyOverride: String? = null,
): Result<Unit> {
    val account =
        conversationAccountRef
            ?: return Result.failure(IllegalStateException("Conversation account unavailable"))
    val optimisticKey =
        optimisticKeyOverride
            ?: optimisticSendKey(message)
            ?: return Result.failure(IllegalStateException("Message is no longer locally cancellable"))
    // A running file-backed transfer holds the commit lock for its whole upload; stop it first.
    retainedMediaUploads.get(optimisticKey)?.requestTransferCancel()
    return appState.withGroupCommitLock(account, group.groupIdHex) {
        val current = optimisticMessages[optimisticKey]
        val tempId = current?.record?.messageIdHex ?: message.messageIdHex
        val phase = optimisticSendPhases[optimisticKey]
        when {
            phase == OptimisticSendPhase.CANCELLED -> Result.success(Unit)
            current?.status == MessageStatus.Failed || phase == OptimisticSendPhase.PRE_ACCEPTANCE -> {
                val retryInFlight = current?.status == MessageStatus.Pending
                optimisticSendPhases[optimisticKey] = OptimisticSendPhase.CANCELLED
                optimisticCancellationGeneration.value += 1
                if (retryInFlight) discardedDuringRetry.add(optimisticKey)
                removeCancelledOptimisticSend(optimisticKey, tempId)
                if (!retryInFlight) trimCancelledSendTombstones()
                Result.success(Unit)
            }
            else -> Result.failure(IllegalStateException("Message is already owned by MarmotKit"))
        }
    }
}

/**
 * Runs [block] until it finishes or the send [optimisticKey] is cancelled. A cancel stops [block] at
 * once, so a slow artwork lookup never holds the text order for later sends, and ends the send with
 * [OptimisticSendCancelledException], so it can never publish.
 */
internal suspend fun <T> ConversationController.untilSendCancelled(
    optimisticKey: String,
    block: suspend () -> T,
): T =
    coroutineScope {
        // A lookup that never suspends finishes here without yielding the send to anything else.
        val work = async(start = CoroutineStart.UNDISPATCHED) { block() }
        if (work.isCompleted) return@coroutineScope work.await()
        val watcher =
            launch(start = CoroutineStart.UNDISPATCHED) {
                optimisticCancellationGeneration.first {
                    optimisticSendPhases[optimisticKey] == OptimisticSendPhase.CANCELLED
                }
                work.cancel()
            }
        try {
            work.await()
        } catch (cancel: CancellationException) {
            requireOptimisticSendNotCancelled(optimisticKey)
            throw cancel
        } finally {
            watcher.cancel()
        }
    }

internal fun ConversationController.requireOptimisticSendNotCancelled(optimisticKey: String) {
    if (optimisticSendPhases[optimisticKey] == OptimisticSendPhase.CANCELLED) {
        throw OptimisticSendCancelledException()
    }
}

internal fun ConversationController.recordOptimisticSendAcceptance(
    optimisticKey: String,
    summary: dev.ipf.marmotkit.SendSummaryFfi,
) {
    optimisticSendPhases[optimisticKey] =
        if (summary.acceptDisposition == SendAcceptDispositionFfi.ACCEPTED_PENDING) {
            OptimisticSendPhase.ACCEPTED_PENDING
        } else {
            OptimisticSendPhase.SETTLED
        }
}

internal fun ConversationController.optimisticSendIsMdkOwnedOrUnknown(optimisticKey: String): Boolean =
    when (optimisticSendPhases[optimisticKey]) {
        OptimisticSendPhase.ACCEPTED_PENDING,
        OptimisticSendPhase.ACCEPTANCE_UNKNOWN,
        OptimisticSendPhase.SETTLED,
        -> true
        else -> false
    }

internal fun ConversationController.trimCancelledSendTombstones() {
    val cancelledKeys =
        optimisticSendPhases
            .asSequence()
            .filter { (key, phase) ->
                phase == OptimisticSendPhase.CANCELLED && key !in discardedDuringRetry
            }.map { (key, _) -> key }
            .toList()
    cancelledKeys
        .take((cancelledKeys.size - MAX_CANCELLED_SEND_TOMBSTONES).coerceAtLeast(0))
        .forEach(optimisticSendPhases::remove)
}

/** Advances the read marker after any accepted add without coupling it to reaction rollback. */
internal suspend fun ConversationController.markReactionTargetRead(target: String) {
    runCatchingCancellable { markReadUpTo(target) }
        .onFailure {
            if (BuildConfig.DEBUG) Log.w("DMConversation", "mark-read after reaction failed", it)
        }
}

/**
 * Settles the durable-acceptance callback for [optimisticKey], and retires the send-failure
 * notice that attempt raised: once the engine owns the send, the notice is no longer true (#2666).
 */
internal fun ConversationController.completeDurableAcceptance(optimisticKey: String) {
    durableAcceptanceCallbacks.remove(optimisticKey)?.invoke()
    appState.dismissSendFailureNotice(sendFailureAttempt(optimisticKey))
}

/** Correlation key for a notice raised by the send attempt on optimistic [key]. */
internal fun ConversationController.sendFailureAttempt(key: String) = SendFailureAttempt(conversationAccountRef, group.groupIdHex, key)

internal fun ConversationController.failOptimisticChatListPreview(optimisticMessageIdHex: String) {
    appState.failOptimisticSentPreview(conversationAccountRef, group.groupIdHex, optimisticMessageIdHex)
}

/** The pending chat-list preview an outgoing send bumps its row with. */
internal fun ConversationController.sentPreview(
    tempId: String,
    plaintext: String,
    contentTokens: MarkdownDocumentFfi,
    timelineAt: ULong,
): ChatListMessagePreviewFfi =
    ChatListMessagePreviewFfi(
        retentionSeconds = null,
        retentionExpiresAt = null,
        messageIdHex = tempId,
        sender = conversationAccountIdHex ?: "",
        senderDisplayName = null,
        plaintext = plaintext,
        contentTokens = contentTokens,
        kind = 9uL,
        timelineAt = timelineAt,
        deleted = false,
        deletionSource = DeletionSourceFfi.UNKNOWN,
        attachmentKind = null,
        attachmentCount = 0u,
        groupSystem = null,
        deliveryState = ChatListMessageDeliveryStateFfi.PENDING,
        sticker = null,
    )

// The chat-list preview a manual retry re-applies: same optimistic id so
// the row entry flips FAILED → PENDING in place, fresh timestamp so the
// row re-bumps to the top like any new send.
internal fun ConversationController.retryPreview(
    tempId: String,
    record: AppMessageRecordFfi,
): ChatListMessagePreviewFfi = sentPreview(tempId, record.plaintext, record.contentTokens, nowSeconds())
