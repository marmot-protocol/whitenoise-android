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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import dev.ipf.whitenoise.android.MainActivity

/** Replaces [MainActivity]'s router, for tests and previews hosted by another activity. */
internal val LocalHardwareShortcutRouter = staticCompositionLocalOf<HardwareShortcutRouter?> { null }

/** The router for this window, or null in previews and hosts without hardware shortcuts. */
@Composable
internal fun currentHardwareShortcutRouter(): HardwareShortcutRouter? =
    LocalHardwareShortcutRouter.current ?: (LocalActivity.current as? MainActivity)?.hardwareShortcuts

/**
 * Registers [onInvoke] as this composition's owner of [command], offered while [available], and returns
 * [onInvoke] unchanged so a screen can wrap the lambda its on-screen control already calls. A command carried
 * from a compact conversation runs here once this owner becomes available.
 */
@Composable
internal fun rememberHardwareShortcut(
    command: HardwareShortcutCommand,
    available: Boolean,
    onInvoke: () -> Unit,
): () -> Unit {
    val router = currentHardwareShortcutRouter()
    val currentAvailable by rememberUpdatedState(available)
    val currentInvoke by rememberUpdatedState(onInvoke)
    val handler = remember(command) { HardwareShortcutHandler(command, { currentAvailable }, { currentInvoke() }) }
    DisposableEffect(router, handler) {
        val unregister = router?.register(handler)
        onDispose { unregister?.invoke() }
    }
    LaunchedEffect(router, handler, available) {
        if (available) router?.claimCarried(handler)
    }
    return onInvoke
}

/** Makes every hardware shortcut unavailable while an in-window surface that owns input is composed. */
@Composable
internal fun HardwareShortcutBlocker() {
    val router = currentHardwareShortcutRouter()
    DisposableEffect(router) {
        val release = router?.block()
        onDispose { release?.invoke() }
    }
}

/**
 * Tracks which side-by-side pane owns keyboard commands: the pane holding focus, otherwise the pane pressed
 * last. Ctrl+Shift+F belongs to the conversation pane only.
 */
@Stable
internal class ChatPaneKeyOwner {
    var listFocused by mutableStateOf(false)
    var conversationFocused by mutableStateOf(false)
    var listPressedLast by mutableStateOf(false)

    /** True when the list pane, not the conversation pane, owns keyboard commands. */
    val listOwnsKeys: Boolean
        get() =
            when {
                conversationFocused -> false
                listFocused -> true
                else -> listPressedLast
            }
}

/** Remembers the pane key owner for a two-pane frame and reports it, with [twoPane], to the window's router. */
@Composable
internal fun rememberChatPaneKeyOwner(twoPane: Boolean): ChatPaneKeyOwner {
    val router = currentHardwareShortcutRouter()
    val owner = remember { ChatPaneKeyOwner() }
    val currentTwoPane by rememberUpdatedState(twoPane)
    DisposableEffect(router, owner) {
        val detach = router?.attachPanes(split = { currentTwoPane }, listOwnsKeys = { owner.listOwnsKeys })
        onDispose { detach?.invoke() }
    }
    LaunchedEffect(twoPane) {
        // A removed list pane reports no focus loss, so a new split starts from the conversation pane.
        if (!twoPane) {
            owner.listFocused = false
            owner.listPressedLast = false
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
