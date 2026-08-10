@file:Suppress("FunctionName")

package dev.ipf.whitenoise.android.ui.share

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.ipf.whitenoise.android.share.SharePayload
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState

internal fun runShareChatPickerDismissal(
    clearFocus: () -> Unit,
    hideKeyboard: () -> Unit,
    dismiss: () -> Unit,
) {
    clearFocus()
    hideKeyboard()
    dismiss()
}

/** Modal window boundary that prevents the underlying shell from receiving touch or accessibility focus. */
@Composable
internal fun ShareChatPickerFullScreen(
    appState: WhiteNoiseAppState,
    requestId: String = "",
    payload: SharePayload,
    onDismiss: () -> Unit,
    onStage: (List<String>) -> Unit,
    overlayBackRegistrar: ShareChatPickerOverlayBackRegistrar? = null,
) {
    Dialog(
        onDismissRequest = {},
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        ShareChatPickerFullScreenContent(
            appState = appState,
            requestId = requestId,
            payload = payload,
            onDismiss = onDismiss,
            onStage = onStage,
            overlayBackRegistrar = overlayBackRegistrar,
        )
    }
}
