package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.whitenoise.android.state.ChatListItem

/**
 * Snap decision when the active chat-list head identity changes while the
 * user is still on the list (issues #541, #1376). Decided from the scroll
 * position captured BEFORE the reorder: LazyColumn tracks the previously
 * anchored row by key across a reorder, so once a new head lands above the
 * viewport the post-reorder snapshot already reads index 1 / offset 0 — a
 * post-reorder `firstVisibleItemIndex == 0` guard can never fire for a reader
 * sitting flush at the top, exactly the reader for whom the promoted chat is
 * invisible. A reader at the true top (pre-reorder index 0, any offset,
 * including flush) is watching the head and must be snapped to the new one; a
 * reader scrolled deeper (pre-reorder index > 0) is never yanked.
 */
internal fun shouldSnapChatListForHeadReorder(
    previousHeadId: String?,
    currentHeadId: String?,
    preReorderFirstVisibleItemIndex: Int,
    isScrollInProgress: Boolean,
    isActiveList: Boolean,
): Boolean {
    val headChanged = previousHeadId != null && currentHeadId != null && currentHeadId != previousHeadId
    return isActiveList &&
        headChanged &&
        !isScrollInProgress &&
        preReorderFirstVisibleItemIndex == 0
}

/**
 * Visible active-list head captured when leaving ChatsScreen (issues #1313, #3280).
 * [headId] is the absolute first row. [activityHeadId] is the first unpinned row —
 * the slot a sent-to chat is promoted into while pinned chats keep [headId]
 * unchanged — or null when every visible row is pinned.
 */
internal data class ChatListReturnHead(
    val headId: String,
    val activityHeadId: String?,
)

/** Captures the [ChatListReturnHead] of the filtered [visibleItems], or null for an empty list. */
internal fun chatListReturnHead(visibleItems: List<ChatListItem>): ChatListReturnHead? {
    val head = visibleItems.firstOrNull() ?: return null
    return ChatListReturnHead(
        headId = head.id,
        activityHeadId = visibleItems.firstOrNull { !it.pinned() }?.id,
    )
}

/**
 * Whether the shell may run the one-shot conversation-return snap decision.
 * Returns false while the active list head is unknown or scroll restoration is
 * still in flight so [shouldSnapChatListOnConversationReturn] is not evaluated
 * against a transient snapshot.
 */
internal fun canDecideConversationReturnHeadSnap(
    headAtConversationOpen: ChatListReturnHead?,
    currentHead: ChatListReturnHead?,
    isScrollInProgress: Boolean,
    isActiveList: Boolean,
): Boolean {
    if (headAtConversationOpen == null) return false
    if (!isActiveList) return true
    if (currentHead == null || isScrollInProgress) return false
    return true
}

/**
 * Snap decision when the shell returns from an in-app conversation and the
 * list head moved while the list was off-screen: a different chat became the
 * absolute head (issue #1313), or a chat was promoted to the top of the
 * unpinned section beneath an unchanged pinned head (issue #3280).
 * [headAtConversationOpen] is the visible active-list head captured when
 * leaving ChatsScreen; [currentHead] must be the same visible-head notion.
 * Call only after [canDecideConversationReturnHeadSnap] is true.
 */
internal fun shouldSnapChatListOnConversationReturn(
    headAtConversationOpen: ChatListReturnHead?,
    currentHead: ChatListReturnHead?,
    isActiveList: Boolean,
): Boolean {
    if (!isActiveList) return false
    if (headAtConversationOpen == null || currentHead == null) return false
    return currentHead != headAtConversationOpen
}
