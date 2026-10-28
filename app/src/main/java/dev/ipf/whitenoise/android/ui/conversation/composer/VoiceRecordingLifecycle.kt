package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.VoiceRecordingController
import dev.ipf.whitenoise.android.audio.VoiceRecordingStopReason
import dev.ipf.whitenoise.android.state.TransientNotice
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState

/** Capture belongs to the visible conversation; backgrounding preserves a reviewable take. */
@Composable
@Suppress("FunctionNaming")
internal fun VoiceRecordingLifecycleEffect(controller: VoiceRecordingController) {
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val recording = controller.isRecording
    DisposableEffect(view, recording) {
        val previous = view.keepScreenOn
        if (recording) view.keepScreenOn = true
        onDispose { if (recording) view.keepScreenOn = previous }
    }
    DisposableEffect(lifecycle, controller) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE) controller.interrupt()
            }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val warning = stringResource(R.string.voice_recording_limit_warning)
    LaunchedEffect(controller, controller.isNearLimit) {
        if (controller.isNearLimit) {
            @Suppress("DEPRECATION")
            view.announceForAccessibility(warning)
        }
    }
    val haptics = LocalHapticFeedback.current
    val lastSeconds = recording && controller.remainingMs <= VoiceRecordingController.LIMIT_HAPTIC_MS
    LaunchedEffect(controller, lastSeconds) {
        if (lastSeconds) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
}

/** Keeps automatic-stop feedback with its unsent review, never in the app-wide snackbar queue. */
@Composable
internal fun rememberVoiceRecordingStopNotice(
    review: VoiceRecordingReview,
    appState: WhiteNoiseAppState,
    accountRef: String?,
    groupId: String,
): (VoiceRecordingStopReason, VoiceReviewClip?) -> Unit {
    val notice =
        remember(review, appState) { mutableStateOf<Pair<VoiceReviewClip, TransientNotice>?>(null) }
    DisposableEffect(review, appState) {
        onDispose { notice.value?.second?.let(appState::clearTransientNotice) }
    }
    LaunchedEffect(review.clip) {
        val owned = notice.value
        if (owned != null && review.clip !== owned.first) {
            appState.clearTransientNotice(owned.second)
            if (notice.value === owned) notice.value = null
        }
    }
    return remember(review, appState, accountRef, groupId) {
        onStop@{ reason: VoiceRecordingStopReason, completedClip: VoiceReviewClip? ->
            if (accountRef == null || completedClip == null) return@onStop
            if (appState.activeAccountRef == accountRef && review.clip === completedClip) {
                notice.value =
                    Pair(
                        completedClip,
                        appState.presentConversationTransient(
                            accountRef,
                            groupId,
                            when (reason) {
                                VoiceRecordingStopReason.LimitReached -> R.string.voice_recording_limit_reached
                                VoiceRecordingStopReason.Interrupted -> R.string.voice_recording_interrupted
                            },
                        ),
                    )
            }
        }
    }
}
