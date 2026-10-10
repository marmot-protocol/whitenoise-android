@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.ConversationDictationComposerAccess
import dev.ipf.whitenoise.android.audio.ConversationDictationComposerPhase
import dev.ipf.whitenoise.android.audio.ConversationDictationController
import dev.ipf.whitenoise.android.ui.common.fadingVerticalScroll

/** Dictate remains useful while the single capture owner holds unresolved audio or native closure. */
@Composable
internal fun rememberComposerDictationAction(
    controller: ConversationDictationController,
    accountRef: String,
    groupIdHex: String,
    draft: TextFieldValue,
    replyToMessageIdHex: String?,
): () -> Unit {
    var panelOwner by remember(controller, accountRef, groupIdHex) {
        mutableStateOf<ConversationDictationComposerAccess?>(null)
    }
    val binding = remember(controller, accountRef, groupIdHex) { ConversationDictationPanelBinding() }
    DisposableEffect(binding) { onDispose { binding.dispose() } }
    val access = controller.composerAccess(accountRef, groupIdHex)
    LaunchedEffect(access.sessionId, access.phase) {
        val resolved =
            access.phase == ConversationDictationComposerPhase.Ready &&
                panelOwner?.phase != ConversationDictationComposerPhase.Protected
        if (panelOwner?.sessionId != access.sessionId || resolved) panelOwner = null
    }
    if (panelOwner != null) {
        ConversationDictationRecoveryPanel(
            access =
                if (access.phase == ConversationDictationComposerPhase.Ready) checkNotNull(panelOwner) else access,
            retryEnabled = controller.canRetryComposerAudio(access),
            onRetry = {
                if (binding.active) {
                    controller.retryComposerAudio(access)
                    panelOwner = controller.composerAccess(accountRef, groupIdHex)
                }
            },
            onDiscard = { if (binding.active) controller.discardComposerAudio(access) },
            onKeepForLater = {
                if (binding.active && controller.keepComposerAudioForLater(access)) panelOwner = null
            },
            onDismiss = { panelOwner = null },
        )
    }
    return {
        if (binding.active) {
            val result = controller.requestComposerStart(accountRef, groupIdHex, draft, replyToMessageIdHex)
            if (result.phase != ConversationDictationComposerPhase.Ready) panelOwner = result
        }
    }
}

/** This nonmodal status does not replace the text field, attachments, Send arrow, or IME Send. */
@Composable
internal fun ConversationDictationRecoveryStatus(access: ConversationDictationComposerAccess) {
    Text(
        text = recoveryPhaseLabel(access),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).testTag("dictation-recovery-status"),
    )
}

/** Closing this panel keeps the existing recovery owner; no dismissal automatically starts recording. */
@Composable
internal fun ConversationDictationRecoveryPanel(
    access: ConversationDictationComposerAccess,
    retryEnabled: Boolean,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
    onDismiss: () -> Unit,
    onKeepForLater: () -> Unit = onDismiss,
) {
    var confirmDiscard by remember(access) { mutableStateOf(false) }
    val remainingAudio = access.phase == ConversationDictationComposerPhase.RemainingAudio
    if (confirmDiscard) {
        ConversationDictationDiscardConfirmation(
            onDiscard = {
                onDiscard()
                onDismiss()
            },
            onDismiss = { confirmDiscard = false },
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            modifier = Modifier.testTag("dictation-recovery-panel"),
            title = { Text(stringResource(R.string.dictation_remaining_audio)) },
            text = {
                Column(Modifier.fadingVerticalScroll(rememberScrollState())) {
                    Text(recoveryPhaseLabel(access))
                    access.failure?.let { Text(dictationFailureLabel(it)) }
                    if (remainingAudio) {
                        Text(stringResource(R.string.dictation_remaining_explanation))
                        TextButton(onClick = { confirmDiscard = true }) { Text(stringResource(R.string.discard)) }
                    }
                }
            },
            confirmButton = {
                if (remainingAudio) {
                    TextButton(onClick = onRetry, enabled = retryEnabled) {
                        Text(stringResource(R.string.dictation_retry_remaining))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = onKeepForLater) { Text(stringResource(R.string.dictation_keep_later)) }
            },
        )
    }
}

@Composable
private fun ConversationDictationDiscardConfirmation(
    onDiscard: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("dictation-discard-confirmation"),
        title = { Text(stringResource(R.string.discard)) },
        text = { Text(stringResource(R.string.dictation_discard_remaining)) },
        confirmButton = { TextButton(onClick = onDiscard) { Text(stringResource(R.string.discard)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun recoveryPhaseLabel(access: ConversationDictationComposerAccess): String =
    when (access.phase) {
        ConversationDictationComposerPhase.Ready -> stringResource(R.string.dictate_text)
        ConversationDictationComposerPhase.RemainingAudio -> stringResource(R.string.dictation_stopped_remaining)
        ConversationDictationComposerPhase.Transcribing -> stringResource(R.string.dictation_processing)
        ConversationDictationComposerPhase.ClosingMicrophone -> stringResource(R.string.dictation_finishing_microphone)
        ConversationDictationComposerPhase.AudioStateUnavailable ->
            stringResource(R.string.dictation_audio_state_unavailable)
        ConversationDictationComposerPhase.OtherConversation -> stringResource(R.string.dictation_original_conversation)
        ConversationDictationComposerPhase.TargetUnavailable -> stringResource(R.string.conversation_unavailable)
        ConversationDictationComposerPhase.Protected ->
            access.failure?.let { dictationFailureLabel(it) } ?: stringResource(R.string.dictation_preparing)
    }
