package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.media.editor.CoalescingMessageDraftWriter
import dev.ipf.whitenoise.android.media.editor.MessageDraftMutationResult
import dev.ipf.whitenoise.android.notifications.NotificationTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay

/** One process-local delivery receipt per accepted tap; MDK remains the only durable draft store. */
internal class NotificationReplyDraftHandoff(
    private val scope: CoroutineScope,
    private val writer: CoalescingMessageDraftWriter,
    private val store: DraftStore,
    private val available: (NotificationTarget) -> Boolean = { true },
    private val onHydrated: () -> Unit,
) {
    private val deliveries = linkedMapOf<String, Deferred<Boolean>>()

    /** A cancelled navigation waiter cannot cancel or replay an already-started local save. */
    suspend fun stage(target: NotificationTarget): Boolean {
        val draft = target.replyDraft ?: return true
        deliveries[draft.id]?.takeIf { it.isCancelled }?.let { deliveries.remove(draft.id) }
        val previous = deliveries[draft.id]
        if (previous?.isCompleted == true && !previous.await()) deliveries.remove(draft.id, previous)
        val delivery = deliveries.getOrPut(draft.id) {
            scope.async { persist(target) }
        }
        return delivery.await().also {
            if (!it) deliveries.remove(draft.id, delivery)
            pruneReceipts()
        }
    }

    /** Retry only local draft persistence, never a send; uncertain commits are suffix-idempotent. */
    private suspend fun persist(target: NotificationTarget): Boolean {
        val text = checkNotNull(target.replyDraft).text
        var attempt = 0
        while (attempt < MAX_SAVE_ATTEMPTS && available(target)) {
            val completion =
                writer.mergeText(
                    target.accountRef,
                    target.groupIdHex,
                    text,
                    trimIncoming = false,
                    deduplicateSuffix = true,
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

    private companion object {
        const val MAX_RECEIPTS = 32
        const val MAX_SAVE_ATTEMPTS = 3
        const val RETRY_DELAY_MILLIS = 250L
    }
}
