package dev.ipf.whitenoise.android.ui.share

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import kotlinx.coroutines.launch

/** One commit/dismiss owner shared by the toolbar, system Back and selected-recipient action. */
@Composable
internal fun rememberShareChatPickerActions(
    requestId: String,
    snackbarHostState: SnackbarHostState,
    onDismiss: () -> Unit,
    stage: suspend () -> Boolean,
    bindCommitting: (() -> Boolean) -> Unit,
): ShareChatPickerScaffoldActions {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val coroutineScope = rememberCoroutineScope()
    val stageRejectedMessage = stringResource(R.string.no_share_target_available)
    val finishingState = remember(requestId) { mutableStateOf(false) }
    var finishing by finishingState
    bindCommitting { finishingState.value }
    val dismissPicker: () -> Unit = {
        if (!finishing) {
            finishing = true
            runShareChatPickerDismissal(
                clearFocus = { focusManager.clearFocus(force = true) },
                hideKeyboard = { keyboardController?.hide() },
                dismiss = onDismiss,
            )
        }
    }
    return ShareChatPickerScaffoldActions(
        dismiss = dismissPicker,
        stage = {
            if (!finishing) {
                finishing = true
                coroutineScope.launch {
                    var committed = false
                    try {
                        committed = stage()
                        if (committed) {
                            runShareChatPickerDismissal(
                                clearFocus = { focusManager.clearFocus(force = true) },
                                hideKeyboard = { keyboardController?.hide() },
                                dismiss = onDismiss,
                            )
                        } else {
                            snackbarHostState.currentSnackbarData?.dismiss()
                            snackbarHostState.showSnackbar(stageRejectedMessage)
                        }
                    } finally {
                        if (!committed) finishing = false
                    }
                }
            }
        },
    )
}

internal data class ShareChatPickerScaffoldActions(
    val dismiss: () -> Unit,
    val stage: () -> Unit,
)
