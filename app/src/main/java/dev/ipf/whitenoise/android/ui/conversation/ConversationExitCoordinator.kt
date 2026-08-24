package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import dev.ipf.whitenoise.android.ui.common.activity

/**
 * Serializes the transition from a mounted conversation to the chat list.
 *
 * When the IME still owns an inset, navigation waits for the zero-inset edge.
 * This preserves the final inset dispatch before focus is detached and prevents
 * the chat list from inheriting the conversation's keyboard-sized viewport.
 * Repeated requests retry dismissal but remain coalesced until the inset closes.
 */
internal class ConversationExitCoordinator {
    var awaitingImeDismiss: Boolean = false
        private set

    private var completing = false

    fun requestExit(
        imeIsOpen: Boolean,
        hideIme: () -> Unit,
        clearComposerFocus: () -> Unit,
        navigate: () -> Unit,
    ) {
        if (completing) return

        if (awaitingImeDismiss) {
            hideIme()
            if (!imeIsOpen) {
                awaitingImeDismiss = false
                complete(clearComposerFocus, navigate)
            }
            return
        }

        hideIme()
        if (imeIsOpen) {
            awaitingImeDismiss = true
        } else {
            complete(clearComposerFocus, navigate)
        }
    }

    fun onImeVisibilityChanged(
        imeIsOpen: Boolean,
        clearComposerFocus: () -> Unit,
        navigate: () -> Unit,
    ) {
        if (!awaitingImeDismiss || imeIsOpen) return
        awaitingImeDismiss = false
        complete(clearComposerFocus, navigate)
    }

    private fun complete(
        clearComposerFocus: () -> Unit,
        navigate: () -> Unit,
    ) {
        if (completing) return
        completing = true
        try {
            clearComposerFocus()
            navigate()
        } finally {
            completing = false
        }
    }
}

@Composable
internal fun rememberConversationExitHandler(
    identity: Any? = Unit,
    imeIsOpen: Boolean,
    routeToChatList: () -> Unit,
): () -> Unit {
    val focusManager = LocalFocusManager.current
    val view = LocalView.current
    val imeController =
        remember(view) {
            val activity = requireNotNull(view.context.activity())
            WindowCompat.getInsetsController(activity.window, view)
        }
    val currentImeIsOpen = rememberUpdatedState(imeIsOpen)
    val currentRouteToChatList = rememberUpdatedState(routeToChatList)
    val coordinator = remember(identity) { ConversationExitCoordinator() }

    LaunchedEffect(coordinator, imeIsOpen, focusManager) {
        if (!imeIsOpen && coordinator.awaitingImeDismiss) {
            coordinator.onImeVisibilityChanged(
                imeIsOpen = false,
                clearComposerFocus = { focusManager.clearFocus(force = true) },
                navigate = { currentRouteToChatList.value() },
            )
        }
    }

    return remember(coordinator, focusManager) {
        {
            coordinator.requestExit(
                imeIsOpen = currentImeIsOpen.value,
                hideIme = { imeController.hide(WindowInsetsCompat.Type.ime()) },
                clearComposerFocus = { focusManager.clearFocus(force = true) },
                navigate = { currentRouteToChatList.value() },
            )
        }
    }
}
