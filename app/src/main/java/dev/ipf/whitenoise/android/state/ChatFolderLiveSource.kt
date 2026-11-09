package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select

/** Which native source the chat list reads: every row at once, and the selected folder whose windows open too. */
internal data class ChatFolderSourceSpec(
    val complete: Boolean = false,
    val folder: ChatFolderQuery? = null,
)

/** Folder presentation switches sources without retiring account-owned mutations or reconnecting the account. */
internal class ChatFolderLiveSource {
    val complete = MutableStateFlow(false)

    /** The selected folder's native query, whose windows load its chats beyond the plain windows (#3652). */
    val folder = MutableStateFlow<ChatFolderQuery?>(null)

    /** The source the next window set should open. */
    val current: ChatFolderSourceSpec get() = ChatFolderSourceSpec(complete.value, folder.value)

    /** Retires the current receive pair promptly, awaiting cancellation before the owner closes its handles. */
    suspend fun receiveUntilChanged(
        expected: ChatFolderSourceSpec,
        receive: suspend () -> Unit,
    ): Boolean =
        coroutineScope {
            val stream =
                async {
                    receive()
                    false
                }
            val changed =
                async {
                    combine(complete, folder, ::ChatFolderSourceSpec).first { it != expected }
                    true
                }
            try {
                select {
                    changed.onAwait { it }
                    stream.onAwait { it }
                }
            } finally {
                stream.cancel()
                changed.cancel()
            }
        }
}

/**
 * Alternate folder ordering needs every native row; bounded recent windows remain the default, and a selected
 * [folder] opens its own native windows beside them so its chats load beyond the plain windows.
 */
internal suspend fun ChatListLiveSubscriptions.openFolderSource(
    account: String,
    complete: Boolean,
    folder: ChatFolderQuery? = null,
): ChatListWindowSet =
    if (complete) {
        ChatListWindowSet.openComplete(account, requireNotNull(openPresentedChatList))
    } else {
        val folderWindows = folder?.let { query -> openChatListQueryWindow?.let { ChatFolderWindows(query, it) } }
        ChatListWindowSet.open(account, openPresentedChatList, folderWindows, openChatListWindow)
    }
