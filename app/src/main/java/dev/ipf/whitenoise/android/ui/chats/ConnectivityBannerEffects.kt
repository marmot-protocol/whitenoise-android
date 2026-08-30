package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

@Composable
internal fun rememberConnectivityForegroundEpoch(): Int {
    val lifecycleOwner = LocalLifecycleOwner.current
    var foregroundEpoch by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START) foregroundEpoch++
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return foregroundEpoch
}

@Composable
@Suppress("FunctionNaming")
internal fun RelayConnectivityPollingEffect(
    appState: WhiteNoiseAppState,
    controller: ChatsController,
    displayed: ConnectivityBannerState,
    foregroundEpoch: Int,
) {
    val currentDisplayed by rememberUpdatedState(displayed)
    val currentForegroundEpoch by rememberUpdatedState(foregroundEpoch)
    LaunchedEffect(appState, controller) {
        val wakeEvents =
            relayPollWakeEvents(
                displayedStates = snapshotFlow { currentDisplayed },
                relaysConnected = appState.connectivitySignals.map { it.relaysConnected },
                foregroundResumes = snapshotFlow { currentForegroundEpoch }.drop(1).map { },
            )
        while (true) {
            appState.refreshRelayConnectivity()
            val signals = appState.connectivitySignals.value
            if (signals.hasValidatedInternet && !signals.relaysConnected) {
                controller.revalidateConnectionReadiness()
            }
            val pollDelay = relayPollDelayMillis(currentDisplayed, signals.relaysConnected)
            if (pollDelay == CONNECTIVITY_RELAY_POLL_MILLIS) {
                delay(pollDelay)
            } else {
                withTimeoutOrNull(pollDelay) { wakeEvents.first() }
            }
        }
    }
}

@Composable
@Suppress("FunctionNaming")
internal fun ValidatedInternetRefreshEffect(
    appState: WhiteNoiseAppState,
    controller: ChatsController,
    activeAccountRef: String?,
    runtimeGeneration: Int,
) {
    LaunchedEffect(controller, activeAccountRef, runtimeGeneration) {
        var firstSignal = true
        appState.connectivitySignals
            .map { it.hasValidatedInternet }
            .distinctUntilChanged()
            .collect { hasValidatedInternet ->
                when {
                    firstSignal -> {
                        firstSignal = false
                        if (!hasValidatedInternet) controller.invalidateConnectionReadiness()
                    }
                    hasValidatedInternet -> controller.refreshConnectionReadiness()
                    else -> controller.invalidateConnectionReadiness()
                }
            }
    }
}

@Composable
@Suppress("FunctionNaming")
internal fun ConnectivityEdgeRefreshEffects(
    effectOwner: Any,
    activeAccountRef: String?,
    runtimeGeneration: Int,
    hasValidatedInternet: Boolean,
    relaysConnected: Boolean,
    foregroundEpoch: Int,
    revalidateConnectionReadiness: () -> Unit,
) {
    LaunchedEffect(effectOwner, activeAccountRef, runtimeGeneration, foregroundEpoch) {
        if (foregroundEpoch > 0 && hasValidatedInternet) revalidateConnectionReadiness()
    }
    LaunchedEffect(effectOwner, activeAccountRef, runtimeGeneration, relaysConnected) {
        if (hasValidatedInternet && !relaysConnected) revalidateConnectionReadiness()
    }
}
