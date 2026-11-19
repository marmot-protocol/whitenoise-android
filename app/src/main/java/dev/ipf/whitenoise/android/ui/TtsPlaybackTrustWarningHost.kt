package dev.ipf.whitenoise.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ipf.whitenoise.android.audio.tts.TtsTrustWarningDialog
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState

@Composable
@Suppress("FunctionNaming")
internal fun TtsPlaybackTrustWarningHost(appState: WhiteNoiseAppState) {
    val request = appState.pendingTtsTrustWarning ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val eligible = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && request.isCurrent()
    LaunchedEffect(request, eligible) {
        if (!eligible) request.respond(false)
    }
    DisposableEffect(request, lifecycle) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE) request.respond(false)
            }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            request.respond(false)
        }
    }
    if (eligible) {
        TtsTrustWarningDialog(
            ownerKey = request,
            onProceed = {
                request.respond(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            },
            onDismiss = { request.respond(false) },
        )
    }
}
