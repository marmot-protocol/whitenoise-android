package dev.ipf.whitenoise.android.state

import android.util.Log
import dev.ipf.marmotkit.MarmotKitException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

/** Retry spacing while MarmotKit is still preparing the presentation a folder window's query reads. */
private const val FOLDER_WINDOW_RETRY_INITIAL_MS = 500L
private const val FOLDER_WINDOW_RETRY_MAX_MS = 5_000L

/**
 * Keeps the selected folder's own windows open beside [plain] until cancelled (#3652). Each folder change closes the
 * previous folder windows and opens the new ones without touching the plain windows. A folder whose presentation is
 * not ready yet retries with backoff, an ended folder stream reopens, and a refused query leaves the folder on the
 * loaded rows its members match. Every change republishes the merged rows through [plain]'s frame guard.
 */
internal suspend fun ChatsController.runFolderWindows(
    account: String,
    plain: ChatListWindowSet,
) {
    val open = liveSubscriptions.openChatListQueryWindow ?: return
    try {
        folderSource.folder.collectLatest { query ->
            replaceFolderWindows(null)
            applyChatListWindowRows(account, plain)
            if (query != null) keepFolderWindowsOpen(account, plain, ChatFolderWindows(query, open))
        }
    } finally {
        withContext(NonCancellable) { replaceFolderWindows(null) }
    }
}

/**
 * Opens [folder]'s windows, retrying while MarmotKit is not ready, and reopens them whenever their stream ends. No
 * folder failure escapes, so it can never end the plain windows' receive loop.
 */
@Suppress("TooGenericExceptionCaught") // A folder window failure of any kind falls back to the loaded rows.
private suspend fun ChatsController.keepFolderWindowsOpen(
    account: String,
    plain: ChatListWindowSet,
    folder: ChatFolderWindows,
) {
    var backoff = FOLDER_WINDOW_RETRY_INITIAL_MS
    while (!plain.closed) {
        val windows =
            try {
                ChatListWindowSet.openFolder(account, folder)
            } catch (_: MarmotKitException.ChatPresentationNotReady) {
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (refused: Exception) {
                Log.w("DMChats", "${releaseFailureMarker("CHAT_FOLDER_WINDOW_OPEN", refused)} fallback=loaded_rows")
                return
            }
        if (windows != null) {
            backoff = FOLDER_WINDOW_RETRY_INITIAL_MS
            replaceFolderWindows(windows)
            applyChatListWindowRows(account, plain)
            runCatchingCancellable { windows.receive { _, _ -> applyChatListWindowRows(account, plain) } }
                .exceptionOrNull()
                ?.let { Log.w("DMChats", "${releaseFailureMarker("CHAT_FOLDER_WINDOW_RECEIVE", it)} reopening=true") }
            replaceFolderWindows(null)
            applyChatListWindowRows(account, plain)
        }
        delay(backoff)
        backoff = (backoff * 2).coerceAtMost(FOLDER_WINDOW_RETRY_MAX_MS)
    }
}

/**
 * Loads the next page of the selected folder's own windows, in every view it covers that has more; false when the
 * folder has no windows open or nothing newer was installed.
 */
suspend fun ChatsController.loadMoreFolderChats(): Boolean {
    val windows = folderWindows?.takeUnless { it.closed } ?: return false
    val paged =
        CHAT_LIST_WINDOW_VIEWS
            .filter { windows.hasMoreAfter(it, folder = true) }
            .count { windows.pageForward(it, folder = true) != null }
    if (paged > 0) republishWithFolderRows()
    return paged > 0
}

/** Reports the folder chat the user sees to the folder window holding it, so a capped window can page past it. */
suspend fun ChatsController.reportVisibleFolderChat(groupIdHex: String) {
    val windows = folderWindows?.takeUnless { it.closed } ?: return
    val view =
        CHAT_LIST_WINDOW_VIEWS.firstOrNull { view ->
            windows.installed(view, folder = true)?.rows?.any { it.row.groupIdHex == groupIdHex } == true
        }
    if (view != null && windows.setVisibleAnchor(view, groupIdHex, folder = true) != null) republishWithFolderRows()
}

/** Republishes the plain rows with the folder's, through the plain set's frame guard. */
private suspend fun ChatsController.republishWithFolderRows() {
    val plain = chatListWindows
    val account = accountRef
    if (plain != null && account != null) applyChatListWindowRows(account, plain)
}

/** Whether the selected folder's rows load through its own native windows rather than the plain ones. */
fun ChatsController.hasFolderWindows(): Boolean = folderWindowsOpen
