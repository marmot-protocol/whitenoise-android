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
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalClipboardManager
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

private data class DiagnosticLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: ULong = (System.currentTimeMillis() / 1000L).toULong(),
    val text: String,
)

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
            entries = (entries + DiagnosticLogEntry(text = "event stream failed: ${throwable.message ?: throwable.javaClass.simpleName}")).takeLast(500)
        } finally {
            streaming = false
            withContext(Dispatchers.IO) {
                subscription.destroy()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diagnostics)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch { health = appState.marmotIo { relayHealth() } }
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = {
                            if (sendingPing) return@OutlinedButton
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
                        },
                        enabled = !sendingPing && appState.activeAccountRef != null,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.send_to_self))
                    }
                    OutlinedButton(onClick = { entries = emptyList() }) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.clear))
                    }
                    Spacer(Modifier.weight(1f))
                    Text(stringResource(if (streaming) R.string.live else R.string.idle), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                SectionCard(title = stringResource(R.string.relay_health)) {
                    if (health == null) {
                        Text(stringResource(R.string.no_relay_snapshot_yet), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { scope.launch { health = appState.marmotIo { relayHealth() } } }) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.refresh))
                        }
                    } else {
                        health?.let { relay ->
                            DiagnosticRow(stringResource(R.string.total), relay.totalRelays.toString())
                            DiagnosticRow(stringResource(R.string.connected), relay.connected.toString())
                            DiagnosticRow(stringResource(R.string.connecting), relay.connecting.toString())
                            DiagnosticRow(stringResource(R.string.disconnected), relay.disconnected.toString())
                            DiagnosticRow(stringResource(R.string.attempts), relay.connectionAttempts.toString())
                            DiagnosticRow(stringResource(R.string.successes), relay.connectionSuccesses.toString())
                        }
                    }
                }
            }
            item {
                SectionCard(title = stringResource(R.string.runtime)) {
                    DiagnosticRow(stringResource(R.string.active_account), appState.activeAccountRef ?: stringResource(R.string.none))
                    DiagnosticRow(stringResource(R.string.accounts), appState.accounts.size.toString())
                    DiagnosticRow(stringResource(R.string.bootstrap_relays), appState.bootstrapRelayCount().toString())
                }
            }
            // Event log: emitted as top-level lazy items (keyed by the entry's
            // id) rather than a forEach inside a single item, so the up-to-500
            // rows are actually virtualized instead of all composing at once.
            // See #35.
            item {
                Text(
                    stringResource(R.string.event_log),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (entries.isEmpty()) {
                item {
                    Text(stringResource(R.string.waiting_for_events), color = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable
internal fun DiagnosticRow(
    label: String,
    value: String,
    copyValue: String? = null,
    appState: WhiteNoiseAppState? = null,
) {
    val clipboard = LocalClipboardManager.current
    // Opt-in tap-to-copy: a row becomes copyable only when both the full
    // value and an appState (for the confirmation toast) are supplied. Plain
    // numeric/status rows leave these null and stay non-interactive.
    val copyable = !copyValue.isNullOrEmpty() && appState != null
    val copyLabel = stringResource(R.string.copy)
    val rowModifier =
        if (copyable) {
            Modifier
                .fillMaxWidth()
                .clickable(
                    onClickLabel = copyLabel,
                    role = Role.Button,
                ) {
                    clipboard.setText(AnnotatedString(copyValue!!))
                    appState!!.presentText(AppText.Resource(R.string.toast_copied_value, listOf(label)))
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
