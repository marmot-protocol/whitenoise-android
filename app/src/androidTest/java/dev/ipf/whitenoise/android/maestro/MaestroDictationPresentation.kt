package dev.ipf.whitenoise.android.maestro

import androidx.compose.runtime.Composable
import dev.ipf.whitenoise.android.audio.ConversationDictationComposerAccess
import dev.ipf.whitenoise.android.audio.ConversationDictationComposerPhase
import dev.ipf.whitenoise.android.ui.conversation.composer.ConversationDictationMicrophoneDialog
import dev.ipf.whitenoise.android.ui.conversation.composer.ConversationDictationPartialSendDialog
import dev.ipf.whitenoise.android.ui.conversation.composer.ConversationDictationRecoveryPanel

/** Exercises presentation decisions without microphone capture, recognition or message submission. */
@Composable
@Suppress("FunctionNaming")
internal fun MaestroDictationPresentation(fixture: MaestroPresentationFixture) {
    when {
        fixture.scenario.startsWith("dictation-microphone") ->
            ConversationDictationMicrophoneDialog(
                onDismiss = { fixture.finish("dismiss") },
                onOpenSettings = { fixture.finish("settings-handoff") },
            )
        fixture.scenario.startsWith("dictation-partial") ->
            ConversationDictationPartialSendDialog(
                onDismiss = { fixture.finish("dismiss") },
                onSend = { fixture.finish("send-handoff") },
                sendEnabled = fixture.scenario != "dictation-partial-disabled",
            )
        else -> {
            val phase =
                when (fixture.scenario) {
                    "dictation-transcribing" -> ConversationDictationComposerPhase.Transcribing
                    "dictation-closing" -> ConversationDictationComposerPhase.ClosingMicrophone
                    "dictation-unavailable" -> ConversationDictationComposerPhase.AudioStateUnavailable
                    "dictation-other-chat" -> ConversationDictationComposerPhase.OtherConversation
                    "dictation-target-gone" -> ConversationDictationComposerPhase.TargetUnavailable
                    else -> ConversationDictationComposerPhase.RemainingAudio
                }
            ConversationDictationRecoveryPanel(
                access = ConversationDictationComposerAccess(1, 1, phase),
                retryEnabled = fixture.scenario != "dictation-retry-disabled",
                onRetry = { fixture.finish("retry") },
                onDiscard = { fixture.record("discard") },
                onDismiss = { fixture.finish("dismiss") },
            )
        }
    }
}
