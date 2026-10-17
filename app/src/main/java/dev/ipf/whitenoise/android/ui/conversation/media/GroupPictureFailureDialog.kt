package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R

/** The viewer keeps draft ownership, retry and cancellation; this renders the existing failure controls. */
@Composable
@Suppress("FunctionNaming")
internal fun GroupPictureFailureDialog(
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.toast_couldnt_update_group)) },
        text = { Text(stringResource(R.string.error_try_again)) },
        confirmButton = { TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
