package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ChatListViewFfi

/**
 * Loads the next page of the selected folder's own windows, in every view it covers that has more; false when the
 * folder has no windows open or nothing newer was installed.
 */
suspend fun ChatsController.loadMoreFolderChats(): Boolean {
    val windows = chatListWindows?.takeIf { it.hasFolderWindows }
    val account = accountRef
    if (windows == null || account == null) return false
    val paged =
        CHAT_LIST_WINDOW_VIEWS
            .filter { windows.hasMoreAfter(it, folder = true) }
            .count { windows.pageForward(it, folder = true) != null }
    if (paged > 0) applyChatListWindowRows(account, windows)
    return paged > 0
}

/** Reports the folder chat the user sees, so a capped folder window can keep paging past it. */
suspend fun ChatsController.reportVisibleFolderChat(
    groupIdHex: String,
    view: ChatListViewFfi,
) {
    val windows = chatListWindows?.takeIf { it.hasFolderWindows } ?: return
    val account = accountRef ?: return
    if (windows.setVisibleAnchor(view, groupIdHex, folder = true) != null) applyChatListWindowRows(account, windows)
}

/** Whether the selected folder's rows load through its own native windows rather than the plain ones. */
fun ChatsController.hasFolderWindows(): Boolean = chatListWindows?.hasFolderWindows == true
