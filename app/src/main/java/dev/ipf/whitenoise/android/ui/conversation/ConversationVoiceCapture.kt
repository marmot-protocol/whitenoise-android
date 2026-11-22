package dev.ipf.whitenoise.android.ui.conversation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.VoiceNoteKey
import dev.ipf.whitenoise.android.audio.VoiceRecordingController
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.composer.VoiceRecordingLifecycleEffect
import dev.ipf.whitenoise.android.ui.conversation.composer.VoiceRecordingReview
import dev.ipf.whitenoise.android.ui.conversation.composer.VoiceReviewRecoveryMarker
import dev.ipf.whitenoise.android.ui.conversation.composer.VoiceReviewUnavailableNotice
import dev.ipf.whitenoise.android.ui.conversation.composer.rememberVoiceRecordingStopNotice
import dev.ipf.whitenoise.android.ui.conversation.composer.rememberVoiceReviewRecoveryMarker
import kotlinx.coroutines.CoroutineScope

/**
 * What one conversation presents of its voice note: the screen-scoped review, the recorder bound from the
 * process-level owner, and the marker for a take lost with the process.
 */
@Stable
internal class ConversationVoiceCapture(
    val review: VoiceRecordingReview,
    val recorder: VoiceRecordingController,
    val recovery: VoiceReviewRecoveryMarker,
    /** True while the account, runtime and controller binding this capture was created for are still live. */
    val ownerIsCurrent: () -> Boolean,
)

/**
 * Presents the conversation's voice note. The recorder and any unsent take belong to the process-level owner, so a
 * locked take keeps recording and a finished take waits in review after this chat closes. Only explicit Send
 * transfers the file to the native sender.
 */
@Composable
internal fun rememberConversationVoiceCapture(
    appState: WhiteNoiseAppState,
    controller: ConversationController,
    mediaSender: ConversationMediaSender,
    scope: CoroutineScope,
): ConversationVoiceCapture {
    val conversationAccountRef = controller.boundAccountRef
    val key =
        remember(conversationAccountRef, controller) {
            VoiceNoteKey(conversationAccountRef.orEmpty(), controller.group.groupIdHex)
        }
    val owner = appState.voiceNotes
    val voiceReviewRecovery = rememberVoiceReviewRecoveryMarker(conversationAccountRef, controller.group.groupIdHex)
    val voiceReviewRuntime = appState.runtimeGeneration
    val ownerIsCurrent =
        remember(controller, conversationAccountRef, voiceReviewRuntime) {
            {
                conversationAccountRef != null &&
                    appState.activeAccountRef == conversationAccountRef &&
                    appState.runtimeGeneration == voiceReviewRuntime &&
                    controller.boundAccountRef == conversationAccountRef
            }
        }
    val voiceReview =
        remember(controller, conversationAccountRef, voiceReviewRuntime, mediaSender, key) {
            VoiceRecordingReview(
                scope = scope,
                ownerIsCurrent = ownerIsCurrent,
                send = { file, duration, canSend, onQueued ->
                    mediaSender.sendVoiceAttachment(file, duration, canSend, onQueued)
                },
                onSendFailure = { appState.present(R.string.send_failed) },
                onPlaybackFailure = { appState.present(R.string.voice_message_failed) },
                deleteFile = { file -> owner.consume(key, file) },
                onSendInterrupted = { appState.present(R.string.voice_send_interrupted) },
            )
        }
    DisposableEffect(voiceReview) {
        onDispose { voiceReview.detach() }
    }
    val requestPermission = rememberVoicePermissionRequest(appState, LocalContext.current)
    val voiceRecordingController = remember(key, owner) { owner.bind(key, requestPermission) }
    DisposableEffect(voiceRecordingController) {
        onDispose { owner.unbind(key, voiceRecordingController) }
    }
    VoiceRecordingLifecycleEffect(voiceRecordingController)
    VoiceDraftPresentation(appState, key, voiceReview, voiceRecordingController, voiceReviewRecovery)
    return remember(voiceReview, voiceRecordingController, voiceReviewRecovery, ownerIsCurrent) {
        ConversationVoiceCapture(voiceReview, voiceRecordingController, voiceReviewRecovery, ownerIsCurrent)
    }
}

/**
 * Shows the owner's draft in review, explains an automatic stop once, reports a take lost with the process, and
 * drops the waiting draft when a new recording starts in this chat.
 */
@Composable
@Suppress("FunctionNaming")
private fun VoiceDraftPresentation(
    appState: WhiteNoiseAppState,
    key: VoiceNoteKey,
    voiceReview: VoiceRecordingReview,
    voiceRecordingController: VoiceRecordingController,
    voiceReviewRecovery: VoiceReviewRecoveryMarker,
) {
    val owner = appState.voiceNotes
    val voiceRecordingStopNotice =
        rememberVoiceRecordingStopNotice(voiceReview, appState, key.accountRef, key.groupIdHex)
    val draft = owner.draft(key)
    LaunchedEffect(voiceReview, draft) {
        if (draft == null || !voiceReview.adopt(draft.file, draft.durationMs)) return@LaunchedEffect
        val completedClip = voiceReview.clip
        draft.stopReason?.let { reason ->
            voiceRecordingStopNotice(reason, completedClip)
            owner.acknowledgeStop(key)
        }
    }
    LaunchedEffect(owner, key) {
        if (owner.takeLost(key)) voiceReviewRecovery.updatePresence(true)
    }
    // A new recording discards a pending review, including one a quick restart's previous tail just offered.
    LaunchedEffect(voiceRecordingController.isRecording, voiceReview.clip) {
        if (voiceRecordingController.isRecording) {
            voiceReview.discardForNewRecording()
            voiceReviewRecovery.updatePresence(false)
        }
    }
}

/** Asks for the microphone once per screen and reports a denial; returns whether recording may start now. */
@Composable
private fun rememberVoicePermissionRequest(
    appState: WhiteNoiseAppState,
    context: Context,
): () -> Boolean {
    val micPermissionDeniedMsg = stringResource(R.string.voice_message_permission_denied)
    var voiceMicPermissionRequested by remember { mutableStateOf(false) }
    val voiceMicPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted -> if (!granted) appState.present(micPermissionDeniedMsg) }
    return {
        val granted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted && !voiceMicPermissionRequested) {
            voiceMicPermissionRequested = true
            voiceMicPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        granted
    }
}

/** Shows the voice-review recovery notice when Activity recreation lost an unsent take. */
@Composable
@Suppress("FunctionNaming")
internal fun ConversationVoiceRecoveryNotice(capture: ConversationVoiceCapture) {
    val voiceReviewRecovery = capture.recovery
    val voiceRecordingController = capture.recorder
    if (voiceReviewRecovery.hasReview && capture.review.clip == null && !voiceRecordingController.isRecording) {
        VoiceReviewUnavailableNotice(
            onDismiss = { voiceReviewRecovery.updatePresence(false) },
            onRecordAgain = {
                var accepted = false
                voiceReviewRecovery.consume {
                    if (capture.ownerIsCurrent() && voiceRecordingController.start()) {
                        voiceReviewRecovery.updatePresence(false)
                        voiceRecordingController.lock()
                        accepted = true
                    }
                }
                accepted
            },
        )
    }
}
