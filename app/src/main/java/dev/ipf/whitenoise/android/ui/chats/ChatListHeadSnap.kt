package dev.ipf.whitenoise.android.ui.chats

/**
 * Snap decision when the active chat-list head identity changes while the user
 * is still on the list (issue #541). Only corrects the clipped-head case at
 * item 0 so a reader scrolled deeper is not yanked to the top.
 */
internal fun shouldSnapChatListForClippedHeadReorder(
    previousHeadId: String?,
    currentHeadId: String?,
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
    isScrollInProgress: Boolean,
    isActiveList: Boolean,
): Boolean {
    if (!isActiveList) return false
    if (previousHeadId == null || currentHeadId == null || currentHeadId == previousHeadId) return false
    if (isScrollInProgress) return false
    return firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset > 0
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
