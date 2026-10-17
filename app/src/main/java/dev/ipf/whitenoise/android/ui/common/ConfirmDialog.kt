package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R

/**
 * A yes/no confirmation on the app dialog; a destructive confirm takes the error role, and [dismissLabel] replaces
 * Cancel.
 */
@Suppress("FunctionNaming")
@Composable
internal fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    dismissLabel: String? = null,
    identity: (@Composable () -> Unit)? = null,
    confirmEnabled: Boolean = true,
) {
    WhiteNoiseAlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = confirmEnabled,
                modifier = Modifier.testTag("confirmation.confirm"),
                colors =
                    if (destructive) {
                        ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    } else {
                        ButtonDefaults.textButtonColors()
                    },
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("confirmation.cancel")) {
                Text(dismissLabel ?: stringResource(R.string.cancel))
            }
        },
        title = { Text(title) },
        text = {
            if (identity == null) {
                Text(message)
            } else {
                Column(
                    Modifier.fadingVerticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(message)
                    identity()
                }
            }
        },
    )
}
