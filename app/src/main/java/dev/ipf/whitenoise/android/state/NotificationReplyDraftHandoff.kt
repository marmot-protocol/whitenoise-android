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
    private val onFailed: () -> Unit = {},
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val onHydrated: () -> Unit,
) {
    private val deliveries = linkedMapOf<String, Deferred<Boolean>>()
    private val pending = linkedMapOf<String, PendingReply>()

    /** A cancelled navigation waiter cannot cancel or replay an already-started local save. */
    suspend fun stage(target: NotificationTarget): Boolean {
        val draft = target.replyDraft ?: return true
        val intake = pending.getOrPut(draft.id) { PendingReply(target, nowMillis() + HANDOFF_WAIT_MILLIS) }
        val previous = deliveries[draft.id]
        previous?.let { cached ->
            val retryable = cached.isCancelled || (cached.isCompleted && !awaitDelivery(cached))
            if (retryable) deliveries.remove(draft.id, cached)
        }
        val delivery = deliveries.getOrPut(draft.id) { scope.async { deliver(intake) } }
        val remainingWait = (intake.deadlineMillis - nowMillis()).coerceAtLeast(0L)
        val applied = withTimeoutOrNull(remainingWait) { awaitDelivery(delivery) } == true
        if (applied || !available(target)) pending.remove(draft.id)
        if (!applied && delivery.isCompleted) deliveries.remove(draft.id, delivery)
        pruneReceipts()
        return applied
    }

    /** Recovery changes only a draft, never navigation; the original tap is already consumed. */
    suspend fun retryPending(
        accountRef: String,
        groupIdHex: String,
    ) {
        pending.values
            .map { it.target }
            .filter {
                it.accountRef == accountRef && it.groupIdHex == groupIdHex
            }.forEach { stage(it) }
    }

    /** Sign-out/wipe retires unsaved private input and cancels only that account's outstanding deliveries. */
    fun removeAccount(accountRef: String) {
        val ids = pending.filterValues { it.target.accountRef == accountRef }.keys.toList()
        ids.forEach { id ->
            pending.remove(id)
            deliveries.remove(id)?.cancel()
        }
    }

    /** Retire late successes before receipt eviction; report an actual failure only once, never on wait timeout. */
    private suspend fun deliver(intake: PendingReply): Boolean {
        val applied = persist(intake)
        val id = checkNotNull(intake.target.replyDraft).id
        if (applied || !available(intake.target)) {
            pending.remove(id, intake)
        } else if (!intake.failureReported) {
            intake.failureReported = true
            onFailed()
        }
        return applied
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

    /** Bound completed delivery receipts without evicting active saves or the separate failed-save buffer. */
    private fun pruneReceipts() {
        while (deliveries.size > MAX_RECEIPTS) {
            val completed = deliveries.entries.firstOrNull { entry -> entry.value.isCompleted } ?: break
            deliveries.remove(completed.key)
        }
    }

    /** A cancelled save is a failed handoff; cancellation of this navigation caller must still propagate. */
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

    private class PendingReply(
        val target: NotificationTarget,
        val deadlineMillis: Long,
    ) {
        val receipt = MessageDraftMergeReceipt()
        var failureReported = false
    }

    private companion object {
        const val MAX_RECEIPTS = 32
        const val MAX_SAVE_ATTEMPTS = 3
        const val RETRY_DELAY_MILLIS = 250L
        const val HANDOFF_WAIT_MILLIS = 3_000L
    }
}
