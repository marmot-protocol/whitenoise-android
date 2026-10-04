package dev.ipf.whitenoise.android.ui.share

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlinx.coroutines.launch

/** One commit/dismiss owner shared by the toolbar, system Back and selected-recipient action. */
@Composable
internal fun rememberShareChatPickerActions(
    requestId: String,
    onDismiss: () -> Unit,
    stage: suspend () -> Boolean,
    bindCommitting: (() -> Boolean) -> Unit,
): ShareChatPickerScaffoldActions {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val coroutineScope = rememberCoroutineScope()
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
                    try {
                        if (stage()) {
                            runShareChatPickerDismissal(
                                clearFocus = { focusManager.clearFocus(force = true) },
                                hideKeyboard = { keyboardController?.hide() },
                                dismiss = onDismiss,
                            )
                        }
                    } finally {
                        finishing = false
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
