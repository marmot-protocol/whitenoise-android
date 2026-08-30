package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ChatListConnectionPhase
import dev.ipf.whitenoise.android.state.ChatListConnectionState
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/** Banner states. Steady-state connected renders nothing — transient only. */
internal enum class ConnectivityBannerState { Hidden, Offline, Connecting, JustConnected }

internal enum class ConnectivityBannerTarget { Offline, NoAttempt, Connecting, Connected }

internal data class ConnectivityBannerPresentation(
    val displayed: ConnectivityBannerState,
    val recoveryPending: Boolean,
    val target: ConnectivityBannerTarget,
)

/** Raw target from independent internet, active-attempt, and application-ready inputs. */
internal fun connectivityBannerTarget(
    hasValidatedInternet: Boolean,
    activeAccountRef: String?,
    runtimeGeneration: Int,
    connectionState: ChatListConnectionState,
): ConnectivityBannerTarget =
    when {
        activeAccountRef == null -> ConnectivityBannerTarget.NoAttempt
        !hasValidatedInternet -> ConnectivityBannerTarget.Offline
        connectionState.accountRef != activeAccountRef ||
            connectionState.runtimeGeneration != runtimeGeneration -> ConnectivityBannerTarget.NoAttempt
        connectionState.phase == ChatListConnectionPhase.Validating -> ConnectivityBannerTarget.NoAttempt
        connectionState.phase == ChatListConnectionPhase.Attempting -> ConnectivityBannerTarget.Connecting
        connectionState.phase == ChatListConnectionPhase.Ready -> ConnectivityBannerTarget.Connected
        else -> ConnectivityBannerTarget.NoAttempt
    }

/**
 * Maps a relay-health snapshot to the banner's connectivity signal. Zero
 * configured relays means there is nothing to connect to — signed-out or a
 * bare runtime — so the banner has nothing to complain about.
 */
internal fun relaysConnectedFromHealth(
    connectedRelays: Int,
    totalRelays: Int,
): Boolean = totalRelays == 0 || connectedRelays > 0

/**
 * The clamp applied to every relay-signal write: no validated internet means
 * no connected relays, whatever an optimistic default or stale health snapshot
 * claims. A restore therefore keeps the signal false until a fresh
 * [relaysConnectedFromHealth] sample proves recovery — a quick offline/online
 * bounce, an offline startup seed, or a poll racing the transition can never
 * flash success off stale state.
 */
internal fun relaysConnectedOnNetworkChange(
    isOnline: Boolean,
    cached: Boolean,
): Boolean = isOnline && cached

/**
 * Shapes the raw target into display state while remembering an unresolved
 * problem across a no-attempt interval. Only explicit application readiness
 * can consume that pending recovery and flash JustConnected; a failed attempt
 * merely hides Connecting and cannot manufacture a success transition.
 */
internal fun connectivityBannerNext(
    previous: ConnectivityBannerPresentation,
    target: ConnectivityBannerTarget,
): ConnectivityBannerPresentation =
    when (target) {
        ConnectivityBannerTarget.Offline ->
            ConnectivityBannerPresentation(
                ConnectivityBannerState.Offline,
                recoveryPending = true,
                target = target,
            )
        ConnectivityBannerTarget.Connecting ->
            ConnectivityBannerPresentation(
                ConnectivityBannerState.Connecting,
                recoveryPending = true,
                target = target,
            )
        ConnectivityBannerTarget.NoAttempt ->
            previous.copy(
                displayed = ConnectivityBannerState.Hidden,
                target = target,
            )
        ConnectivityBannerTarget.Connected ->
            if (previous.recoveryPending) {
                ConnectivityBannerPresentation(
                    ConnectivityBannerState.JustConnected,
                    recoveryPending = false,
                    target = target,
                )
            } else {
                ConnectivityBannerPresentation(
                    ConnectivityBannerState.Hidden,
                    recoveryPending = false,
                    target = target,
                )
            }
    }

internal fun initialConnectivityBannerState(target: ConnectivityBannerTarget): ConnectivityBannerPresentation =
    when (target) {
        ConnectivityBannerTarget.Offline ->
            ConnectivityBannerPresentation(
                ConnectivityBannerState.Offline,
                recoveryPending = true,
                target = target,
            )
        ConnectivityBannerTarget.Connecting ->
            ConnectivityBannerPresentation(
                ConnectivityBannerState.Connecting,
                recoveryPending = true,
                target = target,
            )
        ConnectivityBannerTarget.NoAttempt,
        ConnectivityBannerTarget.Connected,
        ->
            ConnectivityBannerPresentation(
                ConnectivityBannerState.Hidden,
                recoveryPending = false,
                target = target,
            )
    }

// How long the success flash stays before the bar returns to normal.
internal const val CONNECTIVITY_BANNER_FLASH_MILLIS = 800L

// Relay health is a polled snapshot (the bindings expose no push stream for
// pool state). The fast cadence runs only while the signal is banner-relevant,
// steady connected operation backs off so the idle chat list is not woken
// every two seconds for a signal that changes nothing.
internal const val CONNECTIVITY_RELAY_POLL_MILLIS = 2_000L

// Accepted tradeoff of the backoff: a relay-only drop (device network still
// up) has no push source, so it is discovered by the next steady poll — up to
// one backoff late. Network-driven transitions and foreground resumes wake the
// sleep immediately through [relayPollWakeEvents].
internal const val CONNECTIVITY_RELAY_STEADY_POLL_MILLIS = 15_000L

/**
 * Picks the relay-health poll delay: fast while the banner shows a problem or
 * the relays are not connected, backed off once steady-state connected. Named
 * so the regression test can pin that steady state never uses the fast cadence.
 */
internal fun relayPollDelayMillis(
    displayed: ConnectivityBannerState,
    relaysConnected: Boolean,
): Long =
    if (displayed == ConnectivityBannerState.Hidden && relaysConnected) {
        CONNECTIVITY_RELAY_STEADY_POLL_MILLIS
    } else {
        CONNECTIVITY_RELAY_POLL_MILLIS
    }

/**
 * Events that must cut a steady-state backoff sleep short: any state change
 * that puts the poll back on the fast cadence (network loss is pushed, banner
 * state is local), and every foreground resume — the poll is a no-op while
 * backgrounded, so a resume must not wait out a sleep started against a stale
 * pre-background snapshot.
 */
internal fun relayPollWakeEvents(
    displayedStates: Flow<ConnectivityBannerState>,
    relaysConnected: Flow<Boolean>,
    foregroundResumes: Flow<Unit>,
): Flow<Unit> =
    merge(
        combine(displayedStates, relaysConnected) { shown, connected ->
            relayPollDelayMillis(shown, connected) == CONNECTIVITY_RELAY_POLL_MILLIS
        }.filter { it }.map { },
        foregroundResumes,
    )

internal const val CHAT_LIST_INLINE_CONNECTIVITY_TAG = "chat-list-inline-connectivity"

/**
 * Owns relay fallback polling and the connectivity state machine for the
 * chat list. Hoist once per screen and pass the displayed state to the inline
 * indicator.
 */
@Composable
internal fun rememberChatListConnectivityState(
    appState: WhiteNoiseAppState,
    controller: ChatsController,
): ConnectivityBannerState {
    val signals by appState.connectivitySignals.collectAsState()
    val activeAccountRef = appState.activeAccountRef
    val runtimeGeneration = appState.runtimeGeneration
    val target =
        connectivityBannerTarget(
            hasValidatedInternet = signals.hasValidatedInternet,
            activeAccountRef = activeAccountRef,
            runtimeGeneration = runtimeGeneration,
            connectionState = controller.connectionState,
        )
    var presentation by
        remember(controller, activeAccountRef, runtimeGeneration) {
            mutableStateOf(initialConnectivityBannerState(target))
        }
    val renderedPresentation =
        if (presentation.target == target) presentation else connectivityBannerNext(presentation, target)
    SideEffect {
        if (presentation != renderedPresentation) presentation = renderedPresentation
    }
    val foregroundEpoch = rememberConnectivityForegroundEpoch()
    RelayConnectivityPollingEffect(appState, controller, renderedPresentation.displayed, foregroundEpoch)
    ValidatedInternetRefreshEffect(appState, controller, activeAccountRef, runtimeGeneration)
    ConnectivityEdgeRefreshEffects(
        effectOwner = controller,
        activeAccountRef = activeAccountRef,
        runtimeGeneration = runtimeGeneration,
        hasValidatedInternet = signals.hasValidatedInternet,
        relaysConnected = signals.relaysConnected,
        foregroundEpoch = foregroundEpoch,
        revalidateConnectionReadiness = controller::revalidateConnectionReadiness,
        retryConnectionReadiness = controller::refreshConnectionReadiness,
    )
    LaunchedEffect(controller, activeAccountRef, runtimeGeneration, renderedPresentation) {
        if (renderedPresentation.displayed == ConnectivityBannerState.JustConnected) {
            delay(CONNECTIVITY_BANNER_FLASH_MILLIS)
            if (presentation == renderedPresentation) {
                presentation = renderedPresentation.copy(displayed = ConnectivityBannerState.Hidden)
            }
        }
    }
    return renderedPresentation.displayed
}

/**
 * All transient connectivity states render in one stable slot beside the
 * active account avatar; steady-state connected renders nothing.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Suppress("FunctionNaming")
@Composable
internal fun ChatListInlineConnectivityIndicator(
    state: ConnectivityBannerState,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = state != ConnectivityBannerState.Hidden,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier.testTag(CHAT_LIST_INLINE_CONNECTIVITY_TAG),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier =
                Modifier
                    .padding(end = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            when (state) {
                ConnectivityBannerState.Offline ->
                    Icon(
                        Icons.Default.CloudOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                ConnectivityBannerState.Connecting ->
                    LoadingIndicator(modifier = Modifier.size(18.dp))
                ConnectivityBannerState.JustConnected ->
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                ConnectivityBannerState.Hidden -> Unit
            }
            Text(
                text =
                    when (state) {
                        ConnectivityBannerState.Connecting -> stringResource(R.string.connectivity_connecting)
                        ConnectivityBannerState.JustConnected -> stringResource(R.string.connectivity_connected)
                        ConnectivityBannerState.Offline -> stringResource(R.string.connectivity_offline)
                        ConnectivityBannerState.Hidden -> ""
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
