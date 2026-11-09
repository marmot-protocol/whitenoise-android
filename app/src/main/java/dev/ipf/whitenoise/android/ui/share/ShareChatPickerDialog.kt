@file:Suppress("FunctionName")

package dev.ipf.whitenoise.android.ui.share

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.DialogProperties
import dev.ipf.whitenoise.android.core.emojiSetAddress
import dev.ipf.whitenoise.android.share.SharePayload
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.PlaybackDialog
import dev.ipf.whitenoise.android.ui.settings.EmojiSetsScreen

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
    onStage: suspend (String, List<String>) -> Boolean,
    overlayBackRegistrar: ShareChatPickerOverlayBackRegistrar? = null,
    controllerFactory: (WhiteNoiseAppState) -> ChatsController = { ChatsController(it) },
    controllerBinder: suspend (ChatsController, String) -> Unit = { controller, accountRef ->
        controller.bind(accountRef)
    },
) {
    val hasStreams = payload.streamUris.isNotEmpty()
    val setAddress =
        remember(payload.text, hasStreams) {
            payload.text?.takeUnless { hasStreams }?.let(::emojiSetAddress)
        }
    var setAccount by rememberSaveable(requestId, appState.activeAccountRef) { mutableStateOf<String?>(null) }
    val pickerState = rememberSaveableStateHolder()
    PlaybackDialog(
        onDismissRequest = {},
        onSourceDismiss = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        val targetAccount = setAccount
        if (targetAccount != null && setAddress != null) {
            EmojiSetsScreen(
                appState,
                onBack = { setAccount = null },
                initialAddress = setAddress,
                targetAccount = targetAccount,
            )
        } else {
            pickerState.SaveableStateProvider(requestId) {
                ShareChatPickerFullScreenContent(
                    appState = appState,
                    requestId = requestId,
                    payload = payload,
                    onDismiss = onDismiss,
                    onStage = onStage,
                    overlayBackRegistrar = overlayBackRegistrar,
                    controllerFactory = controllerFactory,
                    controllerBinder = controllerBinder,
                    onOpenEmojiSet = if (setAddress == null) null else ({ setAccount = it }),
                )
            }
        }
    }
}
