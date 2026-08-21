package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * Ends conversation-owned text input before routing away from the screen.
 *
 * [exit] is deliberately one-shot because the outgoing conversation remains
 * composed during the shell's back-slide animation and can receive duplicate
 * navigation requests until that transition settles.
 */
internal class ConversationExitCoordinator(
    private val clearFocus: () -> Unit,
    private val hideIme: () -> Unit,
    private val routeToChatList: () -> Unit,
) {
    private var exitRequested = false

    fun exit() {
        if (exitRequested) return
        exitRequested = true
        clearFocus()
        hideIme()
        routeToChatList()
    }
}

@Composable
internal fun rememberConversationExitCoordinator(
    identity: Any? = Unit,
    routeToChatList: () -> Unit,
): ConversationExitCoordinator {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val currentRouteToChatList = rememberUpdatedState(routeToChatList)
    return remember(identity, focusManager, keyboardController) {
        ConversationExitCoordinator(
            clearFocus = { focusManager.clearFocus(force = true) },
            hideIme = { keyboardController?.hide() },
            routeToChatList = { currentRouteToChatList.value() },
        )
    }
}
