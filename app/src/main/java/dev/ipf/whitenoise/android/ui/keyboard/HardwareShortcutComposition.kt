@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.keyboard

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import dev.ipf.whitenoise.android.MainActivity

/** Replaces the window's router, for tests and previews hosted by an activity without one. */
internal val LocalHardwareShortcutRouter = staticCompositionLocalOf<HardwareShortcutRouter?> { null }

/** The router for this window, or null in previews and hosts without hardware shortcuts. */
@Composable
internal fun currentHardwareShortcutRouter(): HardwareShortcutRouter? =
    LocalHardwareShortcutRouter.current ?: (LocalActivity.current as? MainActivity)?.hardwareShortcuts

/**
 * Registers [onInvoke] as this composition's owner of [command], offered while [available]. A screen passes the
 * lambda its on-screen control already calls. A command carried from a compact conversation runs here once this
 * owner can take it.
 */
@Composable
internal fun KeyboardShortcut(
    command: HardwareShortcutCommand,
    available: Boolean,
    onInvoke: () -> Unit,
) {
    val router = currentHardwareShortcutRouter()
    val currentAvailable by rememberUpdatedState(available)
    val currentInvoke by rememberUpdatedState(onInvoke)
    val handler = remember(command) { HardwareShortcutHandler(command, { currentAvailable }, { currentInvoke() }) }
    DisposableEffect(router, handler) {
        val unregister = router?.register(handler)
        onDispose { unregister?.invoke() }
    }
    LaunchedEffect(router, handler) {
        // A carried command waits until this owner is available and no compact conversation covers Chats.
        val owner = router ?: return@LaunchedEffect
        snapshotFlow { owner.canTakeCarried(handler) }.collect { canTake ->
            if (canTake) owner.claimCarried(handler)
        }
    }
}

/**
 * Makes hardware shortcuts unavailable while an in-window surface that owns input is composed: every command, or
 * with [chatsOnly] just the Chats commands, for a surface that replaces Chats beside an open conversation.
 */
@Composable
internal fun HardwareShortcutBlocker(chatsOnly: Boolean = false) {
    val router = currentHardwareShortcutRouter()
    DisposableEffect(router, chatsOnly) {
        val release = router?.block(chatsOnly)
        onDispose { release?.invoke() }
    }
}

/**
 * Tracks which side-by-side pane owns keyboard commands: the pane holding focus, otherwise the pane pressed
 * last. Until either pane is focused or pressed, neither owns them. Ctrl+Shift+F belongs to the conversation pane.
 */
@Stable
internal class ChatPaneKeyOwner {
    var listFocused by mutableStateOf(false)
    var conversationFocused by mutableStateOf(false)

    /** The pane pressed last, true for the list, or null until a pane is pressed. */
    var listPressedLast by mutableStateOf<Boolean?>(null)

    /** True when the conversation pane owns keyboard commands. */
    val conversationOwnsKeys: Boolean
        get() =
            when {
                conversationFocused -> true
                listFocused -> false
                else -> listPressedLast == false
            }
}

/** Remembers the pane key owner for a two-pane frame and reports it, with [twoPane], to the window's router. */
@Composable
internal fun rememberChatPaneKeyOwner(twoPane: Boolean): ChatPaneKeyOwner {
    val router = currentHardwareShortcutRouter()
    val owner = remember { ChatPaneKeyOwner() }
    val currentTwoPane by rememberUpdatedState(twoPane)
    DisposableEffect(router, owner) {
        val detach =
            router?.attachPanes(split = { currentTwoPane }, conversationOwnsKeys = { owner.conversationOwnsKeys })
        onDispose { detach?.invoke() }
    }
    LaunchedEffect(twoPane) {
        // A removed list pane reports no focus loss, so a new split starts with neither pane owning the keys.
        if (!twoPane) {
            owner.listFocused = false
            owner.listPressedLast = null
        }
    }
    return owner
}

/** Records focus within, and presses on, the list pane ([list]) or the conversation pane for [owner]. */
internal fun Modifier.chatPaneKeyInput(
    owner: ChatPaneKeyOwner,
    list: Boolean,
): Modifier =
    onFocusChanged { state ->
        if (list) owner.listFocused = state.hasFocus else owner.conversationFocused = state.hasFocus
    }.pointerInput(owner, list) {
        awaitPointerEventScope {
            while (true) {
                // Observed on the initial pass and never consumed, so the pane's own gestures are unchanged.
                if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) {
                    owner.listPressedLast = list
                }
            }
        }
    }
