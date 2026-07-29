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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

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
// pool state), refreshed on this cadence while the banner can render.
internal const val CONNECTIVITY_RELAY_POLL_MILLIS = 2_000L

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
    LaunchedEffect(appState) {
        while (true) {
            appState.refreshRelayConnectivity()
            delay(CONNECTIVITY_RELAY_POLL_MILLIS)
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
