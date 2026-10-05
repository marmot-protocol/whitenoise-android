package dev.ipf.whitenoise.android.state

import androidx.annotation.MainThread
import dev.ipf.whitenoise.android.media.editor.CoalescingMessageDraftWriter
import dev.ipf.whitenoise.android.media.editor.MessageDraftMergeReceipt
import dev.ipf.whitenoise.android.media.editor.MessageDraftMutationResult
import dev.ipf.whitenoise.android.notifications.NotificationTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** One process-local delivery receipt per accepted tap; MDK remains the only durable draft store. */
@MainThread
internal class NotificationReplyDraftHandoff(
    private val scope: CoroutineScope,
    private val writer: CoalescingMessageDraftWriter,
    private val store: DraftStore,
    private val available: (NotificationTarget) -> Boolean = { true },
    private val onHydrated: () -> Unit,
) {
    private val deliveries = linkedMapOf<String, Deferred<Boolean>>()
    private val pending = linkedMapOf<String, PendingReply>()

    /** A cancelled navigation waiter cannot cancel or replay an already-started local save. */
    suspend fun stage(target: NotificationTarget): Boolean {
        val draft = target.replyDraft ?: return true
        val intake = pending.getOrPut(draft.id) { PendingReply(target) }
        val previous = deliveries[draft.id]
        if (previous != null && (previous.isCancelled || (previous.isCompleted && !awaitDelivery(previous)))) {
            deliveries.remove(draft.id, previous)
        }
        val delivery = deliveries.getOrPut(draft.id) { scope.async { persist(intake) } }
        val applied = withTimeoutOrNull(HANDOFF_WAIT_MILLIS) { awaitDelivery(delivery) } == true
        if (applied || !available(target)) pending.remove(draft.id)
        if (!applied && delivery.isCompleted) deliveries.remove(draft.id, delivery)
        pruneReceipts()
        return applied
    }

    /** Recovery changes only a draft, never navigation; the original tap is already consumed. */
    suspend fun retryPending(accountRef: String, groupIdHex: String) {
        pending.values.map { it.target }.filter {
            it.accountRef == accountRef && it.groupIdHex == groupIdHex
        }.forEach { stage(it) }
    }

    fun removeAccount(accountRef: String) {
        val ids = pending.filterValues { it.target.accountRef == accountRef }.keys.toList()
        ids.forEach { id ->
            pending.remove(id)
            deliveries.remove(id)?.cancel()
        }
    }

    /** Retry only local persistence; exact proposed content recognizes an uncertain commit. */
    private suspend fun persist(intake: PendingReply): Boolean {
        val target = intake.target
        val text = checkNotNull(target.replyDraft).text
        var attempt = 0
        while (attempt < MAX_SAVE_ATTEMPTS && available(target)) {
            val completion =
                writer.mergeText(
                    target.accountRef,
                    target.groupIdHex,
                    text,
                    trimIncoming = false,
                    receipt = intake.receipt,
                )
            if (completion.result is MessageDraftMutationResult.Success) {
                if (available(target)) {
                    writer.hydrateMergedDraft(store, target.accountRef, target.groupIdHex, completion, onHydrated)
                    return true
                }
                break
            }
            attempt += 1
            if (attempt < MAX_SAVE_ATTEMPTS) delay(RETRY_DELAY_MILLIS)
        }
        return false
    }

    private fun pruneReceipts() {
        while (deliveries.size > MAX_RECEIPTS) {
            val completed = deliveries.entries.firstOrNull { entry -> entry.value.isCompleted } ?: break
            deliveries.remove(completed.key)
        }
    }

    private suspend fun awaitDelivery(delivery: Deferred<Boolean>): Boolean =
        try {
            delivery.await()
        } catch (_: CancellationException) {
            currentCoroutineContext().ensureActive()
            false
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            false
        }

    private class PendingReply(val target: NotificationTarget) {
        val receipt = MessageDraftMergeReceipt()
    }

    private companion object {
        const val MAX_RECEIPTS = 32
        const val MAX_SAVE_ATTEMPTS = 3
        const val RETRY_DELAY_MILLIS = 250L
        const val HANDOFF_WAIT_MILLIS = 3_000L
    }
}
