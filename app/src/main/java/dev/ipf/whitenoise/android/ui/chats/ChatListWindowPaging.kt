package dev.ipf.whitenoise.android.ui.chats

/** Whether the retained front is close enough to request the rows that precede it. */
internal fun shouldPageChatListBackward(
    firstVisibleIndex: Int,
    hasMoreBefore: Boolean,
    searchActive: Boolean,
    prefetchRows: Int,
): Boolean =
    !searchActive &&
        hasMoreBefore &&
        firstVisibleIndex >= 0 &&
        firstVisibleIndex < prefetchRows

/** Whether the jump-to-top control must remain available for a shifted bounded window. */
internal fun shouldShowChatListJumpToTop(
    firstVisibleIndex: Int,
    hasMoreBefore: Boolean,
    wasVisible: Boolean,
    showIndex: Int,
    hideIndex: Int,
): Boolean =
    when {
        hasMoreBefore -> true
        firstVisibleIndex >= showIndex -> true
        firstVisibleIndex <= hideIndex -> false
        else -> wasVisible
    }
