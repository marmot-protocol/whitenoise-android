package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.AccountRelayListsFfi
import dev.ipf.marmotkit.MissingRelayListKindFfi
import dev.ipf.marmotkit.RelayListFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.RelayListKind
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.isAcceptableRelayUrl
import dev.ipf.whitenoise.android.ui.common.SectionCard
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RelaysScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
) {
    var pendingUrl by remember { mutableStateOf("") }
    var lists by remember(appState.activeAccountRef) { mutableStateOf<AccountRelayListsFfi?>(null) }
    var selectedKind by remember { mutableStateOf(RelayListKind.Nip65) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun reloadLists() {
        lists = appState.accountRelayLists()
    }

    LaunchedEffect(appState.activeAccountRef) {
        reloadLists()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.relays)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { scope.launch { reloadLists() } }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                SectionCard(title = stringResource(R.string.account_relay_lists)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        relayListKinds.forEach { option ->
                            FilterChip(
                                selected = selectedKind == option,
                                onClick = { selectedKind = option },
                                label = { Text(stringResource(option.labelRes)) },
                            )
                        }
                    }

                    val currentRelays = lists?.relaysFor(selectedKind).orEmpty()
                    if (currentRelays.isEmpty()) {
                        Text(stringResource(R.string.no_relays), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    currentRelays.forEach { relay ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(relay, modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace)
                            IconButton(
                                onClick = {
                                    saving = true
                                    appState.launchMutation {
                                        try {
                                            lists = appState.setAccountRelays(selectedKind, currentRelays - relay) ?: appState.accountRelayLists()
                                        } finally {
                                            saving = false
                                        }
                                    }
                                },
                                enabled = !saving && currentRelays.size > 1,
                            ) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.remove_relay))
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = pendingUrl,
                            onValueChange = { pendingUrl = it },
                            label = { Text("wss://relay.example.com") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            keyboardOptions =
                                KeyboardOptions(
                                    capitalization = KeyboardCapitalization.None,
                                    autoCorrectEnabled = false,
                                    keyboardType = KeyboardType.Uri,
                                ),
                        )
                        IconButton(
                            onClick = {
                                val trimmed = pendingUrl.trim()
                                saving = true
                                appState.launchMutation {
                                    try {
                                        lists = appState.setAccountRelays(selectedKind, currentRelays + trimmed) ?: appState.accountRelayLists()
                                        pendingUrl = ""
                                    } finally {
                                        saving = false
                                    }
                                }
                            },
                            modifier = Modifier.size(48.dp),
                            enabled =
                                pendingUrl.trim().let {
                                    !saving &&
                                        appState.activeAccountRef != null &&
                                        isAcceptableRelayUrl(it) &&
                                        !currentRelays.contains(it)
                                },
                        ) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_relay))
                        }
                    }
                }
            }
            item {
                PublishedRelayLists(lists)
            }
        }
    }
}

private val relayListKinds =
    listOf(
        RelayListKind.Nip65,
        RelayListKind.Inbox,
    )

internal val RelayListKind.labelRes: Int
    get() =
        when (this) {
            RelayListKind.Nip65 -> R.string.nip_65
            RelayListKind.Inbox -> R.string.inbox
        }

private fun AccountRelayListsFfi.relaysFor(kind: RelayListKind): List<String> =
    when (kind) {
        RelayListKind.Nip65 -> nip65.relays
        RelayListKind.Inbox -> inbox.relays
    }

@Composable
private fun PublishedRelayLists(lists: AccountRelayListsFfi?) {
    SectionCard(title = stringResource(R.string.published_relay_lists)) {
        if (lists == null) {
            Text(stringResource(R.string.no_relay_projection), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@SectionCard
        }
        RelayListRow(stringResource(R.string.nip_65), lists.nip65)
        RelayListRow(stringResource(R.string.inbox), lists.inbox)
        if (lists.complete) {
            Text(stringResource(R.string.all_relay_lists_published), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(
                stringResource(R.string.missing_relay_lists, missingRelayListLabels(lists.missing)),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun missingRelayListLabels(missing: List<MissingRelayListKindFfi>): String {
    val labels = mutableListOf<String>()
    for (kind in missing) {
        labels += stringResource(kind.labelRes)
    }
    return labels.joinToString(", ")
}

internal val MissingRelayListKindFfi.labelRes: Int
    get() =
        when (this) {
            MissingRelayListKindFfi.NIP65 -> R.string.nip_65
            MissingRelayListKindFfi.INBOX -> R.string.inbox
        }

@Composable
private fun RelayListRow(
    title: String,
    list: RelayListFfi,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text("${list.relays.size}", fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (list.relays.isEmpty()) {
            Text(stringResource(R.string.not_published), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            list.relays.forEach { relay ->
                Text(relay, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
