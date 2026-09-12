package dev.ipf.whitenoise.android.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.AccountRelayListsFfi
import dev.ipf.marmotkit.MissingRelayListKindFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.RelayUrlValidationResult
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.canRemoveRelay
import dev.ipf.whitenoise.android.state.publishMissingRelayLists
import dev.ipf.whitenoise.android.state.relayUrlValidationResult
import dev.ipf.whitenoise.android.state.restoreDefaultAccountRelays
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseFilledTonalButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseModalBottomSheet
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseSheetHeader
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseTextField
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import kotlinx.coroutines.flow.first

/**
 * Relays: the account's published relay lists with their recovery actions, then every relay the account uses with
 * the lists it serves, an Add relay sheet, a detail page per relay and Restore default relays. Every edit goes
 * through MarmotKit's validated publish path; nothing here mutates group routes (D08).
 */
@Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod")
@Composable
internal fun RelaysScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
) {
    val account = appState.activeAccountRef
    var lists by remember(account) { mutableStateOf<AccountRelayListsFfi?>(null) }
    var publication by remember(account) { mutableStateOf(RelayPublicationState()) }
    val operations = remember(appState, account) { appState.relayOperationState(account) }
    val busy = operations.busy
    var selectedUrl by rememberSaveable(account) { mutableStateOf<String?>(null) }
    var addSheet by rememberSaveable(account) { mutableStateOf(false) }
    var rejectedUrl by rememberSaveable(account) { mutableStateOf<String?>(null) }
    var restoreDialog by rememberSaveable(account) { mutableStateOf(false) }

    fun runPublication(
        operation: RelayPublicationOperation,
        block: suspend () -> AccountRelayListsFfi?,
    ) {
        operations.launch(
            launcher = appState::launchMutation,
            onStarted = { publication = RelayPublicationState(running = operation) },
        ) {
            val updated = block()
            // A multi-kind publish may have partially succeeded. Always expose the authoritative projection.
            val projection = updated ?: account?.let { appState.loadAccountRelayLists(it) }
            if (appState.activeAccountRef != account) return@launch
            if (projection != null) lists = projection
            publication = RelayPublicationState(failed = operation.takeIf { updated == null })
        }
    }

    fun runEdit(block: suspend () -> AccountRelayListsFfi?) {
        operations.launch(launcher = appState::launchMutation) {
            val updated = block() ?: account?.let { appState.loadAccountRelayLists(it) }
            if (updated != null && appState.activeAccountRef == account) lists = updated
        }
    }

    LaunchedEffect(account, operations) {
        // Reopening this account during a mutation must load its result after the existing operation finishes.
        snapshotFlow { operations.busy }.first { !it }
        runPublication(RelayPublicationOperation.Refresh) { account?.let { appState.loadAccountRelayLists(it) } }
    }

    val relays = lists?.let(::accountRelays).orEmpty()
    selectedUrl?.let { url ->
        val relay = relays.firstOrNull { it.url == url }
        if (relay == null) {
            selectedUrl = null
        } else {
            BackHandler { selectedUrl = null }
            RelayDetailsScreen(
                relay = relay,
                lists = lists,
                busy = busy,
                onBack = { selectedUrl = null },
                onSetRole = { role, enabled ->
                    runEdit {
                        if (enabled) {
                            appState.addAccountRelay(account, role.kind, url)
                        } else {
                            appState.removeAccountRelay(account, role.kind, url)
                        }
                    }
                },
                onRemove = {
                    runEdit {
                        var updated: AccountRelayListsFfi? = lists
                        relay.roles.forEach { role ->
                            updated = appState.removeAccountRelay(account, role.kind, url) ?: updated
                        }
                        updated
                    }
                    selectedUrl = null
                },
            )
            return
        }
    }

    RelaysContent(
        state = RelaysUiState(lists = lists, publication = publication, busy = busy),
        onBack = onBack,
        onOpenRelay = { selectedUrl = it },
        onAdd = { addSheet = true },
        onRefresh = {
            runPublication(RelayPublicationOperation.Refresh) { account?.let { appState.loadAccountRelayLists(it) } }
        },
        onPublishMissing = {
            runPublication(RelayPublicationOperation.PublishMissing) { appState.publishMissingRelayLists(account) }
        },
        onRestore = { restoreDialog = true },
    )
    if (addSheet) {
        AddRelaySheet(
            existing = relays,
            busy = busy,
            rejectedUrl = rejectedUrl,
            onDismiss = { addSheet = false },
            onAdd = { url, roles ->
                rejectedUrl = null
                runEdit {
                    var updated: AccountRelayListsFfi? = null
                    var allAdded = true
                    missingRelayRoles(relays, url, roles).forEach { role ->
                        val result = appState.addAccountRelay(account, role.kind, url)
                        if (result == null) allAdded = false else updated = result
                    }
                    if (allAdded) addSheet = false else rejectedUrl = url
                    updated
                }
            },
        )
    }
    if (restoreDialog) {
        WhiteNoiseAlertDialog(
            onDismissRequest = { restoreDialog = false },
            title = { Text(stringResource(R.string.restore_default_relays_question)) },
            text = { Text(stringResource(R.string.relay_restore_detail)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        restoreDialog = false
                        runEdit { appState.restoreDefaultAccountRelays(account) }
                    },
                ) { Text(stringResource(R.string.restore_defaults), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { restoreDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** The list screen without any state ownership, so tests can render every projection. */
@Suppress("FunctionNaming", "LongMethod", "LongParameterList")
@Composable
internal fun RelaysContent(
    state: RelaysUiState,
    onBack: () -> Unit,
    onOpenRelay: (String) -> Unit,
    onAdd: () -> Unit,
    onRefresh: () -> Unit,
    onPublishMissing: () -> Unit,
    onRestore: () -> Unit,
) {
    val lists = state.lists
    val relays = lists?.let(::accountRelays).orEmpty()
    val defaultsInUse = lists != null && lists.usesDefaultRelays()
    val busy = state.busy || state.publication.running != null
    SettingsScaffold(title = stringResource(R.string.relays), onBack = onBack) {
        SettingsList {
            if (relays.any(AccountRelay::needsAttention)) {
                item {
                    SettingsCallout(
                        text = stringResource(R.string.relay_imported_issue_detail),
                        modifier = Modifier.testTag("relays.imported.issue"),
                        title = stringResource(R.string.relay_imported_issue_title),
                        icon = R.drawable.ic_warning,
                    )
                }
            }
            item { SettingsSection(stringResource(R.string.relay_lists_section)) }
            item {
                SettingsGroup(modifier = Modifier.testTag("relay.publication.group")) {
                    relayPublicationRows(state, onRefresh, onPublishMissing)
                }
            }
            item { SettingsExplainer(stringResource(relayListsHelpRes(lists))) }
            item { SettingsSection(stringResource(R.string.profile_relays)) }
            item {
                SettingsGroup(modifier = Modifier.testTag("relays.group")) {
                    relays.forEach { relay ->
                        row(relay.url) { context ->
                            SettingsLink(
                                context = context,
                                title = relay.name,
                                onClick = { onOpenRelay(relay.url) },
                                enabled = !busy,
                                modifier = Modifier.testTag("relays.row.${relay.url}"),
                                subtitle = relaySummary(relay),
                            )
                        }
                    }
                    row("add") { context ->
                        SettingsAction(
                            context = context,
                            title = stringResource(R.string.add_relay),
                            onClick = onAdd,
                            enabled = lists != null && !busy,
                            leading = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                        )
                    }
                }
            }
            item { SettingsExplainer(stringResource(R.string.relays_explanation)) }
            item {
                WhiteNoiseFilledTonalButton(
                    onClick = onRestore,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                start = WhiteNoiseSpacing.CompactScreenMargin,
                                top = WhiteNoiseSpacing.Section,
                                end = WhiteNoiseSpacing.CompactScreenMargin,
                            ).testTag("relays.restore"),
                    enabled = lists != null && !defaultsInUse && !busy,
                ) { Text(stringResource(R.string.restore_default_relays)) }
            }
            item {
                SettingsExplainer(
                    stringResource(if (defaultsInUse) R.string.relay_defaults_in_use else R.string.relay_restore_help),
                )
            }
        }
    }
}

/** Published / Missing / Status unavailable per list, then the one contextual action the state allows. */
private fun SettingsGroupScope.relayPublicationRows(
    state: RelaysUiState,
    onRefresh: () -> Unit,
    onPublishMissing: () -> Unit,
) {
    val lists = state.lists
    row("posting") { context ->
        SettingsValue(
            context = context,
            title = stringResource(R.string.nip_65),
            value = stringResource(relayListStatusRes(lists, MissingRelayListKindFfi.NIP65)),
            modifier = Modifier.testTag("relay.publication.posting"),
        )
    }
    row("receiving") { context ->
        SettingsValue(
            context = context,
            title = stringResource(R.string.inbox),
            value = stringResource(relayListStatusRes(lists, MissingRelayListKindFfi.INBOX)),
            modifier = Modifier.testTag("relay.publication.receiving"),
        )
    }
    relayPublicationAction(state, onRefresh, onPublishMissing)
}

/** The single action the current publication state allows: progress, Retry, Refresh, or Publish missing. */
private fun SettingsGroupScope.relayPublicationAction(
    state: RelaysUiState,
    onRefresh: () -> Unit,
    onPublishMissing: () -> Unit,
) {
    val lists = state.lists
    val running = state.publication.running
    val failed = state.publication.failed
    when {
        running != null ->
            row("running") { context ->
                SettingsAction(
                    context = context,
                    title = stringResource(running.progressRes),
                    onClick = {},
                    enabled = false,
                    leading = {
                        CircularProgressIndicator(
                            Modifier.size(RelayProgressSize).clearAndSetSemantics {},
                            strokeWidth = 2.dp,
                        )
                    },
                )
            }
        failed != null ->
            row("retry") { context ->
                SettingsAction(
                    context = context,
                    title = stringResource(R.string.retry),
                    onClick = if (failed == RelayPublicationOperation.Refresh) onRefresh else onPublishMissing,
                    modifier = Modifier.testTag("relay.publication.retry"),
                    subtitle = stringResource(failed.failureRes),
                    enabled = !state.busy,
                )
            }
        else -> {
            row("refresh") { context ->
                SettingsAction(
                    context = context,
                    title = stringResource(R.string.relay_list_refresh),
                    onClick = onRefresh,
                    modifier = Modifier.testTag("relay.publication.refresh"),
                    enabled = !state.busy,
                )
            }
            if (lists != null && lists.missing.isNotEmpty()) {
                row("publish") { context ->
                    SettingsAction(
                        context = context,
                        title = stringResource(R.string.relay_list_publish),
                        onClick = onPublishMissing,
                        modifier = Modifier.testTag("relay.publication.publish"),
                        subtitle = stringResource(R.string.relay_list_publish_help),
                        enabled = !state.busy,
                    )
                }
            }
        }
    }
}

/** One relay: its host, address and the lists it serves, with Use-for switches and Remove relay. */
@Suppress("FunctionNaming", "LongMethod")
@Composable
private fun RelayDetailsScreen(
    relay: AccountRelay,
    lists: AccountRelayListsFfi?,
    busy: Boolean,
    onBack: () -> Unit,
    onSetRole: (AccountRelayRole, Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    var removeDialog by rememberSaveable(relay.url) { mutableStateOf(false) }
    val blockingRoles =
        relay.roles.filter { role -> lists != null && !canRemoveRelay(lists.relaysFor(role.kind), relay.url) }
    SettingsScaffold(title = stringResource(R.string.relay), onBack = onBack) {
        SettingsList {
            item {
                SettingsGroup(modifier = Modifier.testTag("relay.details.metadata")) {
                    row("name") { context -> SettingsValue(context, stringResource(R.string.name), relay.name) }
                    row("url") { context -> SettingsValue(context, stringResource(R.string.url), relay.url) }
                }
            }
            item { SettingsSection(stringResource(R.string.use_for)) }
            item {
                SettingsGroup(modifier = Modifier.testTag("relay.details.roles")) {
                    AccountRelayRole.entries.forEach { role ->
                        row(role.name) { context ->
                            SettingsSwitch(
                                context = context,
                                title = stringResource(role.labelRes),
                                checked = role in relay.roles,
                                onCheckedChange = { onSetRole(role, it) },
                                subtitle = stringResource(role.helpRes),
                                enabled = !busy && role !in blockingRoles,
                            )
                        }
                    }
                }
            }
            if (blockingRoles.isNotEmpty()) {
                item {
                    SettingsExplainer(
                        stringResource(
                            R.string.relay_remove_last_detail,
                            blockingRoles.map { stringResource(it.labelRes) }.joinToString(),
                        ),
                    )
                }
            }
            item {
                SettingsGroup {
                    row("remove") { context ->
                        SettingsAction(
                            context = context,
                            title = stringResource(R.string.remove_relay),
                            onClick = { removeDialog = true },
                            enabled = !busy && blockingRoles.isEmpty(),
                            destructive = true,
                        )
                    }
                }
            }
        }
    }
    if (removeDialog) {
        WhiteNoiseAlertDialog(
            onDismissRequest = { removeDialog = false },
            title = { Text(stringResource(R.string.remove_named_relay, relay.name)) },
            text = { Text(stringResource(R.string.relay_remove_detail)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        removeDialog = false
                        onRemove()
                    },
                ) { Text(stringResource(R.string.remove_relay), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { removeDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** The Add relay task sheet: a secure URL, the lists it should join, and one pinned Add action. */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming", "LongMethod")
@Composable
private fun AddRelaySheet(
    existing: List<AccountRelay>,
    busy: Boolean,
    rejectedUrl: String?,
    onDismiss: () -> Unit,
    onAdd: (String, Set<AccountRelayRole>) -> Unit,
) {
    val value: TextFieldState = rememberTextFieldState()
    var roles by rememberSaveable { mutableStateOf(AccountRelayRole.entries.toSet()) }
    val currentValue = value.text.toString().trim()
    val acceptable =
        currentValue.isNotEmpty() && relayUrlValidationResult(currentValue) == RelayUrlValidationResult.Acceptable
    val duplicate = acceptable && roles.isNotEmpty() && missingRelayRoles(existing, currentValue, roles).isEmpty()
    val rejected = rejectedUrl != null && rejectedUrl == currentValue
    val canAdd = acceptable && !duplicate && roles.isNotEmpty() && !busy
    WhiteNoiseModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            WhiteNoiseSheetHeader(title = stringResource(R.string.add_relay), onClose = onDismiss)
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                WhiteNoiseTextField(
                    state = value,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin)
                            .testTag("relay.add.url"),
                    label = { Text(stringResource(R.string.relay_url)) },
                    placeholder = { Text("wss://relay.example.com") },
                    supportingText = { Text(stringResource(R.string.relay_url_help)) },
                    errorMessage = if (duplicate || rejected) stringResource(R.string.relay_unique_url_error) else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    lineLimits = TextFieldLineLimits.SingleLine,
                )
                SettingsSection(stringResource(R.string.use_for))
                SettingsGroup(modifier = Modifier.testTag("relay.add.roles")) {
                    AccountRelayRole.entries.forEach { role ->
                        row(role.name) { context ->
                            SettingsSwitch(
                                context = context,
                                title = stringResource(role.labelRes),
                                checked = role in roles,
                                onCheckedChange = { selected -> roles = if (selected) roles + role else roles - role },
                                subtitle = stringResource(role.helpRes),
                            )
                        }
                    }
                }
                if (roles.isEmpty()) SettingsExplainer(stringResource(R.string.choose_at_least_one_role))
            }
            SettingsBottomAction(color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
                WhiteNoiseButton(
                    onClick = { onAdd(currentValue, roles) },
                    modifier = Modifier.fillMaxWidth().testTag("relay.add.submit"),
                    enabled = canAdd,
                    loading = busy,
                ) { Text(stringResource(R.string.add_relay)) }
            }
        }
    }
}

/** Host and, when the address is not a secure wss:// URL, the needs-attention note. */
@Composable
private fun relaySummary(relay: AccountRelay): String =
    if (relay.needsAttention) "${relay.url} · ${stringResource(R.string.relay_needs_attention)}" else relay.url

private val RelayProgressSize = 20.dp
