package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ChatListWindowSnapshotFfi
import kotlinx.coroutines.CancellationException

/** Requires the complete initial replacement promised by an open chat-list window. */
internal fun ChatListWindowSnapshotFfi?.requireChatListWindowSnapshot(): ChatListWindowSnapshotFfi =
    this ?: throw CancellationException("Chat-list window closed before snapshot")

/**
 * Orders complete replacements from one native chat-list window. Command results and their stream
 * echoes may arrive in either order, so an equal or older sequence is ignored; a different
 * subscription generation belongs to a replacement handle and requires reopening.
 */
internal class ChatListWindowCursor(
    initial: ChatListWindowSnapshotFfi,
) {
    private val generation = initial.subscriptionGeneration

    /** Sequence of the newest installed replacement, the value every window command must quote. */
    var sequence: ULong = initial.sequence
        private set

    /** True when the replacement cannot belong to the currently owned native handle. */
    fun requiresReopen(update: ChatListWindowSnapshotFfi): Boolean = update.subscriptionGeneration != generation

    /** Accepts only a newer replacement from the same generation. */
    fun accept(update: ChatListWindowSnapshotFfi): Boolean {
        if (requiresReopen(update) || update.sequence <= sequence) return false
        sequence = update.sequence
        return true
    }
}
