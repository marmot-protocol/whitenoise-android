package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.ConnectivitySignals
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Counter that increments once per host `ON_START`, so a `LaunchedEffect` keyed on it can detect a
 * foreground resume distinctly from any other recomposition.
 */
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

/**
 * Whether the host activity is currently stopped.
 *
 * A Compose `LaunchedEffect` follows composition lifetime, not the host activity lifecycle, so a
 * retained chat-list composition keeps running through STOP unless something explicitly gates it.
 * [RelayConnectivityPollingEffect] uses this to suspend its periodic poll — and the readiness call
 * it can trigger — while nothing is on screen to show it (#2815). Locked-screen notification
 * delivery does not depend on this poll: it runs through the process-owned notification runtime and
 * `NotificationNetworkRecoveryCoordinator`, both scoped to the application, not this composition.
 */
@Composable
internal fun rememberChatListPollingStopped(): Boolean {
    val lifecycleOwner = LocalLifecycleOwner.current
    var stopped by
        remember(lifecycleOwner) {
            mutableStateOf(!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        }
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> stopped = true
                    Lifecycle.Event.ON_START -> stopped = false
                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return stopped
}

/**
 * Polls relay health on a cadence that backs off once steady and connected, and nudges the
 * chat-list readiness check when relays read down with usable internet.
 *
 * Suspended entirely while [rememberChatListPollingStopped] reports the host activity stopped
 * (#2815): the poll delay, the relay-health read, and the readiness nudge all stop together, rather
 * than the surrounding timer running against a screen nobody can see. The poll resumes with one
 * fresh sample the instant the activity starts again. Takes its dependencies as callbacks, like the
 * sibling effects in this file, so a test can drive the loop under a manual clock without a real
 * [WhiteNoiseAppState] or [ChatsController].
 */
@Composable
@Suppress("FunctionNaming", "LongParameterList")
internal fun RelayConnectivityPollingEffect(
    effectOwner: Any,
    displayed: ConnectivityBannerState,
    foregroundEpoch: Int,
    connectivitySignals: () -> ConnectivitySignals,
    relaysConnectedFlow: Flow<Boolean>,
    refreshRelayConnectivity: suspend () -> Unit,
    revalidateConnectionReadiness: () -> Unit,
) {
    val currentDisplayed by rememberUpdatedState(displayed)
    val currentForegroundEpoch by rememberUpdatedState(foregroundEpoch)
    val stopped = rememberChatListPollingStopped()
    val currentStopped by rememberUpdatedState(stopped)
    LaunchedEffect(effectOwner) {
        val wakeEvents =
            relayPollWakeEvents(
                displayedStates = snapshotFlow { currentDisplayed },
                relaysConnected = relaysConnectedFlow,
                foregroundResumes = snapshotFlow { currentForegroundEpoch }.drop(1).map { },
            )
        snapshotFlow { currentStopped }.collectLatest { isStopped ->
            // A newer emission here — the activity stopping again — cancels whatever the previous
            // value started, which is exactly how collectLatest unwinds the running poll loop below.
            if (isStopped) return@collectLatest
            while (true) {
                refreshRelayConnectivity()
                val signals = connectivitySignals()
                if (signals.hasValidatedInternet && !signals.relaysConnected) {
                    revalidateConnectionReadiness()
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
}

/**
 * Re-evaluates chat-list readiness whenever validated internet appears, recovers, or drops, keyed so
 * the first signal after a bind only invalidates rather than assuming a prior attempt failed.
 */
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
                        if (!hasValidatedInternet) controller.invalidateConnectionReadinessOnNetworkLoss()
                    }
                    hasValidatedInternet -> controller.refreshConnectionReadiness()
                    else -> controller.invalidateConnectionReadinessOnNetworkLoss()
                }
            }
    }
}

/**
 * Revalidates chat-list readiness on the two edges the periodic poll would otherwise be alone in
 * catching: a foreground resume with usable internet, and relays reading disconnected while internet
 * is validated.
 */
@Composable
@Suppress("FunctionNaming", "LongParameterList") // Explicit lifecycle/relay dependencies keep the effect testable.
internal fun ConnectivityEdgeRefreshEffects(
    effectOwner: Any,
    activeAccountRef: String?,
    runtimeGeneration: Int,
    hasValidatedInternet: Boolean,
    relaysConnected: Boolean,
    foregroundEpoch: Int,
    revalidateConnectionReadiness: () -> Unit,
    revalidateOnForeground: () -> Unit = revalidateConnectionReadiness,
    revalidateOnRelaySample: () -> Unit = revalidateConnectionReadiness,
) {
    LaunchedEffect(effectOwner, activeAccountRef, runtimeGeneration, foregroundEpoch) {
        if (foregroundEpoch > 0 && hasValidatedInternet) revalidateOnForeground()
    }
    LaunchedEffect(effectOwner, activeAccountRef, runtimeGeneration, relaysConnected) {
        if (hasValidatedInternet && !relaysConnected) revalidateOnRelaySample()
    }
}
