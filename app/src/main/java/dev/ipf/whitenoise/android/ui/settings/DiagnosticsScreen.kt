package dev.ipf.whitenoise.android.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.RelayHealthFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.DiagnosticFormatter
import dev.ipf.whitenoise.android.core.DiagnosticIdentityPresentation
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.diagnostics.PerformanceDiagnosticStatus
import dev.ipf.whitenoise.android.diagnostics.PerformanceDiagnostics
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseDropdownMenu
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseEmptyState
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseMenuItem
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseModalBottomSheet
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseSheetHeader
import dev.ipf.whitenoise.android.ui.common.rememberedRelativeTime
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

internal enum class DiagnosticsSection {
    Actions,
    Performance,
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
    val performanceStatus: PerformanceDiagnosticStatus,
)

internal fun diagnosticsState(
    relayHealth: DiagnosticsRelayHealth?,
    activeAccountRef: String?,
    accountCount: Int,
    bootstrapRelayCount: Int,
    eventCount: Int,
    streaming: Boolean,
    sendingPing: Boolean,
    performanceStatus: PerformanceDiagnosticStatus,
): DiagnosticsState =
    DiagnosticsState(
        sections =
            DiagnosticsSection.entries.filter { section ->
                section != DiagnosticsSection.Performance || performanceStatus.available
            },
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
        performanceStatus = performanceStatus,
    )

internal data class DiagnosticLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: ULong = (System.currentTimeMillis() / 1000L).toULong(),
    val text: String,
)

internal const val DIAGNOSTICS_CONTENT_TAG = "diagnostics-content"
private const val PERFORMANCE_STATUS_REFRESH_MILLIS = 1_000L

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
    var performanceStatus by remember { mutableStateOf(PerformanceDiagnostics.status()) }
    val scope = rememberCoroutineScope()
    val sentPingFormat = stringResource(R.string.diagnostic_sent_ping_to_self)
    val sendToSelfFailedFormat = stringResource(R.string.diagnostic_send_to_self_failed)

    LaunchedEffect(performanceStatus.active) {
        while (performanceStatus.active) {
            delay(PERFORMANCE_STATUS_REFRESH_MILLIS)
            performanceStatus = PerformanceDiagnostics.status()
        }
    }

    fun appendLog(text: String) {
        entries = (entries + DiagnosticLogEntry(text = text)).takeLast(500)
    }

    val diagnosticIdentity =
        remember(appState) {
            DiagnosticIdentityPresentation(
                accountLabel = { label, accountIdHex ->
                    DiagnosticIdentityPresentation.accountLabel(label, accountIdHex, appState::shortNpub)
                },
                publicIdentity = appState::shortNpub,
            )
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
                val described = DiagnosticFormatter.describe(event, diagnosticIdentity)
                entries = (entries + DiagnosticLogEntry(text = described)).takeLast(500)
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
                performanceStatus = performanceStatus,
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
        onPerformanceEnabledChange = { enabled ->
            performanceStatus = if (enabled) PerformanceDiagnostics.start() else PerformanceDiagnostics.stop()
        },
    )
}

/** Presents real diagnostic events in the prototype's card; operational data stays in Health. */
@Composable
@Suppress("FunctionNaming", "LongMethod", "LongParameterList")
internal fun DiagnosticsContent(
    state: DiagnosticsState,
    entries: List<DiagnosticLogEntry>,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSendToSelf: () -> Unit,
    onClear: () -> Unit,
    onPerformanceEnabledChange: (Boolean) -> Unit,
) {
    var showActions by remember { mutableStateOf(false) }
    var showHealth by remember { mutableStateOf(false) }
    if (showHealth) {
        DiagnosticsHealthSheet(
            state = state,
            onDismiss = { showHealth = false },
            onRefresh = onRefresh,
            onSendToSelf = onSendToSelf,
            onPerformanceEnabledChange = onPerformanceEnabledChange,
        )
    }
    SettingsScaffold(
        title = stringResource(R.string.diagnostics),
        onBack = onBack,
        modifier = Modifier.testTag(DIAGNOSTICS_CONTENT_TAG),
        topBarActions = {
            Box {
                IconButton(onClick = { showActions = true }, modifier = Modifier.testTag("diagnostics.actions")) {
                    Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.actions))
                }
                WhiteNoiseDropdownMenu(
                    expanded = showActions,
                    onDismissRequest = { showActions = false },
                    modifier = Modifier.testTag("diagnostics.actions.menu"),
                    items =
                        listOf(
                            WhiteNoiseMenuItem(
                                label = stringResource(R.string.diagnostics_health),
                                icon = R.drawable.ic_bug_report,
                                onClick = { showHealth = true },
                                modifier = Modifier.testTag("diagnostics.action.health"),
                            ),
                            WhiteNoiseMenuItem(
                                label = stringResource(R.string.send_to_self),
                                icon = R.drawable.ic_check,
                                onClick = onSendToSelf,
                                enabled = state.sendToSelfEnabled,
                                modifier = Modifier.testTag("diagnostics.action.test"),
                            ),
                            WhiteNoiseMenuItem(
                                label = stringResource(R.string.clear),
                                icon = R.drawable.ic_delete,
                                onClick = onClear,
                                enabled = entries.isNotEmpty(),
                                modifier = Modifier.testTag("diagnostics.action.clear"),
                            ),
                        ),
                )
            }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.diagnostics_events),
                    modifier = Modifier.weight(1f).testTag("diagnostics.events_title").semantics { heading() },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DiagnosticsStreamIndicator(state.streamStatus)
            }
            Surface(
                modifier = Modifier.fillMaxWidth().weight(1f),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                shape = MaterialTheme.shapes.large,
                border = amoledOutlineBorder(),
            ) {
                if (state.showEventLogEmptyState) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        WhiteNoiseEmptyState(
                            title = stringResource(R.string.diagnostics_no_events),
                            detail = stringResource(R.string.waiting_for_events),
                            modifier = Modifier.testTag("diagnostics.empty"),
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().testTag("diagnostics.events"),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        // Each of the bounded 500 entries remains individually virtualized and keyed.
                        itemsIndexed(entries, key = { _, entry -> entry.id }) { index, entry ->
                            Column(Modifier.fillMaxWidth().testTag("diagnostics.event.$index")) {
                                Column(
                                    Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    val time = rememberedRelativeTime(entry.timestamp)
                                    if (time.isNotEmpty()) {
                                        Text(
                                            time,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontFamily = FontFamily.Monospace,
                                        )
                                    }
                                    Text(
                                        entry.text,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                                if (index < entries.lastIndex) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerLow)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Reports the actual subscription state without a perpetual UI animation or simulated connectivity. */
@Composable
@Suppress("FunctionNaming")
private fun DiagnosticsStreamIndicator(status: DiagnosticsStreamStatus) {
    Row(
        modifier = Modifier.testTag("diagnostics.stream"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color =
            if (status == DiagnosticsStreamStatus.Live) {
                Color(0xFF188038)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        Icon(
            painterResource(R.drawable.ic_settings_cell_tower),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(18.dp),
        )
        Text(
            stringResource(if (status == DiagnosticsStreamStatus.Live) R.string.live else R.string.idle),
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

/** Keeps refresh, all native counters, runtime identity and opt-in logging reachable in one scrollable sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("FunctionNaming", "LongMethod")
private fun DiagnosticsHealthSheet(
    state: DiagnosticsState,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onSendToSelf: () -> Unit,
    onPerformanceEnabledChange: (Boolean) -> Unit,
) {
    WhiteNoiseModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .testTag("diagnostics.health")
                .padding(bottom = 24.dp),
        ) {
            WhiteNoiseSheetHeader(stringResource(R.string.diagnostics_health), onClose = onDismiss)
            SettingsGroup {
                row("refresh") { context ->
                    SettingsAction(
                        context,
                        stringResource(R.string.refresh),
                        onRefresh,
                        Modifier.testTag("diagnostics.health.refresh"),
                    )
                }
                row("send") { context ->
                    SettingsAction(
                        context,
                        stringResource(R.string.send_to_self),
                        onSendToSelf,
                        modifier = Modifier.testTag("diagnostics.health.test"),
                        enabled = state.sendToSelfEnabled,
                    )
                }
            }
            if (DiagnosticsSection.Performance in state.sections) {
                PerformanceDiagnosticsGroup(state.performanceStatus, onPerformanceEnabledChange)
            }
            SettingsSection(stringResource(R.string.relay_health))
            if (state.showRelayHealthEmptyState) {
                SettingsExplainer(stringResource(R.string.no_relay_snapshot_yet))
            } else {
                SettingsGroup {
                    state.relayHealthValues.forEach { value ->
                        row(value.key.name) { context ->
                            SettingsValue(context, diagnosticValueLabel(value.key), value.value.orEmpty())
                        }
                    }
                }
            }
            SettingsSection(stringResource(R.string.runtime))
            SettingsGroup {
                state.runtimeValues.forEach { value ->
                    row(value.key.name) { context ->
                        SettingsValue(
                            context,
                            diagnosticValueLabel(value.key),
                            value.value ?: stringResource(R.string.none),
                        )
                    }
                }
            }
        }
    }
}

/** Retains the caller-owned logging switch, bounded-session status and privacy explanation. */
@Composable
@Suppress("FunctionNaming")
private fun PerformanceDiagnosticsGroup(
    status: PerformanceDiagnosticStatus,
    onEnabledChange: (Boolean) -> Unit,
) {
    SettingsSection(stringResource(R.string.performance_logs))
    SettingsGroup {
        row("performance") { context ->
            SettingsSwitch(
                context = context,
                title = stringResource(R.string.performance_logs),
                checked = status.active,
                onCheckedChange = onEnabledChange,
                modifier = Modifier.testTag("diagnostics.performance"),
                subtitle =
                    if (status.active) {
                        val remainingMinutes = ((status.remainingMillis + 59_999L) / 60_000L).coerceAtLeast(1L)
                        stringResource(R.string.performance_logs_active, remainingMinutes)
                    } else {
                        stringResource(R.string.performance_logs_inactive)
                    },
            )
        }
    }
    SettingsExplainer(stringResource(R.string.performance_logs_description))
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
) {
    val clipboard = LocalClipboardManager.current
    // Opt-in tap-to-copy: callers provide the full value to copy.
    val copyable = !copyValue.isNullOrEmpty()
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
