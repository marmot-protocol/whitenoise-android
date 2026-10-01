@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.ConversationDictationController
import dev.ipf.whitenoise.android.audio.ConversationDictationFailure
import dev.ipf.whitenoise.android.audio.ConversationDictationState

/** Chooses the settings page that can actually clear a failure, and retry for the rest. */
@Composable
internal fun ConversationDictationFailureAction(
    state: ConversationDictationState.Failed,
    controller: ConversationDictationController,
) {
    val context = LocalContext.current
    val recovery = dictationFailureRecovery(state.cause ?: state.reason)
    val retrySend = state.reason == ConversationDictationFailure.SendBlocked
    var confirmPartialSend by remember(state) { mutableStateOf(false) }
    IconButton(
        onClick =
            when {
                retrySend && state.cause != null -> ({ confirmPartialSend = true })
                retrySend -> controller::retry
                recovery == ConversationDictationRecovery.AppSettings -> ({ openDictationAppSettings(context) })
                recovery == ConversationDictationRecovery.SpeechProviderSetup ->
                    ({ openSpeechProviderSetup(context, controller.speechProviderPackage) })
                else -> controller::retry
            },
        modifier = Modifier.size(48.dp),
    ) {
        Icon(
            imageVector =
                if (retrySend || recovery == ConversationDictationRecovery.Retry) {
                    Icons.Default.Refresh
                } else {
                    Icons.Default.Settings
                },
            contentDescription =
                stringResource(
                    when {
                        retrySend -> R.string.dictation_retry_send
                        recovery == ConversationDictationRecovery.AppSettings -> R.string.open_app_settings
                        recovery == ConversationDictationRecovery.SpeechProviderSetup -> R.string.dictation_open_speech_service
                        else -> R.string.retry
                    },
                ),
        )
    }
    if (confirmPartialSend) {
        ConversationDictationSendConfirmation(state, controller, recovery, onDismiss = { confirmPartialSend = false })
    }
}

/** Dialog callbacks keep the displayed failed session as their owner. */
@Composable
private fun ConversationDictationSendConfirmation(
    state: ConversationDictationState.Failed,
    controller: ConversationDictationController,
    recovery: ConversationDictationRecovery,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    ConversationDictationPartialSendDialog(
        onDismiss = onDismiss,
        onSend = {
            onDismiss()
            if (controller.state === state) controller.retry()
        },
        onPaste = {
            onDismiss()
            if (controller.state === state) controller.paste()
        },
        onOpenSettings =
            if (recovery == ConversationDictationRecovery.Retry) {
                null
            } else {
                {
                    when (recovery) {
                        ConversationDictationRecovery.AppSettings -> openDictationAppSettings(context)
                        ConversationDictationRecovery.SpeechProviderSetup -> openSpeechProviderSetup(context, controller.speechProviderPackage)
                        ConversationDictationRecovery.Retry -> Unit
                    }
                }
            },
        settingsLabel =
            stringResource(
                if (recovery == ConversationDictationRecovery.SpeechProviderSetup) R.string.dictation_open_speech_service else R.string.open_app_settings,
            ),
    )
}

/** Requires an explicit choice before sending a prefix after transcription ended unsuccessfully. */
@Composable
internal fun ConversationDictationPartialSendDialog(
    onDismiss: () -> Unit,
    onSend: () -> Unit,
    onPaste: () -> Unit,
    onOpenSettings: (() -> Unit)? = null,
    settingsLabel: String = "",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("dictation-partial-send-dialog"),
        title = { Text(stringResource(R.string.dictation_incomplete_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.dictation_incomplete_text),
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                )
                if (onOpenSettings != null) {
                    TextButton(onClick = onOpenSettings) { Text(settingsLabel) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSend) { Text(stringResource(R.string.dictation_send_recognized_text)) }
        },
        dismissButton = {
            TextButton(onClick = onPaste) { Text(stringResource(R.string.paste)) }
        },
    )
}
