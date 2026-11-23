package dev.ipf.whitenoise.android.ui.common

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ipf.whitenoise.android.state.ToastMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.noteToastSeen
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.takeWhile

/**
 * Shows the app's toast in the root snackbar and retires it when the snackbar ends. Most toasts run even while app lock
 * hides the slot, so a confirmation is never shown twice. A group-change notice is acknowledged once its snackbar has
 * run to the end, so it runs only while [onScreen] holds: it waits while the app is locked or in the background, and
 * one they interrupt starts again once the app is back on screen. [retryLabel] names a local delete's retry action.
 */
@Composable
@Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.
internal fun RootToastSnackbarEffect(
    appState: WhiteNoiseAppState,
    snackbarHostState: SnackbarHostState,
    retryLabel: String,
    onScreen: Flow<Boolean> = rememberAppOnScreen(appState),
) {
    val toast = appState.toast
    val context = LocalContext.current
    LaunchedEffect(toast, retryLabel) {
        toast ?: return@LaunchedEffect
        var ended = false

        suspend fun show() {
            val result = snackbarHostState.showSnackbar(toastSnackbarVisuals(toast, context, retryLabel))
            ended = true
            appState.noteToastSeen(toast)
            finishLocalDeleteSnackbar(appState, toast, result)
        }
        if (toast.groupChangeKey == null) {
            show()
        } else {
            // Leaving the screen cancels the snackbar, and one that ran to the end is not shown again.
            onScreen.takeWhile { !ended }.collectLatest { visible -> if (visible) show() }
        }
    }
}

/** The snackbar for [toast]: a local delete's title with its detail behind Details, any other toast's whole text. */
private fun toastSnackbarVisuals(
    toast: ToastMessage,
    context: Context,
    retryLabel: String,
): ToastSnackbarVisuals {
    val deletion = toast.localDeleteNotice
    return ToastSnackbarVisuals(
        message =
            if (deletion != null) {
                toast.title.resolve(context)
            } else {
                listOfNotNull(toast.title.resolve(context), toast.detail?.resolve(context)).joinToString("\n")
            },
        copyable = toast.copyable,
        tier = toast.tier,
        copyText = toast.diagnosticReport,
        details =
            deletion?.let {
                listOfNotNull(toast.detail?.resolve(context), toast.diagnosticReport).joinToString("\n\n")
            },
        actionLabel = deletion?.retry?.let { retryLabel },
    )
}

/** Whether the app is on screen for the user: started, and neither locked nor deciding whether to lock. */
@Composable
private fun rememberAppOnScreen(appState: WhiteNoiseAppState): Flow<Boolean> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return remember(lifecycle, appState) {
        combine(
            lifecycle.currentStateFlow,
            snapshotFlow { appState.appLock.screenVisible || appState.appLock.evaluationPending },
        ) { state, locked -> state.isAtLeast(Lifecycle.State.STARTED) && !locked }.distinctUntilChanged()
    }
}
