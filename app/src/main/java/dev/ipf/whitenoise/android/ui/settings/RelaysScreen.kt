package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.AccountRelayListsFfi
import dev.ipf.marmotkit.MissingRelayListKindFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.addAccountRelay
import dev.ipf.whitenoise.android.state.canRemoveRelay
import dev.ipf.whitenoise.android.state.loadAccountRelayLists
import dev.ipf.whitenoise.android.state.publishMissingRelayLists
import dev.ipf.whitenoise.android.state.refreshAccountRelayLists
import dev.ipf.whitenoise.android.state.removeAccountRelay
import dev.ipf.whitenoise.android.state.restoreDefaultAccountRelays
import dev.ipf.whitenoise.android.ui.common.ConfirmDialog
import dev.ipf.whitenoise.android.ui.common.Explanation
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseFilledTonalButton
import dev.ipf.whitenoise.android.ui.common.explainOnLongPress
import dev.ipf.whitenoise.android.ui.navigation.SettingsDetail
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import kotlinx.coroutines.flow.first

/**
 * Relays: the account's published relay lists with their recovery actions, then every relay the account uses with
 * the lists it serves, an Add relay sheet, a detail page per relay and Restore default relays. Every edit goes
 * through MarmotKit's validated publish path; nothing here mutates group routes (D08).
 *
 * The list and one relay are separate Settings pages (#4095): the list opens a relay through [onOpenRelay], and the
 * relay page passes its URL as [selectedUrl]. A relay that is no longer in the account's lists, as after Remove,
 * returns to the list.
 */
@Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod", "LongParameterList")
@Composable
internal fun RelaysScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
    onFeedback: (dev.ipf.whitenoise.android.state.ToastMessage) -> Unit = {},
    destination: SettingsDetail = SettingsDetail.Relays,
    selectedUrl: String? = null,
    onOpenRelay: (String) -> Unit = {},
) {
    val account = appState.activeAccountRef
    val feedbackRuntime = appState.runtimeGeneration

    /** Shows publication feedback once the owning account is still active. */
    fun deliverFeedback(previous: dev.ipf.whitenoise.android.state.ToastMessage?) {
        val sameOwner = appState.activeAccountRef == account && appState.runtimeGeneration == feedbackRuntime
        val fresh = appState.toast?.takeIf { it !== previous }
        if (sameOwner && fresh != null) onFeedback(fresh)
    }
    var lists by remember(account) { mutableStateOf<AccountRelayListsFfi?>(null) }
    var publication by remember(account) { mutableStateOf(RelayPublicationState()) }
    val operations = remember(appState, account) { appState.relayOperationState(account) }
    val busy = operations.busy
    var addSheet by rememberSaveable(account) { mutableStateOf(false) }
    var rejectedUrl by rememberSaveable(account) { mutableStateOf<String?>(null) }
    var restoreDialog by rememberSaveable(account) { mutableStateOf(false) }

    /** Runs a relay list publication with progress and feedback. */
    fun runPublication(
        operation: RelayPublicationOperation,
        block: suspend () -> AccountRelayListsFfi?,
    ) {
        operations.launch(
            launcher = appState::launchMutation,
            onStarted = { publication = RelayPublicationState(running = operation) },
        ) {
            val previousToast = appState.toast
            val updated = block()
            deliverFeedback(previousToast)
            // A multi-kind publish may have partially succeeded. Always expose the authoritative projection.
            val projection = updated ?: account?.let { appState.loadAccountRelayLists(it) }
            if (appState.activeAccountRef != account) return@launch
            if (projection != null) lists = projection
            publication = RelayPublicationState(failed = operation.takeIf { updated == null })
        }
    }

    /** Runs a relay list edit under the shared mutation launcher. */
    fun runEdit(block: suspend () -> AccountRelayListsFfi?) {
        operations.launch(launcher = appState::launchMutation) {
            val previousToast = appState.toast
            val result = block()
            deliverFeedback(previousToast)
            val updated = result ?: account?.let { appState.loadAccountRelayLists(it) }
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
            val currentOnBack by rememberUpdatedState(onBack)
            // Lists still loading show the page's frame; loaded lists without the relay return to the list.
            LaunchedEffect(lists, busy) { if (lists != null && !busy) currentOnBack() }
            SettingsScaffold(title = stringResource(R.string.relay), onBack = onBack) {
                CircularProgressIndicator(Modifier.padding(WhiteNoiseSpacing.Section).testTag("relay.details.loading"))
            }
            return
        } else {
            RelayDetailsScreen(
                relay = relay,
                lists = lists,
                busy = busy,
                onBack = onBack,
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
                    onBack()
                },
            )
            return
        }
    }

    RelaysContent(
        state = RelaysUiState(lists = lists, publication = publication, busy = busy),
        destination = destination,
        onBack = onBack,
        onOpenRelay = onOpenRelay,
        onAdd = { addSheet = true },
        onRefresh = {
            runPublication(RelayPublicationOperation.Refresh) { account?.let { appState.refreshAccountRelayLists(it) } }
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
            ownerKey = account to appState.runtimeGeneration,
            onDismiss = { addSheet = false },
            onAdd = { url, roles, finishExit ->
                rejectedUrl = null
                runEdit {
                    var updated: AccountRelayListsFfi? = null
                    var allAdded = true
                    missingRelayRoles(relays, url, roles).forEach { role ->
                        val result = appState.addAccountRelay(account, role.kind, url)
                        if (result == null) allAdded = false else updated = result
                    }
                    if (allAdded) finishExit() else rejectedUrl = url
                    updated
                }
            },
        )
    }
    if (restoreDialog) {
        ConfirmDialog(
            title = stringResource(R.string.restore_default_relays_question),
            message = stringResource(R.string.relay_restore_detail),
            confirmLabel = stringResource(R.string.restore_defaults),
            onConfirm = {
                restoreDialog = false
                runEdit { appState.restoreDefaultAccountRelays(account) }
            },
            onDismiss = { restoreDialog = false },
            // Custom relays the person added are discarded and must be re-added by hand.
            destructive = true,
            confirmModifier =
                Modifier.explainOnLongPress(
                    Explanation(
                        stringResource(R.string.restore_defaults),
                        stringResource(R.string.explanation_relays_restore_confirm),
                    ),
                ),
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
    destination: SettingsDetail = SettingsDetail.Relays,
) {
    val lists = state.lists
    val relays = lists?.let(::accountRelays).orEmpty()
    val defaultsInUse = lists != null && lists.usesDefaultRelays()
    val busy = state.busy || state.publication.running != null
    SettingsScaffold(title = stringResource(R.string.relays), onBack = onBack) {
        SettingsList {
            // One callout per kind of concern, so an onion relay is never described as an insecure connection.
            relays.map(AccountRelay::attention).distinct().sorted().forEach { attention ->
                val callout = attention.callout() ?: return@forEach
                item {
                    SettingsCallout(
                        text = stringResource(callout.detailRes),
                        modifier = Modifier.testTag(callout.testTag),
                        title = stringResource(callout.titleRes),
                        icon = R.drawable.ic_warning,
                    )
                }
            }
            item(key = "search.Relays.2") { SettingsSection(stringResource(R.string.relay_lists_section)) }
            item(key = "search.Relays.3") {
                SettingsGroup(modifier = Modifier.testTag("relay.publication.group")) {
                    relayPublicationRows(state, onRefresh, onPublishMissing)
                }
            }
            item(key = "search.Relays.4") { SettingsExplainer(stringResource(relayListsHelpRes(lists))) }
            item(key = "search.Relays.5") { SettingsSection(stringResource(R.string.profile_relays)) }
            item(key = "search.Relays.6") {
                SettingsGroup(
                    modifier = Modifier.testTag("relays.group"),
                    destination = destination,
                    surface = "account-relays",
                ) {
                    relays.forEach { relay ->
                        row(relay.url) { context ->
                            SettingsLink(
                                context = context,
                                title = relay.name,
                                onClick = { onOpenRelay(relay.url) },
                                enabled = !busy,
                                modifier = Modifier.testTag("relays.row.${relay.url}"),
                                subtitle = relaySummary(relay),
                                explanation = stringResource(R.string.explanation_relays_relay_row),
                                leading = { SettingsLeadingIcon(R.drawable.ic_dns) },
                            )
                        }
                    }
                    row("add") { context ->
                        SettingsAction(
                            context = context,
                            title = stringResource(R.string.add_relay),
                            onClick = onAdd,
                            enabled = lists != null && !busy,
                            leading = { SettingsLeadingIcon(R.drawable.ic_add) },
                            explanation = stringResource(R.string.explanation_relays_add),
                        )
                    }
                }
            }
            item(key = "search.Relays.7") { SettingsExplainer(stringResource(R.string.relays_explanation)) }
            item(key = "search.Relays.8") {
                WhiteNoiseFilledTonalButton(
                    onClick = onRestore,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                start = WhiteNoiseSpacing.CompactScreenMargin,
                                top = WhiteNoiseSpacing.Section,
                                end = WhiteNoiseSpacing.CompactScreenMargin,
                            ).testTag("relays.restore")
                            .explainOnLongPress(
                                Explanation(
                                    stringResource(R.string.restore_default_relays),
                                    stringResource(R.string.explanation_relays_restore),
                                ),
                            ),
                    enabled = lists != null && !defaultsInUse && !busy,
                ) { Text(stringResource(R.string.restore_default_relays)) }
            }
            item(key = "search.Relays.9") {
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
            explanation = stringResource(R.string.explanation_relays_posting),
            leading = { SettingsLeadingIcon(R.drawable.ic_send) },
        )
    }
    row("receiving") { context ->
        SettingsValue(
            context = context,
            title = stringResource(R.string.inbox),
            value = stringResource(relayListStatusRes(lists, MissingRelayListKindFfi.INBOX)),
            modifier = Modifier.testTag("relay.publication.receiving"),
            explanation = stringResource(R.string.explanation_relays_receiving),
            leading = { SettingsLeadingIcon(R.drawable.ic_inbox) },
        )
    }
    relayPublicationAction(state, onRefresh, onPublishMissing)
}

/** The single action the current publication state allows: progress, Retry, Refresh, or Publish missing. */
@Suppress("LongMethod")
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
                    explanation = stringResource(R.string.explanation_relays_retry),
                    enabled = !state.busy,
                    leading = { SettingsLeadingIcon(R.drawable.ic_replay) },
                )
            }
        else -> {
            row("refresh") { context ->
                SettingsAction(
                    context = context,
                    title = stringResource(R.string.relay_list_refresh),
                    onClick = onRefresh,
                    modifier = Modifier.testTag("relay.publication.refresh"),
                    explanation = stringResource(R.string.explanation_relays_refresh),
                    enabled = !state.busy,
                    leading = { SettingsLeadingIcon(R.drawable.ic_refresh) },
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
                        explanation = stringResource(R.string.explanation_relays_publish_missing),
                        enabled = !state.busy,
                        leading = { SettingsLeadingIcon(R.drawable.ic_publish) },
                    )
                }
            }
        }
    }
}

/** One relay: its host, address and the lists it serves, with Use-for switches and Remove relay. */
@Suppress("FunctionNaming", "LongMethod")
@Composable
internal fun RelayDetailsScreen(
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
            item(key = "search.Relays.10") {
                SettingsGroup(modifier = Modifier.testTag("relay.details.metadata")) {
                    row("name") { context ->
                        SettingsValue(
                            context,
                            stringResource(R.string.name),
                            relay.name,
                            leading = { SettingsLeadingIcon(R.drawable.ic_dns) },
                        )
                    }
                    row("url") { context ->
                        SettingsValue(
                            context,
                            stringResource(R.string.url),
                            relay.url,
                            leading = { SettingsLeadingIcon(R.drawable.ic_link) },
                        )
                    }
                }
            }
            item(key = "search.Relays.11") { SettingsSection(stringResource(R.string.use_for)) }
            item(key = "search.Relays.12") {
                SettingsGroup(modifier = Modifier.testTag("relay.details.roles")) {
                    AccountRelayRole.entries.forEach { role ->
                        row(role.name) { context ->
                            SettingsSwitch(
                                context = context,
                                title = stringResource(role.labelRes),
                                checked = role in relay.roles,
                                onCheckedChange = { onSetRole(role, it) },
                                subtitle = stringResource(role.helpRes),
                                explanation = stringResource(role.explanationRes()),
                                enabled = !busy && role !in blockingRoles,
                                leading = { SettingsLeadingIcon(role.iconRes) },
                            )
                        }
                    }
                }
            }
            if (blockingRoles.isNotEmpty()) {
                item(key = "search.Relays.13") {
                    SettingsExplainer(
                        stringResource(
                            R.string.relay_remove_last_detail,
                            blockingRoles.map { stringResource(it.labelRes) }.joinToString(),
                        ),
                    )
                }
            }
            item(key = "search.Relays.14") {
                SettingsGroup {
                    row("remove") { context ->
                        SettingsAction(
                            context = context,
                            title = stringResource(R.string.remove_relay),
                            onClick = { removeDialog = true },
                            enabled = !busy && blockingRoles.isEmpty(),
                            destructive = true,
                            explanation = stringResource(R.string.explanation_relays_remove),
                            leading = {
                                SettingsLeadingIcon(R.drawable.ic_delete, tint = MaterialTheme.colorScheme.error)
                            },
                        )
                    }
                }
            }
        }
    }
    if (removeDialog) {
        ConfirmDialog(
            title = stringResource(R.string.remove_named_relay, relay.name),
            message = stringResource(R.string.relay_remove_detail),
            confirmLabel = stringResource(R.string.remove_relay),
            onConfirm = {
                removeDialog = false
                onRemove()
            },
            onDismiss = { removeDialog = false },
            destructive = true,
            confirmModifier =
                Modifier.explainOnLongPress(
                    Explanation(
                        stringResource(R.string.remove_relay),
                        stringResource(R.string.explanation_relays_remove_confirm),
                    ),
                ),
        )
    }
}
