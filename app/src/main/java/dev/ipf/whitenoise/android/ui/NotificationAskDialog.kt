package dev.ipf.whitenoise.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.ConfirmDialog

/**
 * Shows the explanation the app owes before Android's notification permission dialog (#4278) while [suppressed] is
 * false. Turn on notifications calls [onLaunchSystemDialog]; Not now, Back or a tap outside records the attempt, as a
 * system denial does, so it is never asked again automatically.
 */
@Composable
@Suppress("FunctionNaming")
internal fun NotificationAskHost(
    appState: WhiteNoiseAppState,
    suppressed: Boolean,
    onLaunchSystemDialog: () -> Unit,
) {
    val moment = appState.notificationAskMoment
    if (!moment.explanationVisible || suppressed || appState.appLock.screenVisible) return
    NotificationAskDialog(
        onConfirm = {
            moment.explanationVisible = false
            onLaunchSystemDialog()
        },
        onDismiss = {
            moment.explanationVisible = false
            appState.markDefaultNotificationsEnableAttempted()
        },
    )
}

/** Why notifications help, with the action that opens Android's permission dialog. */
@Composable
@Suppress("FunctionNaming")
internal fun NotificationAskDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmDialog(
        title = stringResource(R.string.notification_permission_ask_title),
        message = stringResource(R.string.notification_permission_ask_message),
        confirmLabel = stringResource(R.string.notification_permission_ask_confirm),
        dismissLabel = stringResource(R.string.notification_permission_ask_not_now),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        ownerKey = Unit,
    )
}
