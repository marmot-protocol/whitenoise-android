@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import dev.ipf.whitenoise.android.ui.keyboard.ChatsReturnRoute
import dev.ipf.whitenoise.android.ui.keyboard.HardwareShortcutCommand
import dev.ipf.whitenoise.android.ui.keyboard.KeyboardShortcut
import dev.ipf.whitenoise.android.ui.keyboard.currentHardwareShortcutRouter

/**
 * Whether this conversation is the shell's selected one. Beside the list, tapping another chat retires the open
 * conversation but keeps it on screen until the tapped chat is ready, and that retained pane must not take the
 * keys meanwhile (#4005). True by default, for screens shown outside the shell.
 */
internal val LocalConversationOwnsShortcuts = compositionLocalOf { true }

/**
 * Wraps [openSearch], the one in-chat search action that chat details, the optional header shortcut and
 * Ctrl+Shift+F share. The key opens that search, or focuses its field when search is already open (#4005).
 * Offered while [available] and this conversation is selected, and side by side only while the conversation pane
 * owns the keys. Returns [openSearch].
 */
@Composable
internal fun ConversationNavigationState.searchShortcut(
    available: Boolean,
    openSearch: () -> Unit,
): () -> Unit {
    val owned = available && LocalConversationOwnsShortcuts.current
    KeyboardShortcut(HardwareShortcutCommand.ConversationSearch, owned) {
        if (searchOpen) searchFocusRequester.requestFocus() else openSearch()
    }
    return openSearch
}

/**
 * Lets Ctrl+F and Ctrl+N leave a compact conversation through [exit], the header's Back action with its usual
 * route transition, while [available] and this conversation is selected. Chats then runs the command (#4005).
 * While composed, even when not [available], this conversation covers Chats, so a Chats screen retained for a
 * route transition never takes the keys. Side by side, Chats stays usable and runs the command itself.
 */
@Composable
internal fun ChatsReturnShortcut(
    available: Boolean,
    exit: () -> Unit,
) {
    val router = currentHardwareShortcutRouter()
    val currentAvailable by rememberUpdatedState(available && LocalConversationOwnsShortcuts.current)
    val currentExit by rememberUpdatedState(exit)
    val route = remember { ChatsReturnRoute({ currentAvailable }, { currentExit() }) }
    DisposableEffect(router, route) {
        val unregister = router?.registerChatsReturn(route)
        onDispose { unregister?.invoke() }
    }
}
