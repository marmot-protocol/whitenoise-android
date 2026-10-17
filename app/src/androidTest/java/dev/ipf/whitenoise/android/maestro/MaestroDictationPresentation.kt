package dev.ipf.whitenoise.android.maestro

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import dev.ipf.whitenoise.android.audio.ConversationDictationComposerAccess
import dev.ipf.whitenoise.android.audio.ConversationDictationComposerPhase
import dev.ipf.whitenoise.android.audio.ConversationDictationFailure
import dev.ipf.whitenoise.android.ui.conversation.composer.ConversationDictationMicrophoneDialog
import dev.ipf.whitenoise.android.ui.conversation.composer.ConversationDictationRecoveryStatus

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
        fixture.scenario.startsWith("dictation-draft-") -> MaestroDictationDraftPresentation(fixture)
        else -> {
            val phase =
                when (fixture.scenario) {
                    "dictation-status-ready" -> ConversationDictationComposerPhase.Ready
                    "dictation-status-transcribing" -> ConversationDictationComposerPhase.Transcribing
                    "dictation-status-closing" -> ConversationDictationComposerPhase.ClosingMicrophone
                    "dictation-status-unavailable" -> ConversationDictationComposerPhase.AudioStateUnavailable
                    "dictation-status-other-chat" -> ConversationDictationComposerPhase.OtherConversation
                    "dictation-status-target-gone" -> ConversationDictationComposerPhase.TargetUnavailable
                    "dictation-status-protected" -> ConversationDictationComposerPhase.Protected
                    "dictation-status-remaining" -> ConversationDictationComposerPhase.RemainingAudio
                    else -> error("Unregistered dictation presentation scenario")
                }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ConversationDictationRecoveryStatus(
                    ConversationDictationComposerAccess(
                        sessionId = 1,
                        actionGeneration = 1,
                        phase = phase,
                        failure =
                            if (phase == ConversationDictationComposerPhase.Protected) {
                                ConversationDictationFailure.Network
                            } else {
                                null
                            },
                    ),
                )
            }
        }
    }
}
