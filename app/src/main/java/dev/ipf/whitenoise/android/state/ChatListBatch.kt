package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.PresentedChatRowFfi

internal const val CHAT_LIST_BATCH_STEP_TIMEOUT_MS = 30_000L

/** Explicit commands: a mixed selection never becomes a series of toggles. */
internal enum class ChatListBatchCommand { READ, UNREAD, MUTE, UNMUTE, PIN, UNPIN, ARCHIVE, RESTORE, EXCLUDE_FOLDER }

internal enum class ChatListBatchOutcome { CHANGED, SATISFIED, SKIPPED, FAILED, NOT_STARTED, UNCONFIRMED }

internal data class ChatListBatchResult(
    val outcomes: Map<String, ChatListBatchOutcome>,
    val attemptedIds: Set<String> = emptySet(),
) {
    fun withAttempt(attempt: ChatListBatchResult?): ChatListBatchResult =
        if (attempt == null) this else ChatListBatchResult(outcomes + attempt.outcomes, attemptedIds + attempt.attemptedIds)

    val changed get() = outcomes.values.count { it == ChatListBatchOutcome.CHANGED }
    val satisfied get() = outcomes.values.count { it == ChatListBatchOutcome.SATISFIED }
    val skipped get() = outcomes.values.count { it == ChatListBatchOutcome.SKIPPED }
    val failed get() = outcomes.values.count { it == ChatListBatchOutcome.FAILED }
    val unconfirmed get() = outcomes.values.count { it == ChatListBatchOutcome.UNCONFIRMED }
    val notStarted get() = outcomes.values.count { it == ChatListBatchOutcome.NOT_STARTED }
    val retryIds get() = outcomes.filterValues { it == ChatListBatchOutcome.FAILED }.keys.toList()
}

/** Classifies the latest native row before attempting a transition. Native remains the authority. */
@Suppress("CyclomaticComplexMethod") // Exhaustive table pairs native desired-state and action-capability flags.
internal fun chatListBatchDisposition(
    row: PresentedChatRowFfi,
    command: ChatListBatchCommand,
): ChatListBatchOutcome? {
    val state = row.row
    val satisfied =
        when (command) {
            ChatListBatchCommand.READ -> !state.hasUnread && !state.manuallyMarkedUnread
            ChatListBatchCommand.UNREAD -> state.hasUnread || state.manuallyMarkedUnread
            ChatListBatchCommand.MUTE -> state.muted
            ChatListBatchCommand.UNMUTE -> !state.muted
            ChatListBatchCommand.PIN -> state.pinned
            ChatListBatchCommand.UNPIN -> !state.pinned
            ChatListBatchCommand.ARCHIVE -> state.archived
            ChatListBatchCommand.RESTORE -> !state.archived
            ChatListBatchCommand.EXCLUDE_FOLDER -> false
        }
    if (satisfied) return ChatListBatchOutcome.SATISFIED
    val actions = row.actions
    val allowed =
        when (command) {
            ChatListBatchCommand.READ -> actions.canMarkRead
            ChatListBatchCommand.UNREAD -> actions.canMarkUnread
            ChatListBatchCommand.MUTE -> actions.canMute
            ChatListBatchCommand.UNMUTE -> actions.canUnmute
            ChatListBatchCommand.PIN -> !state.archived && actions.canPin
            ChatListBatchCommand.UNPIN -> actions.canUnpin
            ChatListBatchCommand.ARCHIVE -> actions.canArchive
            ChatListBatchCommand.RESTORE -> actions.canRestore
            ChatListBatchCommand.EXCLUDE_FOLDER -> true
        }
    return if (allowed) null else ChatListBatchOutcome.SKIPPED
}

/** Each selected contiguous run crosses one neighbour; both relative orders remain stable. */
internal fun moveSelectedPinnedChats(
    order: List<String>,
    selected: Set<String>,
    delta: Int,
): List<String> {
    require(delta == -1 || delta == 1)
    val ids = order.toMutableList()
    val selectedKeys = selected.map { it.lowercase(java.util.Locale.ROOT) }.toSet()

    fun isSelected(id: String) = id.lowercase(java.util.Locale.ROOT) in selectedKeys
    val indices = if (delta < 0) 1..ids.lastIndex else (ids.lastIndex - 1 downTo 0)
    for (index in indices) {
        val next = index + delta
        if (isSelected(ids[index]) && !isSelected(ids[next])) {
            val previous = ids[next]
            ids[next] = ids[index]
            ids[index] = previous
        }
    }
    return ids
}

internal enum class ChatListPinMoveResult { MOVED, UNCHANGED, UNAVAILABLE, UNCONFIRMED }
