package dev.ipf.whitenoise.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ipf.whitenoise.android.audio.ConversationDictationController
import dev.ipf.whitenoise.android.audio.ConversationDictationState
import dev.ipf.whitenoise.android.audio.clearMicrophoneRecovery
import dev.ipf.whitenoise.android.audio.onMicrophoneHostPaused
import dev.ipf.whitenoise.android.audio.onMicrophoneHostResumed
import dev.ipf.whitenoise.android.audio.onMicrophoneOriginChanged
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState

@Suppress("FunctionNaming")
@Composable
internal fun ConversationDictationMicrophoneEffects(
    appState: WhiteNoiseAppState,
    dictation: ConversationDictationController,
    dictationState: ConversationDictationState,
) {
    LaunchedEffect(
        dictationState,
        appState.activeAccountRef,
        appState.activeConversationGroupIdHex,
        appState.pendingProfileNpub,
    ) {
        if (appState.pendingProfileNpub != null) dictation.clearMicrophoneRecovery()
        dictation.onMicrophoneOriginChanged(appState.activeAccountRef, appState.activeConversationGroupIdHex)
    }
    val microphoneLifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(dictation, microphoneLifecycle) {
        // App lock may defer acceptance, but a profile or replacement conversation consumes the return.
        dictation.microphoneOriginValid = {
            val target = dictation.state.target
            target != null &&
                appState.pendingProfileNpub == null &&
                target.matchesConversation(
                    appState.activeAccountRef.orEmpty(),
                    appState.activeConversationGroupIdHex.orEmpty(),
                )
        }
        dictation.microphoneHostReady = {
            val target = dictation.state.target
            microphoneLifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                target != null &&
                target.matchesConversation(
                    appState.activeAccountRef.orEmpty(),
                    appState.activeConversationGroupIdHex.orEmpty(),
                ) &&
                appState.isConversationDictationOriginVisible(target.accountRef, target.groupIdHex)
        }
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_PAUSE -> dictation.onMicrophoneHostPaused()
                    Lifecycle.Event.ON_RESUME -> dictation.onMicrophoneHostResumed()
                    Lifecycle.Event.ON_STOP -> {
                        if (dictation.microphoneSettingsRecovery?.native == true) dictation.clearMicrophoneRecovery()
                    }
                    else -> Unit
                }
            }
        microphoneLifecycle.addObserver(observer)
        onDispose {
            microphoneLifecycle.removeObserver(observer)
            dictation.microphoneOriginValid = { false }
            dictation.microphoneHostReady = { false }
            dictation.clearMicrophoneRecovery()
        }
    }
}
