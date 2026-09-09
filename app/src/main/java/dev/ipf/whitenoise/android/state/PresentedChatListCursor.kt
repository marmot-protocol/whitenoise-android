package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.PresentedChatListUpdateFfi
import kotlinx.coroutines.CancellationException

/** Requires the complete initial snapshot promised by an open subscription. */
internal fun PresentedChatListUpdateFfi?.requirePresentedChatListSnapshot(): PresentedChatListUpdateFfi =
    this ?: throw CancellationException("Presented chat-list subscription closed before snapshot")

/**
 * Orders complete presented-chat snapshots from one native subscription.
 * A generation or account-store change belongs to a replacement handle, while
 * duplicate/out-of-order sequence numbers are ignored on the current handle.
 */
internal class PresentedChatListCursor(
    initial: PresentedChatListUpdateFfi,
) {
    private val generation = initial.subscriptionGeneration
    private val accountStoreEpoch =
        initial.snapshot.presentationVersion.accountStoreEpoch
            .copyOf()
    private var sequence = initial.sequence

    /** True when the update cannot belong to the currently owned native handle. */
    fun requiresReopen(update: PresentedChatListUpdateFfi): Boolean =
        update.subscriptionGeneration != generation ||
            !update.snapshot.presentationVersion.accountStoreEpoch
                .contentEquals(accountStoreEpoch)

    /** Accepts only the next monotonic update from the same generation and store. */
    fun accept(update: PresentedChatListUpdateFfi): Boolean {
        if (requiresReopen(update) || update.sequence <= sequence) return false
        sequence = update.sequence
        return true
    }
}
