@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.composer

import android.content.Context
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.ConversationDictationController
import dev.ipf.whitenoise.android.audio.ConversationDictationFailure
import dev.ipf.whitenoise.android.audio.ConversationDictationState
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.filled.Refresh
import dev.ipf.whitenoise.android.ui.icons.filled.Settings

/** Chooses the settings page that can actually clear a failure, and retry for the rest. */
@Composable
internal fun ConversationDictationFailureAction(
    state: ConversationDictationState.Failed,
    controller: ConversationDictationController,
) {
    val context = LocalContext.current
    val recovery = dictationFailureRecovery(state.cause ?: state.reason)
    val retrySend = state.reason == ConversationDictationFailure.SendBlocked
    val recoveredSendAvailable =
        controller.hasUnrecoveredTranscript(state) ||
            controller.canRetryRetainedAudio ||
            controller.canRetryRecoveredSend
    val settingsRecoveryAvailable = state.recognitionIncomplete && recovery != ConversationDictationRecovery.Retry
    IconButton(
        enabled =
            !retrySend ||
                recoveredSendAvailable ||
                settingsRecoveryAvailable,
        onClick = dictationFailureAction(state, controller, recovery, context),
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
                stringResource(dictationFailureActionLabel(retrySend, state.recognitionIncomplete, recovery)),
        )
    }
}

private fun dictationFailureAction(
    state: ConversationDictationState.Failed,
    controller: ConversationDictationController,
    recovery: ConversationDictationRecovery,
    context: Context,
): () -> Unit =
    when {
        state.reason == ConversationDictationFailure.SendBlocked && state.recognitionIncomplete ->
            ({ if (controller.state === state) controller.finishFailedToDraft(state.sessionId) })
        state.reason == ConversationDictationFailure.SendBlocked -> controller::retry
        recovery == ConversationDictationRecovery.AppSettings -> ({ openDictationAppSettings(context) })
        recovery == ConversationDictationRecovery.SpeechProviderSetup ->
            ({ openSpeechProviderSetup(context, controller.speechProviderPackage) })
        else -> controller::retry
    }

private fun dictationFailureActionLabel(
    retrySend: Boolean,
    recognitionIncomplete: Boolean,
    recovery: ConversationDictationRecovery,
): Int =
    when {
        retrySend && recognitionIncomplete -> R.string.paste
        retrySend -> R.string.dictation_retry_send
        recovery == ConversationDictationRecovery.AppSettings -> R.string.open_app_settings
        recovery == ConversationDictationRecovery.SpeechProviderSetup -> R.string.dictation_open_speech_service
        else -> R.string.retry
    }
