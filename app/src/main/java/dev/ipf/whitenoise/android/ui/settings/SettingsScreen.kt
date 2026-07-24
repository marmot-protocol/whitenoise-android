@file:Suppress("TooManyFunctions")

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
import androidx.compose.ui.platform.testTag
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

internal enum class SettingsHomeSection {
    Account,
    AppPreferences,
    Support,
    AppUpdates,
    BuildInfo,
}

internal enum class SettingsHomeRow {
    Profile,
    IdentityAndKeys,
    Relays,
    KeyPackages,
    Appearance,
    ChatFolders,
    DataAndStorage,
    Notifications,
    TextToSpeech,
    SecurityAndPrivacy,
}

internal data class SettingsHomeState(
    val sections: List<SettingsHomeSection>,
    val accountRows: List<SettingsHomeRow>,
    val preferenceRows: List<SettingsHomeRow>,
    val showAccountHeader: Boolean,
)

internal fun settingsHomeState(
    hasActiveAccount: Boolean,
    selfUpdateEnabled: Boolean,
): SettingsHomeState =
    SettingsHomeState(
        sections =
            buildList {
                add(SettingsHomeSection.Account)
                add(SettingsHomeSection.AppPreferences)
                add(SettingsHomeSection.Support)
                // Store-managed builds own updates; off-store redirects violate policy.
                if (selfUpdateEnabled) add(SettingsHomeSection.AppUpdates)
                add(SettingsHomeSection.BuildInfo)
            },
        accountRows =
            listOf(
                SettingsHomeRow.Profile,
                SettingsHomeRow.IdentityAndKeys,
                SettingsHomeRow.Relays,
                SettingsHomeRow.KeyPackages,
            ),
        preferenceRows =
            listOf(
                SettingsHomeRow.Appearance,
                SettingsHomeRow.ChatFolders,
                SettingsHomeRow.DataAndStorage,
                SettingsHomeRow.Notifications,
                SettingsHomeRow.TextToSpeech,
                SettingsHomeRow.SecurityAndPrivacy,
            ),
        showAccountHeader = hasActiveAccount,
    )

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
            detail == SettingsDetail.FontSize || detail == SettingsDetail.ChatBubbleColors ->
                onDetailChange(SettingsDetail.Appearance)
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
                onOpenChatBubbleColors = { onDetailChange(SettingsDetail.ChatBubbleColors) },
            )
        SettingsDetail.ChatBubbleColors ->
            ChatBubbleColorsScreen(appState, onBack = { onDetailChange(SettingsDetail.Appearance) })
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
        SettingsDetail.Donate -> DonateScreen(onBack = { onDetailChange(null) })
        SettingsDetail.TextToSpeech -> TextToSpeechScreen(appState, onBack = { onDetailChange(null) })
        SettingsDetail.ChatFolders -> ChatFoldersScreen(appState, onBack = { onDetailChange(null) })
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

internal const val SETTINGS_HOME_CONTENT_TAG = "settings-home-content"

internal data class SettingsHomeAccount(
    val title: String,
    val subtitle: String,
    val seed: String,
    val pictureUrl: String?,
)

@Composable
@Suppress("FunctionNaming", "LongMethod")
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
    val activeAccount = appState.activeAccount

    LaunchedEffect(appState.accounts.size) {
        if (showAddIdentity) showAddIdentity = false
    }

    SettingsHomeContent(
        state =
            settingsHomeState(
                hasActiveAccount = activeAccount != null,
                selfUpdateEnabled = BuildConfig.SELF_UPDATE_ENABLED,
            ),
        account =
            activeAccount?.let { account ->
                SettingsHomeAccount(
                    title = appState.displayName(account.accountIdHex),
                    subtitle = appState.shortNpub(account.accountIdHex),
                    seed = account.accountIdHex,
                    pictureUrl = appState.avatarUrl(account.accountIdHex),
                )
            },
        appUpdateInfo = appState.appUpdateInfo,
        versionName = BuildConfig.VERSION_NAME,
        mdkShortSha = BuildConfig.MDK_SHORT_SHA,
        staging = booleanResource(R.bool.staging_build),
        onBackToChats = onBackToChats,
        onOpenAccountSelector = { showAccountSelector = true },
        onOpenQr = { qrAccountId = activeAccount?.accountIdHex },
        onOpenDetail = onOpenDetail,
        onAppUpdateAction = {
            scope.launch {
                // Await the check before acting so the first tap uses a fresh result.
                if (appState.appUpdateInfo.latestVersion == null) {
                    appState.refreshAppUpdate(force = true, notifyIfNewer = false)
                }
                appState.handleAppUpdateAction(context)
            }
        },
    )

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("FunctionNaming", "LongMethod")
internal fun SettingsHomeContent(
    state: SettingsHomeState,
    account: SettingsHomeAccount?,
    appUpdateInfo: AppUpdateInfo,
    versionName: String,
    mdkShortSha: String,
    staging: Boolean,
    onBackToChats: () -> Unit,
    onOpenAccountSelector: () -> Unit,
    onOpenQr: () -> Unit,
    onOpenDetail: (SettingsDetail) -> Unit,
    onAppUpdateAction: () -> Unit,
) {
    Scaffold(
        modifier = Modifier.testTag(SETTINGS_HOME_CONTENT_TAG),
        topBar = { SettingsTopBar(onBackToChats = onBackToChats) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = Dimens.spaceLg),
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceLg),
        ) {
            state.sections.forEach { section ->
                item {
                    when (section) {
                        SettingsHomeSection.Account -> {
                            SectionCard(title = stringResource(R.string.account)) {
                                if (state.showAccountHeader && account != null) {
                                    SettingsAccountHeader(
                                        title = account.title,
                                        subtitle = account.subtitle,
                                        seed = account.seed,
                                        pictureUrl = account.pictureUrl,
                                        onOpenAccountSelector = onOpenAccountSelector,
                                        onOpenQr = onOpenQr,
                                        onEditProfilePicture = { onOpenDetail(SettingsDetail.Profile) },
                                    )
                                }
                                SettingsHomeRows(rows = state.accountRows, onOpenDetail = onOpenDetail)
                            }
                        }

                        SettingsHomeSection.AppPreferences -> {
                            SectionCard(title = stringResource(R.string.app_preferences)) {
                                SettingsHomeRows(rows = state.preferenceRows, onOpenDetail = onOpenDetail)
                            }
                        }

                        SettingsHomeSection.Support -> {
                            // Match the other settings sections' row-to-detail navigation shape.
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

                        SettingsHomeSection.AppUpdates -> {
                            SectionCard(title = stringResource(R.string.app_updates)) {
                                AppUpdateSettingsRow(info = appUpdateInfo, onClick = onAppUpdateAction)
                            }
                        }

                        SettingsHomeSection.BuildInfo -> {
                            // Keep the footer uncluttered: version name and MDK revision only.
                            Column(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp, bottom = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_version_label, versionName),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    text = stringResource(R.string.settings_mdk_version_label, mdkShortSha),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                if (staging) {
                                    // Main resources keep this false; only staging overrides it.
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
            }
        }
    }
}

@Composable
@Suppress("FunctionNaming", "CyclomaticComplexMethod")
private fun SettingsHomeRows(
    rows: List<SettingsHomeRow>,
    onOpenDetail: (SettingsDetail) -> Unit,
) {
    rows.forEach { row ->
        val detail =
            when (row) {
                SettingsHomeRow.Profile -> SettingsDetail.Profile
                SettingsHomeRow.IdentityAndKeys -> SettingsDetail.Identity
                SettingsHomeRow.Relays -> SettingsDetail.Relays
                SettingsHomeRow.KeyPackages -> SettingsDetail.KeyPackages
                SettingsHomeRow.Appearance -> SettingsDetail.Appearance
                SettingsHomeRow.ChatFolders -> SettingsDetail.ChatFolders
                SettingsHomeRow.DataAndStorage -> SettingsDetail.Data
                SettingsHomeRow.Notifications -> SettingsDetail.Notifications
                SettingsHomeRow.TextToSpeech -> SettingsDetail.TextToSpeech
                SettingsHomeRow.SecurityAndPrivacy -> SettingsDetail.SecurityPrivacy
            }
        val title =
            when (row) {
                SettingsHomeRow.Profile -> stringResource(R.string.profile)
                SettingsHomeRow.IdentityAndKeys -> stringResource(R.string.identity_and_keys)
                SettingsHomeRow.Relays -> stringResource(R.string.relays)
                SettingsHomeRow.KeyPackages -> stringResource(R.string.key_packages)
                SettingsHomeRow.Appearance -> stringResource(R.string.appearance)
                SettingsHomeRow.ChatFolders -> stringResource(R.string.chat_folders_title)
                SettingsHomeRow.DataAndStorage -> stringResource(R.string.data_and_storage)
                SettingsHomeRow.Notifications -> stringResource(R.string.notifications)
                SettingsHomeRow.TextToSpeech -> stringResource(R.string.tts_settings_title)
                SettingsHomeRow.SecurityAndPrivacy -> stringResource(R.string.security_and_privacy)
            }
        val subtitle =
            when (row) {
                SettingsHomeRow.Profile -> stringResource(R.string.profile_settings_subtitle)
                SettingsHomeRow.IdentityAndKeys -> stringResource(R.string.identity_settings_subtitle)
                SettingsHomeRow.Relays -> stringResource(R.string.relays_settings_subtitle)
                SettingsHomeRow.KeyPackages -> stringResource(R.string.key_packages_settings_subtitle)
                SettingsHomeRow.Appearance -> stringResource(R.string.appearance_settings_subtitle)
                SettingsHomeRow.ChatFolders -> stringResource(R.string.chat_folders_settings_subtitle)
                SettingsHomeRow.DataAndStorage -> stringResource(R.string.data_and_storage_settings_subtitle)
                SettingsHomeRow.Notifications -> stringResource(R.string.notifications_settings_subtitle)
                SettingsHomeRow.TextToSpeech -> stringResource(R.string.tts_settings_subtitle)
                SettingsHomeRow.SecurityAndPrivacy -> stringResource(R.string.security_privacy_settings_subtitle)
            }
        SettingsRow(title = title, subtitle = subtitle) { onOpenDetail(detail) }
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
