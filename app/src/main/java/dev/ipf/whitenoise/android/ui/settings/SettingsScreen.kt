@file:Suppress("TooManyFunctions")

package dev.ipf.whitenoise.android.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.KeyboardVoice
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.booleanResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.SupportContact
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.account.AccountSelectorSheet
import dev.ipf.whitenoise.android.ui.account.SettingsAccountHeader
import dev.ipf.whitenoise.android.ui.common.LocalSettingsRowsInsideSectionCard
import dev.ipf.whitenoise.android.ui.common.SettingsGroup
import dev.ipf.whitenoise.android.ui.common.SettingsGroupScope
import dev.ipf.whitenoise.android.ui.navigation.SettingsDetail
import dev.ipf.whitenoise.android.ui.profile.AddIdentitySheet
import dev.ipf.whitenoise.android.ui.profile.ProfileEditScreen
import dev.ipf.whitenoise.android.ui.profile.ProfileQrSheet
import dev.ipf.whitenoise.android.ui.theme.Dimens
import dev.ipf.whitenoise.android.ui.theme.PillShape
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorder
import dev.ipf.whitenoise.android.updates.AppUpdateInfo
import kotlinx.coroutines.flow.distinctUntilChanged
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
    AccountAndKeys,
    Relays,
    KeyPackages,
    Appearance,
    ChatFolders,
    DataAndStorage,
    Notifications,
    TextToSpeech,
    Dictation,
    DevicePrivacy,
    AiAgents,
    Help,
}

/**
 * Saveable position of the Settings home list, anchored by stable section key
 * so optional sections can change without restoring an unrelated viewport.
 */
internal data class SettingsHomeViewport(
    val section: SettingsHomeSection?,
    val fallbackIndex: Int,
    val scrollOffset: Int,
) {
    /** Resolves the saved key first and uses a clamped index only as fallback. */
    fun resolveIndex(sections: List<SettingsHomeSection>): Int {
        if (sections.isEmpty()) return 0
        val keyedIndex = section?.let(sections::indexOf)?.takeIf { it >= 0 }
        return keyedIndex ?: fallbackIndex.coerceIn(0, sections.lastIndex)
    }

    companion object {
        val Top = SettingsHomeViewport(SettingsHomeSection.Account, fallbackIndex = 0, scrollOffset = 0)

        /** Saver used only for the lifecycle-scoped Settings visit. */
        val Saver: Saver<SettingsHomeViewport, Any> =
            listSaver(
                save = { listOf(it.section?.name.orEmpty(), it.fallbackIndex, it.scrollOffset) },
                restore = { saved ->
                    SettingsHomeViewport(
                        section =
                            saved[0]
                                .toString()
                                .takeIf(String::isNotEmpty)
                                ?.let { name -> runCatching { SettingsHomeSection.valueOf(name) }.getOrNull() },
                        fallbackIndex = saved[1] as Int,
                        scrollOffset = saved[2] as Int,
                    )
                },
            )
    }
}

/** Shell-level lifecycle events that either retain or retire a Settings visit. */
internal enum class SettingsHomeViewportEvent {
    OpenDiagnostics,
    OpenNewSettingsVisit,
    ExitSettings,
    OpenConversation,
    ChangeAccount,
}

/** Applies the Settings-visit ownership policy to a captured home viewport. */
internal fun reduceSettingsHomeViewport(
    current: SettingsHomeViewport,
    event: SettingsHomeViewportEvent,
): SettingsHomeViewport =
    when (event) {
        SettingsHomeViewportEvent.OpenDiagnostics -> current
        SettingsHomeViewportEvent.OpenNewSettingsVisit,
        SettingsHomeViewportEvent.ExitSettings,
        SettingsHomeViewportEvent.OpenConversation,
        SettingsHomeViewportEvent.ChangeAccount,
        -> SettingsHomeViewport.Top
    }

@Stable
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
                SettingsHomeRow.AccountAndKeys,
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
                SettingsHomeRow.Dictation,
                SettingsHomeRow.DevicePrivacy,
                SettingsHomeRow.AiAgents,
                SettingsHomeRow.Help,
            ),
        showAccountHeader = hasActiveAccount,
    )

// Parent of a settings detail for back navigation; null means the Settings
// home. Kept pure so the back-stack shape (Developer → About → Help → home,
// ChatBubbleColors → Appearance) is unit-testable without Compose.
internal fun settingsDetailParent(detail: SettingsDetail): SettingsDetail? =
    when (detail) {
        SettingsDetail.ActionColor,
        SettingsDetail.ChatBubbleColors,
        -> SettingsDetail.Appearance
        SettingsDetail.About -> SettingsDetail.Help
        SettingsDetail.Developer -> SettingsDetail.About
        else -> null
    }

@Composable
private fun settingsBackHandler(
    detail: SettingsDetail?,
    onBackToChats: () -> Unit,
    onDetailChange: (SettingsDetail?) -> Unit,
) {
    BackHandler {
        if (detail == null) {
            onBackToChats()
        } else {
            onDetailChange(settingsDetailParent(detail))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    appState: WhiteNoiseAppState,
    onBackToChats: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenSupportChat: (ChatListItem) -> Unit,
    detail: SettingsDetail?,
    onDetailChange: (SettingsDetail?) -> Unit,
    homeViewport: SettingsHomeViewport,
    onHomeViewportChange: (SettingsHomeViewport) -> Unit,
) {
    // Issue #121: the prior shape only handled back from a detail
    // subscreen; when on the Settings home (detail == null) the system
    // back fell through to the Activity and exited the app. Always
    // claim back here — pop the detail when on a subscreen, otherwise
    // hand control to the chats list (mirroring the top-bar back arrow).
    settingsBackHandler(detail, onBackToChats, onDetailChange)

    when (detail) {
        SettingsDetail.Appearance ->
            AppearanceScreen(
                appState = appState,
                onBack = { onDetailChange(null) },
                onOpenActionColor = { onDetailChange(SettingsDetail.ActionColor) },
                onOpenChatBubbleColors = { onDetailChange(SettingsDetail.ChatBubbleColors) },
            )
        SettingsDetail.ActionColor ->
            ActionColorScreen(appState, onBack = { onDetailChange(SettingsDetail.Appearance) })
        SettingsDetail.ChatBubbleColors ->
            ChatBubbleColorsScreen(appState, onBack = { onDetailChange(SettingsDetail.Appearance) })
        SettingsDetail.Data -> AutoDownloadDataScreen(appState, onBack = { onDetailChange(null) })
        SettingsDetail.Profile -> ProfileEditScreen(appState, onBack = { onDetailChange(null) })
        SettingsDetail.AccountKeys -> AccountKeysScreen(appState, onBack = { onDetailChange(null) })
        SettingsDetail.Relays -> RelaysScreen(appState, onBack = { onDetailChange(null) })
        SettingsDetail.KeyPackages -> KeyPackagesScreen(appState, onBack = { onDetailChange(null) })
        SettingsDetail.Notifications -> NotificationsScreen(appState, onBack = { onDetailChange(null) })
        SettingsDetail.DevicePrivacy ->
            DevicePrivacyScreen(
                appState = appState,
                onBack = { onDetailChange(null) },
            )
        SettingsDetail.AiAgents -> AiAgentsScreen(appState, onBack = { onDetailChange(null) })
        SettingsDetail.Donate -> DonateScreen(onBack = { onDetailChange(null) })
        SettingsDetail.TextToSpeech -> TextToSpeechScreen(appState, onBack = { onDetailChange(null) })
        SettingsDetail.Dictation -> DictationSettingsScreen(appState, onBack = { onDetailChange(null) })
        SettingsDetail.ChatFolders -> ChatFoldersScreen(appState, onBack = { onDetailChange(null) })
        SettingsDetail.Help ->
            HelpScreen(
                onBack = { onDetailChange(null) },
                onOpenAbout = { onDetailChange(SettingsDetail.About) },
            )
        SettingsDetail.About ->
            AboutScreen(
                appState = appState,
                versionName = BuildConfig.VERSION_NAME,
                mdkShortSha = BuildConfig.MDK_SHORT_SHA,
                onBack = { onDetailChange(SettingsDetail.Help) },
                onOpenDeveloper = { onDetailChange(SettingsDetail.Developer) },
            )
        SettingsDetail.Developer ->
            DeveloperScreen(
                appState = appState,
                onBack = { onDetailChange(SettingsDetail.About) },
                onOpenDiagnostics = onOpenDiagnostics,
            )
        null ->
            SettingsHomeScreen(
                appState = appState,
                onBackToChats = onBackToChats,
                onOpenDetail = { onDetailChange(it) },
                onOpenSupportChat = onOpenSupportChat,
                viewport = homeViewport,
                onViewportChange = onHomeViewportChange,
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
    onOpenSupportChat: (ChatListItem) -> Unit,
    viewport: SettingsHomeViewport,
    onViewportChange: (SettingsHomeViewport) -> Unit,
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

    // Chat with support: reopen the existing direct chat with the canonical
    // support identity. Matches the prior behavior: reopen the existing DM if
    // there is one, otherwise present the support profile — whose Message
    // action runs the ordinary start-chat flow (KeyPackage handling, typed
    // failure, invitation) instead of a bespoke path.
    fun startSupportChat() {
        val existing = appState.existingDirectChat(SupportContact.NPUB)
        if (existing != null) {
            onOpenSupportChat(existing)
        } else {
            appState.presentProfile(SupportContact.NPUB)
        }
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
                    subtitle = appState.npubForDisplay(account.accountIdHex),
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
        onChatWithSupport = ::startSupportChat,
        viewport = viewport,
        onViewportChange = onViewportChange,
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
    onChatWithSupport: () -> Unit = {},
    viewport: SettingsHomeViewport = SettingsHomeViewport.Top,
    onViewportChange: (SettingsHomeViewport) -> Unit = {},
) {
    val currentOnViewportChange by rememberUpdatedState(onViewportChange)
    val listState =
        rememberLazyListState(
            initialFirstVisibleItemIndex = viewport.resolveIndex(state.sections),
            initialFirstVisibleItemScrollOffset = viewport.scrollOffset.coerceAtLeast(0),
        )
    LaunchedEffect(listState) {
        snapshotFlow {
            val index = listState.firstVisibleItemIndex
            val section =
                listState.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.index == index }
                    ?.key
                    ?.toString()
                    ?.let { key -> runCatching { SettingsHomeSection.valueOf(key) }.getOrNull() }
            SettingsHomeViewport(
                section = section,
                fallbackIndex = index,
                scrollOffset = listState.firstVisibleItemScrollOffset,
            )
        }.distinctUntilChanged().collect(currentOnViewportChange)
    }
    Scaffold(
        modifier = Modifier.testTag(SETTINGS_HOME_CONTENT_TAG),
        topBar = { SettingsTopBar(onBackToChats = onBackToChats) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = Dimens.spaceLg),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceLg),
        ) {
            state.sections.forEach { section ->
                item(key = section.name) {
                    when (section) {
                        SettingsHomeSection.Account -> {
                            SettingsGroup(title = stringResource(R.string.account), icon = Icons.Filled.Person) {
                                if (state.showAccountHeader && account != null) {
                                    item {
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
                                }
                                settingsHomeRows(rows = state.accountRows, onOpenDetail = onOpenDetail)
                            }
                        }

                        SettingsHomeSection.AppPreferences -> {
                            SettingsGroup(title = stringResource(R.string.app_preferences), icon = Icons.Filled.Tune) {
                                settingsHomeRows(rows = state.preferenceRows, onOpenDetail = onOpenDetail)
                            }
                        }

                        SettingsHomeSection.Support -> {
                            SettingsGroup(title = stringResource(R.string.support), icon = Icons.Filled.SupportAgent) {
                                item {
                                    SettingsRow(
                                        title = stringResource(R.string.chat_with_support),
                                        subtitle = stringResource(R.string.chat_with_support_subtitle),
                                        icon = Icons.AutoMirrored.Filled.Chat,
                                        onClick = onChatWithSupport,
                                    )
                                }
                                item {
                                    SettingsRow(
                                        title = stringResource(R.string.support_the_project),
                                        subtitle = stringResource(R.string.support_the_project_subtitle),
                                        icon = Icons.Filled.Favorite,
                                        onClick = { onOpenDetail(SettingsDetail.Donate) },
                                    )
                                }
                            }
                        }

                        SettingsHomeSection.AppUpdates -> {
                            SettingsGroup(title = stringResource(R.string.app_updates), icon = Icons.Filled.Update) {
                                item { AppUpdateSettingsRow(info = appUpdateInfo, onClick = onAppUpdateAction) }
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

@Suppress("CyclomaticComplexMethod")
private fun SettingsGroupScope.settingsHomeRows(
    rows: List<SettingsHomeRow>,
    onOpenDetail: (SettingsDetail) -> Unit,
) {
    rows.forEach { row ->
        val detail =
            when (row) {
                SettingsHomeRow.Profile -> SettingsDetail.Profile
                SettingsHomeRow.AccountAndKeys -> SettingsDetail.AccountKeys
                SettingsHomeRow.Relays -> SettingsDetail.Relays
                SettingsHomeRow.KeyPackages -> SettingsDetail.KeyPackages
                SettingsHomeRow.Appearance -> SettingsDetail.Appearance
                SettingsHomeRow.ChatFolders -> SettingsDetail.ChatFolders
                SettingsHomeRow.DataAndStorage -> SettingsDetail.Data
                SettingsHomeRow.Notifications -> SettingsDetail.Notifications
                SettingsHomeRow.TextToSpeech -> SettingsDetail.TextToSpeech
                SettingsHomeRow.Dictation -> SettingsDetail.Dictation
                SettingsHomeRow.DevicePrivacy -> SettingsDetail.DevicePrivacy
                SettingsHomeRow.AiAgents -> SettingsDetail.AiAgents
                SettingsHomeRow.Help -> SettingsDetail.Help
            }
        item {
            SettingsRow(
                title =
                    when (row) {
                        SettingsHomeRow.Profile -> stringResource(R.string.profile)
                        SettingsHomeRow.AccountAndKeys -> stringResource(R.string.account_and_keys)
                        SettingsHomeRow.Relays -> stringResource(R.string.relays)
                        SettingsHomeRow.KeyPackages -> stringResource(R.string.key_packages)
                        SettingsHomeRow.Appearance -> stringResource(R.string.appearance)
                        SettingsHomeRow.ChatFolders -> stringResource(R.string.chat_folders_title)
                        SettingsHomeRow.DataAndStorage -> stringResource(R.string.data_and_storage)
                        SettingsHomeRow.Notifications -> stringResource(R.string.notifications)
                        SettingsHomeRow.TextToSpeech -> stringResource(R.string.tts_settings_title)
                        SettingsHomeRow.Dictation -> stringResource(R.string.dictation_settings_title)
                        SettingsHomeRow.DevicePrivacy -> stringResource(R.string.device_privacy)
                        SettingsHomeRow.AiAgents -> stringResource(R.string.ai_agents)
                        SettingsHomeRow.Help -> stringResource(R.string.help)
                    },
                subtitle =
                    when (row) {
                        SettingsHomeRow.Profile -> stringResource(R.string.profile_settings_subtitle)
                        SettingsHomeRow.AccountAndKeys -> stringResource(R.string.account_keys_settings_subtitle)
                        SettingsHomeRow.Relays -> stringResource(R.string.relays_settings_subtitle)
                        SettingsHomeRow.KeyPackages -> stringResource(R.string.key_packages_settings_subtitle)
                        SettingsHomeRow.Appearance -> stringResource(R.string.appearance_settings_subtitle)
                        SettingsHomeRow.ChatFolders -> stringResource(R.string.chat_folders_settings_subtitle)
                        SettingsHomeRow.DataAndStorage -> stringResource(R.string.data_and_storage_settings_subtitle)
                        SettingsHomeRow.Notifications -> stringResource(R.string.notifications_settings_subtitle)
                        SettingsHomeRow.TextToSpeech -> stringResource(R.string.tts_settings_subtitle)
                        SettingsHomeRow.Dictation -> stringResource(R.string.dictation_settings_subtitle)
                        SettingsHomeRow.DevicePrivacy -> stringResource(R.string.device_privacy_settings_subtitle)
                        SettingsHomeRow.AiAgents -> stringResource(R.string.ai_agents_settings_subtitle)
                        SettingsHomeRow.Help -> stringResource(R.string.help_settings_subtitle)
                    },
                icon =
                    when (row) {
                        SettingsHomeRow.Profile -> Icons.Filled.AccountCircle
                        SettingsHomeRow.AccountAndKeys -> Icons.Filled.Key
                        SettingsHomeRow.Relays -> Icons.Filled.Hub
                        SettingsHomeRow.KeyPackages -> Icons.Filled.Inventory2
                        SettingsHomeRow.Appearance -> Icons.Filled.Palette
                        SettingsHomeRow.ChatFolders -> Icons.Filled.Folder
                        SettingsHomeRow.DataAndStorage -> Icons.Filled.Storage
                        SettingsHomeRow.Notifications -> Icons.Filled.Notifications
                        SettingsHomeRow.TextToSpeech -> Icons.Filled.RecordVoiceOver
                        SettingsHomeRow.Dictation -> Icons.Filled.KeyboardVoice
                        SettingsHomeRow.DevicePrivacy -> Icons.Filled.Shield
                        SettingsHomeRow.AiAgents -> Icons.Filled.SmartToy
                        SettingsHomeRow.Help -> Icons.Filled.Help
                    },
            ) { onOpenDetail(detail) }
        }
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
                .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
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

/**
 * Selectable row with supporting copy and optional merged accessibility text.
 * Disabled choices remain visible so the supporting line can explain why.
 */
@Composable
internal fun SelectableSettingsRowWithSubtitle(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean = true,
    accessibilityLabel: String? = null,
    onClick: () -> Unit,
) {
    val rowModifier =
        Modifier
            .settingsRowAmoledSurfaceBorder()
            .selectable(
                selected = selected,
                enabled = enabled,
                onClick = onClick,
                role = Role.RadioButton,
            )
    ListItem(
        modifier =
            if (accessibilityLabel == null) {
                rowModifier
            } else {
                rowModifier.semantics(mergeDescendants = true) {
                    contentDescription = accessibilityLabel
                }
            },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = {
            Text(
                title,
                color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        supportingContent = {
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailingContent = {
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = stringResource(R.string.selected),
                    tint =
                        if (enabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
        },
    )
}

/** Renders a settings toggle with optional in-row padding for segmented lists. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SettingsSwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    enabled: Boolean = true,
    busy: Boolean = false,
    switchModifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    contentSpacing: Dp = 0.dp,
    icon: ImageVector? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .settingsRowAmoledSurfaceBorder()
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(contentSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.padding(end = 16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (busy) {
            LoadingIndicator(modifier = Modifier.size(24.dp))
        } else {
            Switch(
                modifier = switchModifier,
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
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    ListItem(
        modifier =
            modifier
                .settingsRowAmoledSurfaceBorder()
                .clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = icon?.let { { Icon(it, contentDescription = null) } },
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
            info.lastAttemptErrorReport != null -> stringResource(R.string.app_update_settings_check_failed)
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
        icon = Icons.Filled.Update,
        onClick = onClick,
    )
}
