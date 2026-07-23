package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
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
import androidx.compose.ui.res.stringResource
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
    liveStreamConnected: Boolean,
): ConnectivityBannerState =
    when {
        !hasNetwork -> ConnectivityBannerState.Offline
        !liveStreamConnected -> ConnectivityBannerState.Connecting
        else -> ConnectivityBannerState.Hidden
    }

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

/**
 * Slim transient strip under the chat-list top bar: an actionable offline
 * message, an informational connecting line, and a brief connected flash —
 * never a permanent status widget.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Suppress("FunctionNaming", "LongMethod")
@Composable
internal fun ChatListConnectivityBanner(
    appState: WhiteNoiseAppState,
    modifier: Modifier = Modifier,
) {
    var displayed by remember { mutableStateOf(ConnectivityBannerState.Hidden) }
    LaunchedEffect(appState) {
        appState.connectivitySignals.collectLatest { signals ->
            val target = connectivityBannerTarget(signals.hasNetwork, signals.liveStreamConnected)
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

    AnimatedVisibility(
        visible = displayed != ConnectivityBannerState.Hidden,
        enter = expandVertically(),
        exit = shrinkVertically(),
        modifier = modifier,
    ) {
        Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                when (displayed) {
                    ConnectivityBannerState.Offline ->
                        Icon(
                            Icons.Default.CloudOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp),
                        )
                    ConnectivityBannerState.Connecting ->
                        // Expressive loader (the morphing shape), matching the
                        // sign-in screen's loading state rather than a plain ring.
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
                        when (displayed) {
                            ConnectivityBannerState.Offline -> stringResource(R.string.connectivity_offline)
                            ConnectivityBannerState.Connecting -> stringResource(R.string.connectivity_connecting)
                            ConnectivityBannerState.JustConnected -> stringResource(R.string.connectivity_connected)
                            ConnectivityBannerState.Hidden -> ""
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
