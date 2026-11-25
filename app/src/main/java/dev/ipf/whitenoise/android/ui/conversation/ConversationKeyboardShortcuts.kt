@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import dev.ipf.whitenoise.android.ui.keyboard.ChatsReturnRoute
import dev.ipf.whitenoise.android.ui.keyboard.HardwareShortcutCommand
import dev.ipf.whitenoise.android.ui.keyboard.KeyboardShortcut
import dev.ipf.whitenoise.android.ui.keyboard.currentHardwareShortcutRouter

/**
 * Wraps [openSearch], the one in-chat search action that chat details, the optional header shortcut and
 * Ctrl+Shift+F share. The key opens that search, or focuses its field when search is already open (#4005).
 * Offered while [available], and side by side only while the conversation pane owns the keys. Returns [openSearch].
 */
@Composable
internal fun ConversationNavigationState.searchShortcut(
    available: Boolean,
    openSearch: () -> Unit,
): () -> Unit {
    KeyboardShortcut(HardwareShortcutCommand.ConversationSearch, available) {
        if (searchOpen) searchFocusRequester.requestFocus() else openSearch()
    }
    return openSearch
}

/**
 * Lets Ctrl+F and Ctrl+N leave a compact conversation through [exit], the header's Back action with its usual
 * route transition, while [available]. Chats then runs the command (#4005). While composed, even when not
 * [available], this conversation covers Chats, so a Chats screen retained for a route transition never takes the
 * keys. Side by side, Chats stays usable and runs the command itself.
 */
@Composable
internal fun ChatsReturnShortcut(
    available: Boolean,
    exit: () -> Unit,
) {
    val router = currentHardwareShortcutRouter()
    val currentAvailable by rememberUpdatedState(available)
    val currentExit by rememberUpdatedState(exit)
    val route = remember { ChatsReturnRoute({ currentAvailable }, { currentExit() }) }
    DisposableEffect(router, route) {
        val unregister = router?.registerChatsReturn(route)
        onDispose { unregister?.invoke() }
    }
}
