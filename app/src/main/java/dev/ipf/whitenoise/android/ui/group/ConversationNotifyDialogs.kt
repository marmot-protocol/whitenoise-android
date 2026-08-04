package dev.ipf.whitenoise.android.ui.group

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.ConversationVibrationPattern
import dev.ipf.whitenoise.android.notifications.previewConversationVibration
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
internal fun VibrationPatternDialog(
    currentPattern: ConversationVibrationPattern,
    onDismiss: () -> Unit,
    onSelect: (ConversationVibrationPattern) -> Unit,
) {
    val context = LocalContext.current
    var selected by remember(currentPattern) { mutableStateOf(currentPattern) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vibration_pattern)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.vibration_pattern_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = Dimens.spaceSm),
                )
                ConversationVibrationPattern.entries.forEach { pattern ->
                    VibrationPatternRow(
                        pattern = pattern,
                        selected = selected == pattern,
                        onSelect = { selected = pattern },
                        onPreview = { previewConversationVibration(context, pattern) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSelect(selected) }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Suppress("FunctionNaming")
@Composable
private fun VibrationPatternRow(
    pattern: ConversationVibrationPattern,
    selected: Boolean,
    onSelect: () -> Unit,
    onPreview: () -> Unit,
) {
    val label = vibrationPatternLabel(pattern)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = onPreview) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = stringResource(R.string.preview_vibration_pattern, label),
            )
        }
    }
}

@Composable
internal fun vibrationPatternLabel(pattern: ConversationVibrationPattern): String =
    stringResource(
        when (pattern) {
            ConversationVibrationPattern.SYSTEM_DEFAULT -> R.string.vibration_pattern_system_default
            ConversationVibrationPattern.SHORT -> R.string.vibration_pattern_short
            ConversationVibrationPattern.DOUBLE -> R.string.vibration_pattern_double
            ConversationVibrationPattern.LONG -> R.string.vibration_pattern_long
        },
    )

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
