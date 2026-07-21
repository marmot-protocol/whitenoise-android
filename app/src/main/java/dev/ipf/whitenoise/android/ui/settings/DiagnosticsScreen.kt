package dev.ipf.whitenoise.android.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.RelayHealthFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.DiagnosticFormatter
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.SectionCard
import dev.ipf.whitenoise.android.ui.common.rememberedRelativeTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

internal enum class DiagnosticsSection {
    Actions,
    RelayHealth,
    Runtime,
    EventLog,
}

internal enum class DiagnosticValueKey {
    Total,
    Connected,
    Connecting,
    Disconnected,
    Attempts,
    Successes,
    ActiveAccount,
    Accounts,
    BootstrapRelays,
}

internal enum class DiagnosticsStreamStatus {
    Live,
    Idle,
}

internal data class DiagnosticsRelayHealth(
    val total: UInt,
    val connected: UInt,
    val connecting: UInt,
    val disconnected: UInt,
    val attempts: UInt,
    val successes: UInt,
)

internal data class DiagnosticValue(
    val key: DiagnosticValueKey,
    val value: String?,
)

internal data class DiagnosticsState(
    val sections: List<DiagnosticsSection>,
    val relayHealthValues: List<DiagnosticValue>,
    val runtimeValues: List<DiagnosticValue>,
    val showRelayHealthEmptyState: Boolean,
    val showEventLogEmptyState: Boolean,
    val sendToSelfEnabled: Boolean,
    val streamStatus: DiagnosticsStreamStatus,
)

internal fun diagnosticsState(
    relayHealth: DiagnosticsRelayHealth?,
    activeAccountRef: String?,
    accountCount: Int,
    bootstrapRelayCount: Int,
    eventCount: Int,
    streaming: Boolean,
    sendingPing: Boolean,
): DiagnosticsState =
    DiagnosticsState(
        sections = DiagnosticsSection.entries,
        relayHealthValues =
            relayHealth
                ?.let {
                    listOf(
                        DiagnosticValue(DiagnosticValueKey.Total, it.total.toString()),
                        DiagnosticValue(DiagnosticValueKey.Connected, it.connected.toString()),
                        DiagnosticValue(DiagnosticValueKey.Connecting, it.connecting.toString()),
                        DiagnosticValue(DiagnosticValueKey.Disconnected, it.disconnected.toString()),
                        DiagnosticValue(DiagnosticValueKey.Attempts, it.attempts.toString()),
                        DiagnosticValue(DiagnosticValueKey.Successes, it.successes.toString()),
                    )
                }.orEmpty(),
        runtimeValues =
            listOf(
                DiagnosticValue(DiagnosticValueKey.ActiveAccount, activeAccountRef),
                DiagnosticValue(DiagnosticValueKey.Accounts, accountCount.toString()),
                DiagnosticValue(DiagnosticValueKey.BootstrapRelays, bootstrapRelayCount.toString()),
            ),
        showRelayHealthEmptyState = relayHealth == null,
        showEventLogEmptyState = eventCount == 0,
        sendToSelfEnabled = !sendingPing && activeAccountRef != null,
        streamStatus = if (streaming) DiagnosticsStreamStatus.Live else DiagnosticsStreamStatus.Idle,
    )

internal data class DiagnosticLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: ULong = (System.currentTimeMillis() / 1000L).toULong(),
    val text: String,
)

internal const val DIAGNOSTICS_CONTENT_TAG = "diagnostics-content"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiagnosticsScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
) {
    // Claim back so it returns to Settings rather than falling through
    // to the Activity and closing the app.
    BackHandler { onBack() }
    var health by remember { mutableStateOf<RelayHealthFfi?>(null) }
    var entries by remember { mutableStateOf<List<DiagnosticLogEntry>>(emptyList()) }
    var streaming by remember { mutableStateOf(false) }
    var sendingPing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val sentPingFormat = stringResource(R.string.diagnostic_sent_ping_to_self)
    val sendToSelfFailedFormat = stringResource(R.string.diagnostic_send_to_self_failed)

    fun appendLog(text: String) {
        entries = (entries + DiagnosticLogEntry(text = text)).takeLast(500)
    }

    LaunchedEffect(appState.activeAccountRef, appState.runtimeGeneration) {
        streaming = true
        val subscription = appState.marmotIo { subscribeEvents() }
        try {
            while (true) {
                val event =
                    withContext(Dispatchers.IO) {
                        subscription.next()
                    } ?: break
                entries = (entries + DiagnosticLogEntry(text = DiagnosticFormatter.describe(event))).takeLast(500)
            }
        } catch (throwable: Throwable) {
            // A re-key (account/runtime change) cancels this effect; let that
            // propagate instead of logging it as a stream failure.
            if (throwable is CancellationException) throw throwable
            entries =
                (
                    entries +
                        DiagnosticLogEntry(
                            text = "event stream failed: ${throwable.message ?: throwable.javaClass.simpleName}",
                        )
                ).takeLast(500)
        } finally {
            streaming = false
            withContext(Dispatchers.IO) {
                subscription.destroy()
            }
        }
    }

    DiagnosticsContent(
        state =
            diagnosticsState(
                relayHealth =
                    health?.let { relay ->
                        DiagnosticsRelayHealth(
                            total = relay.totalRelays,
                            connected = relay.connected,
                            connecting = relay.connecting,
                            disconnected = relay.disconnected,
                            attempts = relay.connectionAttempts,
                            successes = relay.connectionSuccesses,
                        )
                    },
                activeAccountRef = appState.activeAccountRef,
                accountCount = appState.accounts.size,
                bootstrapRelayCount = appState.bootstrapRelayCount(),
                eventCount = entries.size,
                streaming = streaming,
                sendingPing = sendingPing,
            ),
        entries = entries,
        onBack = onBack,
        onRefresh = { scope.launch { health = appState.marmotIo { relayHealth() } } },
        onSendToSelf = {
            if (!sendingPing) {
                sendingPing = true
                scope.launch {
                    val account = appState.activeAccountRef
                    if (account == null) {
                        sendingPing = false
                        return@launch
                    }
                    try {
                        runCatching {
                            val groupId =
                                appState.marmotIo {
                                    createGroup(
                                        account,
                                        "diagnostic-${System.currentTimeMillis() / 1000L}",
                                        emptyList(),
                                        null,
                                    )
                                }
                            appState.marmotIo { sendText(account, groupId, "ping at ${System.currentTimeMillis() / 1000L}") }
                            // Archive the throwaway group so the chat list doesn't accumulate orphans.
                            appState.marmotIo { setGroupArchived(account, groupId, true) }
                            appendLog(String.format(sentPingFormat, IdentityFormatter.short(groupId)))
                        }.onFailure {
                            appendLog(String.format(sendToSelfFailedFormat, it.message ?: it.javaClass.simpleName))
                        }
                    } finally {
                        sendingPing = false
                    }
                }
            }
        },
        onClear = { entries = emptyList() },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("FunctionNaming", "LongMethod", "MaxLineLength")
internal fun DiagnosticsContent(
    state: DiagnosticsState,
    entries: List<DiagnosticLogEntry>,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSendToSelf: () -> Unit,
    onClear: () -> Unit,
) {
    Scaffold(
        modifier = Modifier.testTag(DIAGNOSTICS_CONTENT_TAG),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diagnostics)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            state.sections.forEach { section ->
                when (section) {
                    DiagnosticsSection.Actions -> {
                        item {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                OutlinedButton(onClick = onSendToSelf, enabled = state.sendToSelfEnabled) {
                                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.send_to_self))
                                }
                                OutlinedButton(onClick = onClear) {
                                    Icon(Icons.Default.Delete, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.clear))
                                }
                                Spacer(Modifier.weight(1f))
                                Text(
                                    stringResource(
                                        if (state.streamStatus == DiagnosticsStreamStatus.Live) R.string.live else R.string.idle,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    DiagnosticsSection.RelayHealth -> {
                        item {
                            SectionCard(title = stringResource(R.string.relay_health)) {
                                if (state.showRelayHealthEmptyState) {
                                    Text(
                                        stringResource(R.string.no_relay_snapshot_yet),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Button(onClick = onRefresh) {
                                        Icon(Icons.Default.Refresh, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(R.string.refresh))
                                    }
                                } else {
                                    state.relayHealthValues.forEach { value ->
                                        DiagnosticRow(
                                            label = diagnosticValueLabel(value.key),
                                            value = value.value.orEmpty(),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    DiagnosticsSection.Runtime -> {
                        item {
                            SectionCard(title = stringResource(R.string.runtime)) {
                                state.runtimeValues.forEach { value ->
                                    DiagnosticRow(
                                        label = diagnosticValueLabel(value.key),
                                        value = value.value ?: stringResource(R.string.none),
                                    )
                                }
                            }
                        }
                    }

                    DiagnosticsSection.EventLog -> {
                        // Emit log entries as top-level lazy items so the up-to-500 rows
                        // remain virtualized instead of composing inside one item.
                        item {
                            Text(
                                stringResource(R.string.event_log),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        if (state.showEventLogEmptyState) {
                            item {
                                Text(
                                    stringResource(R.string.waiting_for_events),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            items(entries, key = { it.id }) { entry ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(
                                        rememberedRelativeTime(entry.timestamp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(entry.text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun diagnosticValueLabel(key: DiagnosticValueKey): String =
    stringResource(
        when (key) {
            DiagnosticValueKey.Total -> R.string.total
            DiagnosticValueKey.Connected -> R.string.connected
            DiagnosticValueKey.Connecting -> R.string.connecting
            DiagnosticValueKey.Disconnected -> R.string.disconnected
            DiagnosticValueKey.Attempts -> R.string.attempts
            DiagnosticValueKey.Successes -> R.string.successes
            DiagnosticValueKey.ActiveAccount -> R.string.active_account
            DiagnosticValueKey.Accounts -> R.string.accounts
            DiagnosticValueKey.BootstrapRelays -> R.string.bootstrap_relays
        },
    )

@Composable
@Suppress("FunctionNaming", "LongMethod")
internal fun DiagnosticRow(
    label: String,
    value: String,
    copyValue: String? = null,
    appState: WhiteNoiseAppState? = null,
    onCopy: ((String) -> Unit)? = null,
) {
    val clipboard = LocalClipboardManager.current
    // Opt-in tap-to-copy: callers provide the full value plus either an
    // appState-backed confirmation or a custom confirmation callback.
    val copyable = !copyValue.isNullOrEmpty() && (appState != null || onCopy != null)
    val copyLabel = stringResource(R.string.copy)
    val rowModifier =
        if (copyable) {
            Modifier
                .fillMaxWidth()
                .clickable(
                    onClickLabel = copyLabel,
                    role = Role.Button,
                ) {
                    clipboard.setText(AnnotatedString(copyValue.orEmpty()))
                    if (onCopy != null) {
                        onCopy(label)
                    } else {
                        appState?.presentText(AppText.Resource(R.string.toast_copied_value, listOf(label)))
                    }
                }
        } else {
            Modifier.fillMaxWidth()
        }
    Row(
        rowModifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (copyable) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    value,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.End,
                    // The displayed value is already abbreviated by the caller; keep
                    // it (and the trailing copy icon) on a single line so a long ID
                    // never line-breaks with a stray character on a second row (#799).
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Text(
                value,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
