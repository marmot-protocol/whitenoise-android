package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.VoiceRecordingController

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
