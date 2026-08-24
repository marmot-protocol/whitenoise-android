package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * Serializes the transition from a mounted conversation to the chat list.
 *
 * When the IME still owns an inset, navigation waits for the zero-inset edge.
 * This preserves the final inset dispatch before focus is detached and prevents
 * the chat list from inheriting the conversation's keyboard-sized viewport.
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
            awaitingImeDismiss = false
            hideIme()
            complete(clearComposerFocus, navigate)
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
    val keyboardController = LocalSoftwareKeyboardController.current
    val currentImeIsOpen = rememberUpdatedState(imeIsOpen)
    val currentKeyboardController = rememberUpdatedState(keyboardController)
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
                hideIme = { currentKeyboardController.value?.hide() },
                clearComposerFocus = { focusManager.clearFocus(force = true) },
                navigate = { currentRouteToChatList.value() },
            )
        }
    }
}
