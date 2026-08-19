package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withTimeoutOrNull

/** Banner states. Steady-state connected renders nothing — transient only. */
internal enum class ConnectivityBannerState { Hidden, Offline, Connecting, JustConnected }

/** Raw target from the two signals, before flash/debounce shaping. */
internal fun connectivityBannerTarget(
    hasNetwork: Boolean,
    relaysConnected: Boolean,
): ConnectivityBannerState =
    when {
        !hasNetwork -> ConnectivityBannerState.Offline
        !relaysConnected -> ConnectivityBannerState.Connecting
        else -> ConnectivityBannerState.Hidden
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
 * The clamp applied to every relay-signal write: no active network means no
 * connected relays, whatever an optimistic default or a stale health snapshot
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
 * Shapes the raw target into the displayed state: reaching connected FROM a
 * visible problem state flashes JustConnected (the caller times its dismissal);
 * connected while already hidden stays hidden — no steady-state chrome.
 */
internal fun connectivityBannerNext(
    previous: ConnectivityBannerState,
    target: ConnectivityBannerState,
): ConnectivityBannerState =
    if (target == ConnectivityBannerState.Hidden &&
        (previous == ConnectivityBannerState.Offline || previous == ConnectivityBannerState.Connecting)
    ) {
        ConnectivityBannerState.JustConnected
    } else {
        target
    }

// Flapping callbacks (WiFi handoffs, brief socket drops) must not flicker the
// banner: a problem state has to persist this long before it renders.
internal const val CONNECTIVITY_BANNER_DEBOUNCE_MILLIS = 500L

// How long the success flash stays before the bar returns to normal.
internal const val CONNECTIVITY_BANNER_FLASH_MILLIS = 1_500L

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

internal const val CHAT_LIST_OFFLINE_BANNER_TAG = "chat-list-offline-banner"

/**
 * Owns relay polling and the debounced connectivity state machine for the
 * chat list. Hoist once per screen and pass the displayed state to the inline
 * indicator and the offline strip.
 */
@Composable
internal fun rememberChatListConnectivityState(appState: WhiteNoiseAppState): ConnectivityBannerState {
    var displayed by remember { mutableStateOf(ConnectivityBannerState.Hidden) }
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
    LaunchedEffect(appState, lifecycleOwner) {
        val wakeEvents =
            relayPollWakeEvents(
                displayedStates = snapshotFlow { displayed },
                relaysConnected = appState.connectivitySignals.map { it.relaysConnected },
                foregroundResumes = snapshotFlow { foregroundEpoch }.drop(1).map { },
            )
        while (true) {
            appState.refreshRelayConnectivity()
            val pollDelay = relayPollDelayMillis(displayed, appState.connectivitySignals.value.relaysConnected)
            if (pollDelay == CONNECTIVITY_RELAY_POLL_MILLIS) {
                delay(pollDelay)
            } else {
                // Sleep the backoff, but wake early on any event that needs
                // the fast cadence back so recovery never waits out the
                // remaining backoff window.
                withTimeoutOrNull(pollDelay) { wakeEvents.first() }
            }
        }
    }
    LaunchedEffect(appState) {
        appState.connectivitySignals.collectLatest { signals ->
            val target = connectivityBannerTarget(signals.hasNetwork, signals.relaysConnected)
            // collectLatest cancels this block when the signals change, so a
            // flap shorter than the debounce never becomes visible state.
            if (target != ConnectivityBannerState.Hidden && displayed == ConnectivityBannerState.Hidden) {
                delay(CONNECTIVITY_BANNER_DEBOUNCE_MILLIS)
            }
            val next = connectivityBannerNext(displayed, target)
            displayed = next
            if (next == ConnectivityBannerState.JustConnected) {
                delay(CONNECTIVITY_BANNER_FLASH_MILLIS)
                displayed = ConnectivityBannerState.Hidden
            }
        }
    }
    return displayed
}

/**
 * Connecting and JustConnected render inline beside the active account avatar;
 * steady-state connected and offline render nothing here.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Suppress("FunctionNaming")
@Composable
internal fun ChatListInlineConnectivityIndicator(
    state: ConnectivityBannerState,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = state == ConnectivityBannerState.Connecting || state == ConnectivityBannerState.JustConnected,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier.testTag(CHAT_LIST_INLINE_CONNECTIVITY_TAG),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(end = 8.dp),
        ) {
            when (state) {
                ConnectivityBannerState.Connecting ->
                    LoadingIndicator(modifier = Modifier.size(18.dp))
                ConnectivityBannerState.JustConnected ->
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                ConnectivityBannerState.Hidden,
                ConnectivityBannerState.Offline,
                -> Unit
            }
            Text(
                text =
                    when (state) {
                        ConnectivityBannerState.Connecting -> stringResource(R.string.connectivity_connecting)
                        ConnectivityBannerState.JustConnected -> stringResource(R.string.connectivity_connected)
                        ConnectivityBannerState.Hidden,
                        ConnectivityBannerState.Offline,
                        -> ""
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Actionable full-width offline strip under the chat-list top bar. Connecting
 * and JustConnected render inline in [ChatListTopBar] instead.
 */
@Suppress("FunctionNaming")
@Composable
internal fun ChatListConnectivityBanner(
    displayed: ConnectivityBannerState,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = displayed == ConnectivityBannerState.Offline,
        enter = expandVertically(),
        exit = shrinkVertically(),
        modifier = modifier.testTag(CHAT_LIST_OFFLINE_BANNER_TAG),
    ) {
        Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Icon(
                    Icons.Default.CloudOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = stringResource(R.string.connectivity_offline),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
