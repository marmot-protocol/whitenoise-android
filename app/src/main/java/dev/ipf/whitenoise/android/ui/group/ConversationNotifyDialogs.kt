package dev.ipf.whitenoise.android.ui.group

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ChatNotifyMode
import dev.ipf.whitenoise.android.ui.theme.Dimens

private const val MUTE_HOUR_MILLIS = 3_600_000L
private const val MUTE_EIGHT_HOURS_MILLIS = 8 * MUTE_HOUR_MILLIS
private const val MUTE_ONE_WEEK_MILLIS = 7 * 24 * MUTE_HOUR_MILLIS
private const val MUTE_ALWAYS_MILLIS = 0L

@Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.
@Composable
internal fun MuteDurationDialog(
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    var selected by remember { mutableStateOf(MUTE_ALWAYS_MILLIS) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mute_dialog_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.mute_dialog_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = Dimens.spaceSm),
                )
                NotificationModeRow(R.string.mute_duration_8_hours, selected = selected == MUTE_EIGHT_HOURS_MILLIS) {
                    selected = MUTE_EIGHT_HOURS_MILLIS
                }
                NotificationModeRow(R.string.mute_duration_1_week, selected = selected == MUTE_ONE_WEEK_MILLIS) {
                    selected = MUTE_ONE_WEEK_MILLIS
                }
                NotificationModeRow(R.string.mute_duration_always, selected = selected == MUTE_ALWAYS_MILLIS) {
                    selected = MUTE_ALWAYS_MILLIS
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Suppress("FunctionNaming")
@Composable
internal fun NotifyForDialog(
    currentMode: ChatNotifyMode,
    onDismiss: () -> Unit,
    onSelect: (ChatNotifyMode) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notify_for)) },
        text = {
            Column {
                NotificationModeRow(R.string.notify_all_messages, selected = currentMode == ChatNotifyMode.ALL) {
                    onSelect(ChatNotifyMode.ALL)
                }
                NotificationModeRow(
                    R.string.notify_only_mentions,
                    selected = currentMode == ChatNotifyMode.MENTIONS_ONLY,
                ) { onSelect(ChatNotifyMode.MENTIONS_ONLY) }
            }
        },
        confirmButton = {},
    )
}

@Suppress("FunctionNaming")
@Composable
private fun NotificationModeRow(
    labelRes: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
                .padding(vertical = Dimens.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceLg),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(stringResource(labelRes), style = MaterialTheme.typography.bodyLarge)
    }
}
