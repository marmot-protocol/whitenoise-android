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
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.AccountRelayListsFfi
import dev.ipf.marmotkit.MissingRelayListKindFfi
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.RelayListKind
import dev.ipf.whitenoise.android.state.RelayUrlValidationResult
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.canRemoveRelay
import dev.ipf.whitenoise.android.state.relayUrlValidationResult
import dev.ipf.whitenoise.android.ui.common.SettingsGroup
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("FunctionNaming", "LongMethod") // Keep the relay edit transaction next to the screen state it updates.
internal fun RelaysScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
) {
    val activeAccountRef = appState.activeAccountRef
    val editorState = rememberRelayState(activeAccountRef)
    var lists by remember(activeAccountRef) { mutableStateOf<AccountRelayListsFfi?>(null) }
    var selectedKind by remember { mutableStateOf(RelayListKind.Nip65) }
    val scope = rememberCoroutineScope()

    suspend fun reloadLists() {
        lists = appState.accountRelayLists()
    }

    LaunchedEffect(activeAccountRef) {
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
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                SettingsGroup(title = stringResource(R.string.account_relay_lists), icon = Icons.Filled.Hub) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            RelayListSettingsContent(
                                lists = lists,
                                selectedKind = selectedKind,
                                onSelectKind = { selectedKind = it },
                                pendingUrl = editorState.pendingUrl,
                                onPendingUrlChange = { editorState.pendingUrl = it },
                                mutation = editorState.mutation,
                                canEdit = activeAccountRef != null,
                                onAddRelay = addRelay@{ kind, relay, onSuccess ->
                                    if (editorState.mutation != null) return@addRelay
                                    val accountAtStart = activeAccountRef
                                    val mutation = RelayMutation.Adding(kind, relay)
                                    editorState.mutation = mutation
                                    appState.launchMutation {
                                        try {
                                            val updated = appState.addAccountRelay(accountAtStart, kind, relay)
                                            if (updated != null && appState.activeAccountRef == accountAtStart) {
                                                lists = updated
                                                onSuccess()
                                            }
                                        } finally {
                                            if (editorState.mutation == mutation) editorState.mutation = null
                                        }
                                    }
                                },
                                onRemoveRelay = removeRelay@{ kind, relay ->
                                    if (editorState.mutation != null) return@removeRelay
                                    val accountAtStart = activeAccountRef
                                    val mutation = RelayMutation.Removing(kind, relay)
                                    editorState.mutation = mutation
                                    appState.launchMutation {
                                        try {
                                            val updated = appState.removeAccountRelay(accountAtStart, kind, relay)
                                            if (updated != null && appState.activeAccountRef == accountAtStart) {
                                                lists = updated
                                            }
                                        } finally {
                                            if (editorState.mutation == mutation) editorState.mutation = null
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

internal class RelayEditorState {
    var pendingUrl by mutableStateOf("")
    var mutation by mutableStateOf<RelayMutation?>(null)
}

internal sealed interface RelayMutation {
    val kind: RelayListKind

    data class Adding(
        override val kind: RelayListKind,
        val relay: String,
    ) : RelayMutation

    data class Removing(
        override val kind: RelayListKind,
        val relay: String,
    ) : RelayMutation
}

@Composable
internal fun rememberRelayState(accountRef: String?): RelayEditorState = remember(accountRef) { RelayEditorState() }

@Composable
@Suppress("FunctionNaming", "LongMethod", "LongParameterList")
internal fun RelayListSettingsContent(
    lists: AccountRelayListsFfi?,
    selectedKind: RelayListKind,
    onSelectKind: (RelayListKind) -> Unit,
    pendingUrl: String,
    onPendingUrlChange: (String) -> Unit,
    mutation: RelayMutation?,
    canEdit: Boolean,
    allowExternalRelayHosts: Boolean = BuildConfig.DEBUG,
    onAddRelay: (RelayListKind, String, onSuccess: () -> Unit) -> Unit,
    onRemoveRelay: (RelayListKind, String) -> Unit,
) {
    RelayListStatus(lists)

    val busy = mutation != null

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        relayListKinds.forEach { option ->
            FilterChip(
                selected = selectedKind == option,
                onClick = { onSelectKind(option) },
                enabled = !busy,
                label = { Text(stringResource(option.labelRes)) },
            )
        }
    }

    Text(
        text = stringResource(selectedKind.helpRes),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    val currentRelays = lists?.relaysFor(selectedKind).orEmpty()
    RelayRows(
        currentRelays = currentRelays,
        selectedKind = selectedKind,
        mutation = mutation,
        canEdit = canEdit,
        busy = busy,
        allowExternalRelayHosts = allowExternalRelayHosts,
        onRemoveRelay = onRemoveRelay,
    )
    RelayAddEditor(
        currentRelays = currentRelays,
        selectedKind = selectedKind,
        pendingUrl = pendingUrl,
        onPendingUrlChange = onPendingUrlChange,
        mutation = mutation,
        canEdit = canEdit,
        busy = busy,
        allowExternalRelayHosts = allowExternalRelayHosts,
        onAddRelay = onAddRelay,
    )
}

@Composable
@Suppress("FunctionNaming", "LongParameterList")
private fun RelayRows(
    currentRelays: List<String>,
    selectedKind: RelayListKind,
    mutation: RelayMutation?,
    canEdit: Boolean,
    busy: Boolean,
    allowExternalRelayHosts: Boolean,
    onRemoveRelay: (RelayListKind, String) -> Unit,
) {
    if (currentRelays.isEmpty()) {
        Text(stringResource(R.string.no_relays), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (
        currentRelays.any {
            relayUrlValidationResult(it, allowExternalRelayHosts) != RelayUrlValidationResult.Acceptable
        }
    ) {
        Text(
            text = stringResource(R.string.unsupported_relays_cleanup_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    currentRelays.forEach { relay ->
        val removing = mutation == RelayMutation.Removing(selectedKind, relay)
        val removingDescription = stringResource(R.string.remove_relay)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(relay, modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace)
            IconButton(
                onClick = { onRemoveRelay(selectedKind, relay) },
                enabled =
                    canEdit &&
                        !busy &&
                        canRemoveRelay(currentRelays, relay, allowExternalRelayHosts),
            ) {
                if (removing) {
                    CircularProgressIndicator(
                        modifier =
                            Modifier
                                .size(20.dp)
                                .semantics { contentDescription = removingDescription },
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.remove_relay))
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionNaming", "LongParameterList")
private fun RelayAddEditor(
    currentRelays: List<String>,
    selectedKind: RelayListKind,
    pendingUrl: String,
    onPendingUrlChange: (String) -> Unit,
    mutation: RelayMutation?,
    canEdit: Boolean,
    busy: Boolean,
    allowExternalRelayHosts: Boolean,
    onAddRelay: (RelayListKind, String, onSuccess: () -> Unit) -> Unit,
) {
    val trimmedPendingUrl = pendingUrl.trim()
    val pendingValidation =
        trimmedPendingUrl
            .takeIf { it.isNotEmpty() }
            ?.let { relayUrlValidationResult(it, allowExternalRelayHosts) }
    val inputErrorRes = pendingRelayErrorRes(pendingValidation)
    val adding = mutation is RelayMutation.Adding && mutation.kind == selectedKind
    val addingDescription = stringResource(R.string.add_relay)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = pendingUrl,
            onValueChange = onPendingUrlChange,
            label = { Text("wss://relay.example.com") },
            singleLine = true,
            enabled = canEdit && !busy,
            isError = inputErrorRes != null,
            supportingText = inputErrorRes?.let { errorRes -> { Text(stringResource(errorRes)) } },
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
                onAddRelay(selectedKind, trimmedPendingUrl) {
                    onPendingUrlChange("")
                }
            },
            modifier = Modifier.size(48.dp),
            enabled =
                !busy &&
                    canEdit &&
                    pendingValidation == RelayUrlValidationResult.Acceptable &&
                    !currentRelays.contains(trimmedPendingUrl),
        ) {
            if (adding) {
                CircularProgressIndicator(
                    modifier =
                        Modifier
                            .size(20.dp)
                            .semantics { contentDescription = addingDescription },
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_relay))
            }
        }
    }
}

private fun pendingRelayErrorRes(validation: RelayUrlValidationResult?): Int? =
    when (validation) {
        RelayUrlValidationResult.UnsupportedHost -> R.string.error_external_relay_not_supported
        RelayUrlValidationResult.Invalid -> R.string.error_invalid_relay_url
        RelayUrlValidationResult.Acceptable,
        null,
        -> null
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

internal val RelayListKind.helpRes: Int
    get() =
        when (this) {
            RelayListKind.Nip65 -> R.string.relay_posting_help
            RelayListKind.Inbox -> R.string.relay_inbox_help
        }

private fun AccountRelayListsFfi.relaysFor(kind: RelayListKind): List<String> =
    when (kind) {
        RelayListKind.Nip65 -> nip65.relays
        RelayListKind.Inbox -> inbox.relays
    }

@Composable
@Suppress("FunctionNaming")
private fun RelayListStatus(lists: AccountRelayListsFfi?) {
    if (lists == null) {
        Text(
            text = stringResource(R.string.no_relay_projection),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    if (lists.complete) {
        Text(
            text = stringResource(R.string.all_relay_lists_published),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    } else {
        Text(
            text = stringResource(R.string.missing_relay_lists, missingRelayListLabels(lists.missing)),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.error,
        )
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
