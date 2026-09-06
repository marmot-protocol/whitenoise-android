package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Publish
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.AccountKeyPackageFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.SettingsGroup
import dev.ipf.whitenoise.android.ui.common.sectionPanelColor
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorder
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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

internal fun AccountKeyPackageFfi.isRelayDeletionTarget(): Boolean =
    relay &&
        eventIdHex.length == NOSTR_EVENT_ID_HEX_LENGTH &&
        eventIdHex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

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
        AlertDialog(
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
                Button(onClick = {
                    val target = pendingDelete?.takeIf { it.isRelayDeletionTarget() } ?: return@Button
                    val targetAccount = accountRef ?: return@Button
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
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
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
    val hasRetainedLocalMaterial = packages.any { it.local && !it.relay }
    val emptyTitleRes = if (hasRetainedLocalMaterial) R.string.not_published else R.string.no_key_packages_found
    val emptyHelpRes =
        if (hasRetainedLocalMaterial) {
            R.string.retained_key_packages_not_published_help
        } else {
            R.string.no_key_packages_found_help
        }

    Scaffold(
        modifier = Modifier.testTag(KEY_PACKAGES_CONTENT_TAG),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.key_packages)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, enabled = state.actionsEnabled) {
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
                    KeyPackagesSection.Publishing -> {
                        item {
                            SettingsGroup(title = stringResource(R.string.publishing), icon = Icons.Filled.Publish) {
                                item {
                                    Column(
                                        Modifier.fillMaxWidth().padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        PublishingActions(state = state, onRepublish = onRepublish, onPublishNew = onPublishNew)
                                    }
                                }
                            }
                        }
                    }

                    KeyPackagesSection.Published -> {
                        item {
                            // Match the SettingsGroup accent-label treatment so this
                            // heading reads like the other group labels.
                            Row(
                                modifier = Modifier.padding(start = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    stringResource(R.string.published),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                if (state.showLoadingIndicator) {
                                    LoadingIndicator(modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }

                    KeyPackagesSection.Empty -> {
                        item {
                            SettingsGroup(title = stringResource(emptyTitleRes)) {
                                item {
                                    Text(
                                        stringResource(emptyHelpRes),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(16.dp),
                                    )
                                }
                            }
                        }
                    }

                    KeyPackagesSection.PackageList -> {
                        itemsIndexed(
                            packages.relayBacked(),
                            key = { index, kp -> "${kp.eventIdHex}:$index" },
                        ) { _, kp ->
                            KeyPackageCard(
                                kp = kp,
                                actionsEnabled = state.packageActionsEnabled,
                                onDelete = { onDelete(kp) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PublishingActions(
    state: KeyPackagesState,
    onRepublish: () -> Unit,
    onPublishNew: () -> Unit,
) {
    Text(
        stringResource(R.string.key_package_publishing_help),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedButton(
            onClick = onRepublish,
            enabled = state.actionsEnabled,
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.republish))
        }
        Button(
            onClick = onPublishNew,
            enabled = state.actionsEnabled,
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.publish_new))
        }
    }
}

@Composable
private fun KeyPackageCard(
    kp: AccountKeyPackageFfi,
    actionsEnabled: Boolean,
    onDelete: () -> Unit,
) {
    val localLabel = stringResource(R.string.local)
    val relayLabel = stringResource(R.string.relay)
    val unknownLabel = stringResource(R.string.unknown)
    val clipboard = LocalClipboardManager.current
    val copyKeyPackageLabel = stringResource(R.string.copy)
    // Each package renders as its own fully-rounded segment, matching the
    // grouped-list language of the rest of Settings.
    Surface(
        modifier = Modifier.fillMaxWidth().amoledSurfaceBorder(RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        color = sectionPanelColor(),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    // Tap the shortened key-package id header to copy the full value.
                    Text(
                        IdentityFormatter.short(kp.keyPackageId),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        modifier =
                            Modifier.clickable(
                                onClickLabel = copyKeyPackageLabel,
                                role = Role.Button,
                            ) {
                                clipboard.setText(AnnotatedString(kp.keyPackageId))
                            },
                    )
                    Text(
                        formatPublishedAt(kp.publishedAt, stringResource(R.string.unknown_publish_time), stringResource(R.string.published_at)),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (kp.isRelayDeletionTarget()) {
                    IconButton(onClick = onDelete, enabled = actionsEnabled) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete_key_package))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                keyPackageSourceLabels(kp, localLabel, relayLabel, unknownLabel).forEach { label ->
                    AssistChip(onClick = {}, label = { Text(label, style = MaterialTheme.typography.labelSmall) })
                }
            }
            DiagnosticRow(
                stringResource(R.string.event),
                IdentityFormatter.short(kp.eventIdHex),
                copyValue = kp.eventIdHex,
            )
            DiagnosticRow(
                stringResource(R.string.ref),
                IdentityFormatter.short(kp.keyPackageRefHex),
                copyValue = kp.keyPackageRefHex,
            )
            DiagnosticRow(stringResource(R.string.size), stringResource(R.string.bytes_count, kp.keyPackageBytes.toLong()))
            if (kp.sourceRelays.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.source_relays), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    kp.sourceRelays.forEach { relay ->
                        Text(relay, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private fun keyPackageSourceLabels(
    kp: AccountKeyPackageFfi,
    localLabel: String,
    relayLabel: String,
    unknownLabel: String,
): List<String> {
    val out = mutableListOf<String>()
    if (kp.local) out += localLabel
    if (kp.relay) out += relayLabel
    if (out.isEmpty()) out += unknownLabel
    return out
}

private val publishedAtFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault()).withZone(ZoneId.systemDefault())

private fun formatPublishedAt(
    unixSeconds: ULong,
    unknown: String,
    format: String,
): String {
    if (unixSeconds == 0uL) return unknown
    // ULong > Long.MAX_VALUE wraps to a negative epoch; Instant then rejects
    // anything below Instant.MIN. Garbage from a malicious relay shouldn't
    // crash the KeyPackage screen — fall back to "unknown" instead.
    if (unixSeconds > Long.MAX_VALUE.toULong()) return unknown
    val instant = runCatching { Instant.ofEpochSecond(unixSeconds.toLong()) }.getOrNull() ?: return unknown
    return String.format(format, publishedAtFormatter.format(instant))
}
