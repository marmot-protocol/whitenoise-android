@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.ConversationDictationComposerAccess
import dev.ipf.whitenoise.android.audio.ConversationDictationComposerPhase
import dev.ipf.whitenoise.android.audio.ConversationDictationController
import dev.ipf.whitenoise.android.audio.ConversationDictationTranslation
import dev.ipf.whitenoise.android.audio.queueDictation
import dev.ipf.whitenoise.android.audio.requestFreshComposerStart

/** Dictate starts a fresh attempt after safe local settlement, with no recovery decision pop-ups. */
@Composable
@Suppress("LongParameterList")
internal fun rememberComposerDictationAction(
    controller: ConversationDictationController,
    accountRef: String,
    groupIdHex: String,
    draft: TextFieldValue,
    replyToMessageIdHex: String?,
    translation: () -> ConversationDictationTranslation? = { null },
): () -> Unit {
    val binding = remember(controller, accountRef, groupIdHex) { ConversationDictationPanelBinding() }
    DisposableEffect(binding) { onDispose { binding.dispose() } }
    return {
        if (binding.active && controller.queuesFromComposer(accountRef, groupIdHex)) {
            // Another chat's dictation is still being transcribed; this recording waits its turn.
            controller.queueDictation(accountRef, groupIdHex, replyToMessageIdHex, translation())
        } else if (binding.active) {
            controller.requestFreshComposerStart(
                accountRef,
                groupIdHex,
                draft,
                replyToMessageIdHex,
                translation(),
            )
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
