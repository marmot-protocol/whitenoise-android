package dev.ipf.whitenoise.android.ui.chats

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
 * Whether the shell may run the one-shot conversation-return snap decision.
 * Returns false while the active list head is unknown or scroll restoration is
 * still in flight so [shouldSnapChatListOnConversationReturn] is not evaluated
 * against a transient snapshot.
 */
internal fun canDecideConversationReturnHeadSnap(
    headIdAtConversationOpen: String?,
    currentHeadId: String?,
    isScrollInProgress: Boolean,
    isActiveList: Boolean,
): Boolean {
    if (headIdAtConversationOpen == null) return false
    if (!isActiveList) return true
    if (currentHeadId == null || isScrollInProgress) return false
    return true
}

/**
 * Snap decision when the shell returns from an in-app conversation and a
 * different chat became head while the list was off-screen (issue #1313).
 * [headIdAtConversationOpen] is the visible active-list head captured when
 * leaving ChatsScreen; [currentHeadId] must be the same visible-head notion.
 * Call only after [canDecideConversationReturnHeadSnap] is true.
 */
internal fun shouldSnapChatListOnConversationReturn(
    headIdAtConversationOpen: String?,
    currentHeadId: String?,
    isActiveList: Boolean,
): Boolean {
    if (!isActiveList) return false
    if (headIdAtConversationOpen == null || currentHeadId == null) return false
    if (currentHeadId == headIdAtConversationOpen) return false
    return true
}
