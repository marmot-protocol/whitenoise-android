package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.AccountKeyPackageFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseFilledTonalButton
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import kotlinx.coroutines.launch

internal enum class KeyPackagesSection {
    Publishing,
    Published,
    Empty,
    PackageList,
}

internal data class KeyPackagesState(
    val sections: List<KeyPackagesSection>,
    val actionsEnabled: Boolean,
    val packageActionsEnabled: Boolean,
    val showLoadingIndicator: Boolean,
    val packageCount: Int,
)

/** Keeps loading distinct from a confirmed empty inventory and gates native mutations while working. */
internal fun keyPackagesState(
    hasActiveAccount: Boolean,
    loaded: Boolean,
    loading: Boolean,
    working: Boolean,
    packageCount: Int,
): KeyPackagesState =
    KeyPackagesState(
        sections =
            buildList {
                add(KeyPackagesSection.Publishing)
                add(KeyPackagesSection.Published)
                if (loaded && packageCount == 0 && !loading) add(KeyPackagesSection.Empty)
                if (packageCount > 0) add(KeyPackagesSection.PackageList)
            },
        actionsEnabled = hasActiveAccount && !loading && !working,
        packageActionsEnabled = !working,
        showLoadingIndicator = loading,
        packageCount = packageCount,
    )

private const val NOSTR_EVENT_ID_HEX_LENGTH = 64

/** Only relay records with a complete event identifier can target a Nostr deletion. */
internal fun AccountKeyPackageFfi.isRelayDeletionTarget(): Boolean =
    relay &&
        eventIdHex.length == NOSTR_EVENT_ID_HEX_LENGTH &&
        eventIdHex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

/** Published inventory follows the native relay flag, never local retention or a guessed identifier. */
internal fun List<AccountKeyPackageFfi>.relayBacked(): List<AccountKeyPackageFfi> = filter { it.relay }

internal const val KEY_PACKAGES_CONTENT_TAG = "key-packages-content"

/** Shows the active account's KeyPackages and refreshes after acknowledged mutations. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KeyPackagesScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
    loadKeyPackages: suspend (refreshFromNetwork: Boolean) -> List<AccountKeyPackageFfi> = appState::fetchKeyPackages,
    deleteKeyPackage: suspend (accountRef: String, eventIdHex: String, sourceRelays: List<String>) -> Boolean =
        appState::deleteKeyPackage,
) {
    val accountRef = appState.activeAccountRef
    key(accountRef) {
        KeyPackagesScreenForAccount(
            appState = appState,
            accountRef = accountRef,
            onBack = onBack,
            loadKeyPackages = loadKeyPackages,
            deleteKeyPackage = deleteKeyPackage,
        )
    }
}

/** Owns one account generation so a switch cancels inventory loads and deletion work. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("FunctionNaming", "LongMethod") // One account-keyed owner keeps mutation and reload cancellation atomic.
private fun KeyPackagesScreenForAccount(
    appState: WhiteNoiseAppState,
    accountRef: String?,
    onBack: () -> Unit,
    loadKeyPackages: suspend (refreshFromNetwork: Boolean) -> List<AccountKeyPackageFfi>,
    deleteKeyPackage: suspend (accountRef: String, eventIdHex: String, sourceRelays: List<String>) -> Boolean,
) {
    val scope = rememberCoroutineScope()
    var packages by remember { mutableStateOf<List<AccountKeyPackageFfi>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<AccountKeyPackageFfi?>(null) }

    suspend fun reload(refreshFromNetwork: Boolean = false) {
        loading = true
        try {
            packages = loadKeyPackages(refreshFromNetwork)
            loaded = true
        } finally {
            loading = false
        }
    }

    LaunchedEffect(accountRef) {
        if (accountRef != null) reload()
    }

    KeyPackagesContent(
        state =
            keyPackagesState(
                hasActiveAccount = accountRef != null,
                loaded = loaded,
                loading = loading,
                working = working,
                packageCount = packages.relayBacked().size,
            ),
        packages = packages,
        onBack = onBack,
        onRefresh = { scope.launch { reload(refreshFromNetwork = true) } },
        onRepublish = {
            working = true
            appState.launchMutation {
                try {
                    appState.republishKeyPackage()
                    reload(refreshFromNetwork = true)
                } finally {
                    working = false
                }
            }
        },
        onPublishNew = {
            working = true
            appState.launchMutation {
                try {
                    appState.publishNewKeyPackage()
                    reload(refreshFromNetwork = true)
                } finally {
                    working = false
                }
            }
        },
        onDelete = { keyPackage ->
            if (keyPackage.isRelayDeletionTarget()) pendingDelete = keyPackage
        },
    )

    pendingDelete?.let { kp ->
        WhiteNoiseAlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_key_package_question)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.delete_key_package_help))
                    Text(
                        stringResource(R.string.event_value, IdentityFormatter.short(kp.eventIdHex)),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = pendingDelete?.takeIf { it.isRelayDeletionTarget() } ?: return@TextButton
                    val targetAccount = accountRef ?: return@TextButton
                    pendingDelete = null
                    working = true
                    scope.launch {
                        try {
                            if (deleteKeyPackage(targetAccount, target.eventIdHex, target.sourceRelays)) {
                                reload(refreshFromNetwork = true)
                            }
                        } finally {
                            working = false
                        }
                    }
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** Publication controls and real inventory in the prototype's Published and Retained Local Material groups. */
@Composable
@Suppress("FunctionNaming", "LongMethod")
internal fun KeyPackagesContent(
    state: KeyPackagesState,
    packages: List<AccountKeyPackageFfi>,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onRepublish: () -> Unit,
    onPublishNew: () -> Unit,
    onDelete: (AccountKeyPackageFfi) -> Unit,
) {
    val published = packages.relayBacked()
    val retained = packages.filter { it.local && !it.relay }
    SettingsScaffold(
        title = stringResource(R.string.key_packages),
        onBack = onBack,
        modifier = Modifier.testTag(KEY_PACKAGES_CONTENT_TAG),
        topBarActions = {
            TextButton(onClick = onRefresh, enabled = state.actionsEnabled) { Text(stringResource(R.string.refresh)) }
        },
    ) {
        SettingsList {
            item { SettingsSection(stringResource(R.string.publishing)) }
            item { PublishingActions(state, onRepublish, onPublishNew) }
            if (state.showLoadingIndicator) {
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            stringResource(
                                R.string.developer_work_status,
                                stringResource(R.string.developer_refresh_packages),
                                stringResource(R.string.developer_work_running),
                            ),
                            modifier =
                                Modifier
                                    .testTag("key_packages.loading")
                                    .semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
            }
            item { SettingsSection(stringResource(R.string.published)) }
            if (KeyPackagesSection.Empty in state.sections) {
                item {
                    SettingsExplainer(
                        stringResource(
                            if (retained.isEmpty()) {
                                R.string.developer_not_published
                            } else {
                                R.string.retained_key_packages_not_published_help
                            },
                        ),
                    )
                }
            }
            itemsIndexed(published, key = { index, kp -> "published-${kp.eventIdHex}:$index" }) { _, kp ->
                PublishedKeyPackage(kp, state.packageActionsEnabled, onDelete = { onDelete(kp) })
            }
            item { SettingsSection(stringResource(R.string.developer_retained)) }
            val packagesResolved =
                KeyPackagesSection.Empty in state.sections ||
                    KeyPackagesSection.PackageList in state.sections
            if (retained.isEmpty() && !state.showLoadingIndicator && packagesResolved) {
                item { SettingsExplainer(stringResource(R.string.developer_no_retained)) }
            }
            itemsIndexed(retained, key = { index, kp -> "local-${kp.keyPackageId}:$index" }) { _, kp ->
                RetainedKeyPackage(kp)
            }
            item { SettingsExplainer(stringResource(R.string.developer_retained_help)) }
        }
    }
}

/** Republish preserves the native material; the full-width tonal action asks production to rotate it. */
@Composable
@Suppress("FunctionNaming")
private fun PublishingActions(
    state: KeyPackagesState,
    onRepublish: () -> Unit,
    onPublishNew: () -> Unit,
) {
    SettingsGroup {
        row("republish") { context ->
            SettingsGroupPanel(context) {
                TextButton(onClick = onRepublish, enabled = state.actionsEnabled, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.republish))
                }
            }
        }
    }
    SettingsExplainer(stringResource(R.string.developer_republish_help))
    WhiteNoiseFilledTonalButton(
        onClick = onPublishNew,
        enabled = state.actionsEnabled,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin, vertical = WhiteNoiseSpacing.Related)
                .testTag("key_packages.publish"),
    ) { Text(stringResource(R.string.developer_publish_new)) }
    SettingsExplainer(stringResource(R.string.developer_rotate_help))
}
