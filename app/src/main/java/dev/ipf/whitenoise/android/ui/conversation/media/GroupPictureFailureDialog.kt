package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.AnimatedAlertDialog

/** The viewer keeps draft ownership, retry and cancellation; this renders the existing failure controls. */
@Composable
@Suppress("FunctionNaming")
internal fun GroupPictureFailureDialog(
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val currentOnRetry = rememberUpdatedState(onRetry)
    AnimatedAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.toast_couldnt_update_group)) },
        text = { Text(stringResource(R.string.error_try_again)) },
        confirmButton = {
            TextButton(onClick = { close { currentOnRetry.value() } }) { Text(stringResource(R.string.retry)) }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
