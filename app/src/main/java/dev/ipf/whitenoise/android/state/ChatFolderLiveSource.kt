package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select

/** Folder presentation switches sources without retiring account-owned mutations or reconnecting the account. */
internal class ChatFolderLiveSource {
    val complete = MutableStateFlow(false)

    /** Retires the current receive pair promptly, awaiting cancellation before the owner closes its handles. */
    suspend fun receiveUntilChanged(
        expected: Boolean,
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
                    complete.first { it != expected }
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

/** Alternate folder ordering needs every native row; bounded recent windows remain the default. */
internal suspend fun ChatListLiveSubscriptions.openFolderSource(
    account: String,
    complete: Boolean,
): ChatListWindowSet =
    if (complete) {
        ChatListWindowSet.openComplete(account, requireNotNull(openPresentedChatList))
    } else {
        ChatListWindowSet.open(account, openPresentedChatList, openChatListWindow)
    }
