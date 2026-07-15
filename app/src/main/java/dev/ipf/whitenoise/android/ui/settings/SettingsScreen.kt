package dev.ipf.whitenoise.android.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.booleanResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.account.AccountSelectorSheet
import dev.ipf.whitenoise.android.ui.account.SettingsAccountHeader
import dev.ipf.whitenoise.android.ui.common.LocalSettingsRowsInsideSectionCard
import dev.ipf.whitenoise.android.ui.common.SectionCard
import dev.ipf.whitenoise.android.ui.common.sectionPanelColor
import dev.ipf.whitenoise.android.ui.navigation.SettingsDetail
import dev.ipf.whitenoise.android.ui.profile.AddIdentitySheet
import dev.ipf.whitenoise.android.ui.profile.ProfileEditScreen
import dev.ipf.whitenoise.android.ui.profile.ProfileQrSheet
import dev.ipf.whitenoise.android.ui.theme.Dimens
import dev.ipf.whitenoise.android.ui.theme.PillShape
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorder
import dev.ipf.whitenoise.android.updates.AppUpdateInfo
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    appState: WhiteNoiseAppState,
    onBackToChats: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    detail: SettingsDetail?,
    onDetailChange: (SettingsDetail?) -> Unit,
) {
    // Issue #121: the prior shape only handled back from a detail
    // subscreen; when on the Settings home (detail == null) the system
    // back fell through to the Activity and exited the app. Always
    // claim back here — pop the detail when on a subscreen, otherwise
    // hand control to the chats list (mirroring the top-bar back arrow).
    BackHandler {
        when {
            // Font size is a level-2 subscreen reached from Appearance, so
            // back returns there rather than jumping to the Settings home.
            detail == SettingsDetail.FontSize -> onDetailChange(SettingsDetail.Appearance)
            detail != null -> onDetailChange(null)
            else -> onBackToChats()
        }
    }

    when (detail) {
        SettingsDetail.Appearance ->
            AppearanceScreen(
                appState = appState,
                onBack = { onDetailChange(null) },
                onOpenFontSize = { onDetailChange(SettingsDetail.FontSize) },
            )
        SettingsDetail.FontSize -> FontSizeScreen(appState, onBack = { onDetailChange(SettingsDetail.Appearance) })
        SettingsDetail.Data -> AutoDownloadDataScreen(appState, onBack = { onDetailChange(null) })
        SettingsDetail.Profile -> ProfileEditScreen(appState, onBack = { onDetailChange(null) })
        SettingsDetail.Identity -> IdentityScreen(appState, onBack = { onDetailChange(null) })
        SettingsDetail.Relays -> RelaysScreen(appState, onBack = { onDetailChange(null) })
        SettingsDetail.KeyPackages -> KeyPackagesScreen(appState, onBack = { onDetailChange(null) })
        SettingsDetail.Notifications -> NotificationsScreen(appState, onBack = { onDetailChange(null) })
        SettingsDetail.SecurityPrivacy ->
            SecurityPrivacyScreen(
                appState = appState,
                onBack = { onDetailChange(null) },
                onOpenDiagnostics = onOpenDiagnostics,
            )
        SettingsDetail.Donate -> DonateScreen(appState, onBack = { onDetailChange(null) })
        null ->
            SettingsHomeScreen(
                appState = appState,
                onBackToChats = onBackToChats,
                onOpenDetail = { onDetailChange(it) },
            )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTopBar(onBackToChats: () -> Unit) {
    TopAppBar(
        title = { Text(stringResource(R.string.settings)) },
        navigationIcon = {
            IconButton(onClick = onBackToChats) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back_to_chats))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsHomeScreen(
    appState: WhiteNoiseAppState,
    onBackToChats: () -> Unit,
    onOpenDetail: (SettingsDetail) -> Unit,
) {
    var qrAccountId by remember { mutableStateOf<String?>(null) }
    var showAccountSelector by remember { mutableStateOf(false) }
    var showAddIdentity by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(appState.accounts.size) {
        if (showAddIdentity) showAddIdentity = false
    }

    Scaffold(
        topBar = {
            SettingsTopBar(onBackToChats = onBackToChats)
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = Dimens.spaceLg), verticalArrangement = Arrangement.spacedBy(Dimens.spaceLg)) {
            item {
                SectionCard(title = stringResource(R.string.account)) {
                    appState.activeAccount?.let { account ->
                        SettingsAccountHeader(
                            title = appState.displayName(account.accountIdHex),
                            subtitle = appState.shortNpub(account.accountIdHex),
                            seed = account.accountIdHex,
                            pictureUrl = appState.avatarUrl(account.accountIdHex),
                            onOpenAccountSelector = { showAccountSelector = true },
                            onOpenQr = { qrAccountId = account.accountIdHex },
                            onEditProfilePicture = { onOpenDetail(SettingsDetail.Profile) },
                        )
                    }
                    SettingsRow(stringResource(R.string.profile), stringResource(R.string.profile_settings_subtitle)) { onOpenDetail(SettingsDetail.Profile) }
                    SettingsRow(
                        stringResource(R.string.identity_and_keys),
                        stringResource(R.string.identity_settings_subtitle),
                    ) { onOpenDetail(SettingsDetail.Identity) }
                    SettingsRow(stringResource(R.string.relays), stringResource(R.string.relays_settings_subtitle)) { onOpenDetail(SettingsDetail.Relays) }
                    SettingsRow(
                        stringResource(R.string.key_packages),
                        stringResource(R.string.key_packages_settings_subtitle),
                    ) { onOpenDetail(SettingsDetail.KeyPackages) }
                }
            }
            item {
                SectionCard(title = stringResource(R.string.app_preferences)) {
                    SettingsRow(
                        stringResource(R.string.appearance),
                        stringResource(R.string.appearance_settings_subtitle),
                    ) { onOpenDetail(SettingsDetail.Appearance) }
                    SettingsRow(
                        stringResource(R.string.data_and_storage),
                        stringResource(R.string.data_and_storage_settings_subtitle),
                    ) { onOpenDetail(SettingsDetail.Data) }
                    SettingsRow(
                        stringResource(R.string.notifications),
                        stringResource(R.string.notifications_settings_subtitle),
                    ) { onOpenDetail(SettingsDetail.Notifications) }
                    SettingsRow(stringResource(R.string.security_and_privacy), stringResource(R.string.security_privacy_settings_subtitle)) {
                        onOpenDetail(SettingsDetail.SecurityPrivacy)
                    }
                }
            }
            item {
                // Navigation row to the donation page, matching the other
                // Settings sections' row -> detail-screen shape.
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth().amoledSurfaceBorder(RoundedCornerShape(12.dp)),
                    colors = CardDefaults.elevatedCardColors(containerColor = sectionPanelColor()),
                ) {
                    ListItem(
                        modifier = Modifier.clickable { onOpenDetail(SettingsDetail.Donate) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(stringResource(R.string.support_the_project)) },
                        supportingContent = {
                            Text(
                                stringResource(R.string.support_the_project_subtitle),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
            // No in-app update UI on store-managed builds (Google Play): the
            // store owns updates, and off-store update redirects violate policy.
            if (BuildConfig.SELF_UPDATE_ENABLED) {
                item {
                    SectionCard(title = stringResource(R.string.app_updates)) {
                        AppUpdateSettingsRow(
                            info = appState.appUpdateInfo,
                            onClick = {
                                // Await the check before acting, so a first tap in the
                                // unknown state still routes on the fresh result.
                                scope.launch {
                                    if (appState.appUpdateInfo.latestVersion == null) {
                                        appState.refreshAppUpdate(force = true, notifyIfNewer = false)
                                    }
                                    appState.handleAppUpdateAction(context)
                                }
                            },
                        )
                    }
                }
            }
            item {
                // Version footer. Marketing string only — the integer
                // `VERSION_CODE` is intentionally hidden from this
                // surface to keep the line uncluttered; triage that
                // needs the code can read it via `dumpsys package`,
                // logcat, or the Diagnostics screen.
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text =
                            stringResource(
                                R.string.settings_version_label,
                                BuildConfig.VERSION_NAME,
                            ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text =
                            stringResource(
                                R.string.settings_mdk_version_label,
                                BuildConfig.MDK_SHORT_SHA,
                            ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // Release-channel badge: `staging_build` is false in main
                    // resources and overridden to true only in the staging
                    // source set, so dev/production never render it.
                    if (booleanResource(R.bool.staging_build)) {
                        Surface(
                            shape = PillShape,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ) {
                            Text(
                                text = stringResource(R.string.settings_staging_badge),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    qrAccountId?.let { accountId ->
        ProfileQrSheet(
            appState = appState,
            accountIdHex = accountId,
            onDismiss = { qrAccountId = null },
        )
    }
    if (showAccountSelector) {
        AccountSelectorSheet(
            appState = appState,
            onDismiss = { showAccountSelector = false },
            onAddAccount = {
                showAccountSelector = false
                showAddIdentity = true
            },
            onAccountSwitched = onBackToChats,
        )
    }
    if (showAddIdentity) {
        AddIdentitySheet(appState = appState, onDismiss = { showAddIdentity = false })
    }
}

@Composable
internal fun Modifier.settingsRowAmoledSurfaceBorder(shape: Shape = RoundedCornerShape(12.dp)): Modifier =
    if (LocalSettingsRowsInsideSectionCard.current) {
        this
    } else {
        clip(shape).amoledSurfaceBorder(shape)
    }

@Composable
internal fun SelectableSettingsRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        modifier =
            Modifier
                .settingsRowAmoledSurfaceBorder()
                .clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(title) },
        trailingContent = {
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = stringResource(R.string.selected),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
    )
}

// A selectable row that also shows a supporting line (e.g. the approximate
// per-photo size delta) under the title. Mirrors [SelectableSettingsRow] but
// with a subtitle slot.
@Composable
internal fun SelectableSettingsRowWithSubtitle(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        modifier =
            Modifier
                .settingsRowAmoledSurfaceBorder()
                .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(title) },
        supportingContent = {
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailingContent = {
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = stringResource(R.string.selected),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
    )
}

@Composable
internal fun SettingsSwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    enabled: Boolean = true,
    busy: Boolean = false,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .settingsRowAmoledSurfaceBorder(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange,
            )
        }
    }
}

@Composable
internal fun SettingsRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ListItem(
        modifier =
            Modifier
                .settingsRowAmoledSurfaceBorder()
                .clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    )
}

@Composable
private fun AppUpdateSettingsRow(
    info: AppUpdateInfo,
    onClick: () -> Unit,
) {
    val latest = info.latestVersion
    val subtitle =
        when {
            latest == null -> stringResource(R.string.app_update_settings_unknown, info.installedVersion)
            !info.isUpdateAvailable -> stringResource(R.string.app_update_settings_current, info.installedVersion)
            info.releasesBehind != null ->
                stringResource(
                    R.string.app_update_settings_available_with_count,
                    info.installedVersion,
                    latest,
                    info.releasesBehind,
                )
            else -> stringResource(R.string.app_update_settings_available, info.installedVersion, latest)
        }
    SettingsRow(
        title = stringResource(R.string.app_update_settings_title),
        subtitle = subtitle,
        onClick = onClick,
    )
}
