package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R

internal const val AUDIT_REDACTION_SWITCH_TAG = "audit_redaction_switch"

@Composable
internal fun AuditRedactionSwitch(
    checked: Boolean,
    enabled: Boolean,
    busy: Boolean,
    onApplyRedaction: (Boolean) -> Unit,
) {
    var fullDataConfirmOpen by remember { mutableStateOf(false) }

    SettingsSwitchRow(
        title = stringResource(R.string.redact_audit_data),
        subtitle = stringResource(R.string.redact_audit_data_subtitle),
        checked = checked,
        enabled = enabled && !busy,
        busy = busy,
        switchModifier = Modifier.testTag(AUDIT_REDACTION_SWITCH_TAG),
        onCheckedChange = { redact ->
            if (redact) {
                onApplyRedaction(true)
            } else {
                fullDataConfirmOpen = true
            }
        },
    )
    if (fullDataConfirmOpen) {
        AlertDialog(
            onDismissRequest = { fullDataConfirmOpen = false },
            title = { Text(stringResource(R.string.redact_audit_data_confirm_title)) },
            text = { Text(stringResource(R.string.redact_audit_data_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        fullDataConfirmOpen = false
                        onApplyRedaction(false)
                    },
                ) {
                    Text(
                        stringResource(R.string.redact_audit_data_confirm_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { fullDataConfirmOpen = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
