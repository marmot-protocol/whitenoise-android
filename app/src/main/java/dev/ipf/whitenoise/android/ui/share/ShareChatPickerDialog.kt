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
import dev.ipf.whitenoise.android.ui.conversation.MeasuredPlaybackDialog
import dev.ipf.whitenoise.android.ui.settings.EmojiSetsScreen
import dev.ipf.whitenoise.android.ui.settings.SettingsRouteMotion

internal fun runShareChatPickerDismissal(
    clearFocus: () -> Unit,
    hideKeyboard: () -> Unit,
    dismiss: () -> Unit,
) {
    clearFocus()
    hideKeyboard()
    dismiss()
}

/**
 * Modal window boundary that prevents the underlying shell from receiving touch or accessibility focus.
 * Native modal, retained picker state and nested route share one presentation owner.
 */
@Composable
@Suppress("LongMethod")
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
    val protectedContent =
        appState.appLockScreenVisible || appState.signOutInProgress || !appState.allowChatScreenshotsInChats
    MeasuredPlaybackDialog(
        origin = null,
        // This completion retires the share request, including a successful cross-account stage.
        // Live account/privacy content is separately invalidated by SettingsRouteMotion below.
        ownerKey = requestId to payload,
        onDismissRequest = onDismiss,
        onSourceDismiss = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        // Retire captured frames across source/account/privacy boundaries. The external share
        // entry has no measured origin; only the internal button supplies one.
        SettingsRouteMotion(
            target = setAccount.takeIf { setAddress != null },
            depth = if (setAccount != null && setAddress != null) 1 else 0,
            ownerKey =
                listOf(
                    requestId,
                    payload,
                    appState.activeAccountRef,
                    appState.runtimeGeneration,
                    protectedContent,
                ),
            suppressMotion = protectedContent,
            contentVisible = !appState.appLockScreenVisible,
            cardMorph = !protectedContent,
        ) { targetAccount ->
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
                        onDismiss = dismiss,
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
}
