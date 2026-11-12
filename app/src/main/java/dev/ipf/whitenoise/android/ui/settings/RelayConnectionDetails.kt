package dev.ipf.whitenoise.android.ui.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ipf.marmotkit.RelayEndpointClassificationFfi
import dev.ipf.marmotkit.RelayEndpointPolicyFfi
import dev.ipf.marmotkit.RelayLivenessFfi
import dev.ipf.marmotkit.RelayStatusFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.classifyRelayConnections
import dev.ipf.whitenoise.android.state.relayLiveness
import java.text.DateFormat
import java.util.Date

/** Collect live rows while this destination is started and resolve identities only when the relay list changes. */
@Composable
internal fun rememberRelayConnections(
    appState: WhiteNoiseAppState,
    account: String,
    generation: Int,
    urls: List<String>,
): Map<String, RelayConnection> {
    val updates = remember(appState, account, generation) { appState.relayLiveness(account, generation) }
    val rows by updates.collectAsStateWithLifecycle(initialValue = null)
    var endpoints by remember(appState, account, generation, urls) {
        mutableStateOf<Map<String, RelayEndpointClassificationFfi>>(emptyMap())
    }
    LaunchedEffect(appState, account, generation, urls) {
        if (urls.isNotEmpty()) endpoints = appState.classifyRelayConnections(account, generation, urls)
    }
    return remember(urls, endpoints, rows) { projectRelayConnections(urls, endpoints, rows.orEmpty()) }
}

/** Join by MDK's canonical spelling, falling back to exact matches if classification is unavailable. */
internal fun projectRelayConnections(
    urls: List<String>,
    endpoints: Map<String, RelayEndpointClassificationFfi>,
    rows: List<RelayLivenessFfi>,
): Map<String, RelayConnection> {
    val byUrl = rows.associateBy(RelayLivenessFfi::url)
    return urls.associateWith { url ->
        val endpoint = endpoints[url]
        val key = endpoint?.normalizedEndpoint?.let(::nativeLivenessKey) ?: url
        RelayConnection(byUrl[key], endpoint?.policy == RelayEndpointPolicyFfi.ALLOWED)
    }
}

/** The liveness stream drops a bare root slash from a classified URL and preserves other paths. */
private fun nativeLivenessKey(endpoint: String): String {
    val authority = endpoint.indexOf("://") + 3
    val slash = endpoint.indexOf('/', startIndex = authority)
    return if (slash == endpoint.lastIndex) endpoint.dropLast(1) else endpoint
}

/** A missing row means unavailable; MDK explicitly uses Unknown for a configured relay it has not checked. */
@get:StringRes
internal val RelayLivenessFfi?.statusLabel: Int
    get() =
        when (this?.status) {
            RelayStatusFfi.CONNECTED -> R.string.relay_connection_connected
            RelayStatusFfi.CONNECTING -> R.string.relay_connection_connecting
            RelayStatusFfi.OFFLINE -> R.string.relay_connection_offline
            RelayStatusFfi.UNKNOWN -> R.string.relay_connection_unknown
            null -> R.string.relay_list_status_unavailable
        }

/** Render live connection details and the available recovery action. */
@Suppress("FunctionNaming")
@Composable
internal fun RelayConnectionDetails(
    connection: RelayLivenessFfi?,
    busy: Boolean,
    reconnectState: RelayReconnectState,
    onReconnect: (() -> Unit)?,
) {
    val retrying = reconnectState == RelayReconnectState.Requesting
    val canReconnect = connection?.status == RelayStatusFfi.OFFLINE || connection?.status == RelayStatusFfi.UNKNOWN
    SettingsGroup {
        row("connection_status") { context ->
            SettingsValue(
                context,
                stringResource(R.string.relay_connection_status),
                stringResource(connection.statusLabel),
            )
        }
        connection?.lastAttemptUnix?.let { seconds ->
            row("connection_attempt") { context ->
                val attempt =
                    remember(seconds) {
                        DateFormat.getDateTimeInstance().format(Date(seconds.toLong() * 1_000L))
                    }
                SettingsValue(context, stringResource(R.string.relay_connection_attempt), attempt)
            }
        }
        if (retrying || (onReconnect != null && canReconnect)) {
            row("reconnect") { context ->
                val title = if (retrying) R.string.relay_connection_retrying else R.string.relay_connection_retry
                SettingsAction(
                    context = context,
                    title = stringResource(title),
                    subtitle =
                        if (reconnectState == RelayReconnectState.Failed) {
                            stringResource(R.string.relay_connection_retry_failed)
                        } else {
                            null
                        },
                    enabled = !busy && !retrying && onReconnect != null,
                    onClick = { onReconnect?.invoke() },
                )
            }
        }
    }
}
