@file:Suppress("TooManyFunctions")

package dev.ipf.whitenoise.android.ui.settings

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.account.AccountSelectorSheet
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.common.LocalSettingsRowsInsideSectionCard
import dev.ipf.whitenoise.android.ui.navigation.SettingsDetail
import dev.ipf.whitenoise.android.ui.profile.AddIdentitySheet
import dev.ipf.whitenoise.android.ui.profile.ProfileEditScreen
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorder
import dev.ipf.whitenoise.android.updates.AppUpdateInfo
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Keyed items of the Settings home list, in display order; the viewport is restored by section key. */
internal enum class SettingsHomeSection {
    Profile,
    AppUpdates,
    Hub,
    Support,
    SignOut,
    Version,
}

/**
 * Rows of the Settings home in the prototype's order. Each row carries its label, Material Symbols icon and
 * test-tag suffix; [detail] is the destination it opens, or null for rows that run an action instead.
 */
internal enum class SettingsHomeRow(
    @param:StringRes val titleRes: Int,
    @param:DrawableRes val iconRes: Int,
    val iconTag: String,
    val detail: SettingsDetail?,
) {
    Profile(R.string.profile, R.drawable.ic_settings_account_circle, "profile", SettingsDetail.Profile),
    ProfileKeys(R.string.settings_profile_keys, R.drawable.ic_settings_key, "profile_keys", SettingsDetail.AccountKeys),
    AiAgents(R.string.ai_agents, R.drawable.ic_settings_person_add, "ai_agents", SettingsDetail.AiAgents),
    Notifications(
        R.string.notifications,
        R.drawable.ic_settings_notifications,
        "notifications",
        SettingsDetail.Notifications,
    ),
    ReadAloud(R.string.settings_read_aloud, R.drawable.ic_volume_up, "read_aloud", SettingsDetail.TextToSpeech),
    Dictation(R.string.dictation_settings_title, R.drawable.ic_mic, "dictation", SettingsDetail.Dictation),
    Appearance(R.string.appearance, R.drawable.ic_settings_contrast, "appearance", SettingsDetail.Appearance),
    ChatFolders(R.string.chat_folders_title, R.drawable.ic_folder, "folders", SettingsDetail.ChatFolders),
    PrivacySecurity(
        R.string.settings_privacy_security,
        R.drawable.ic_settings_front_hand,
        "privacy_security",
        SettingsDetail.DevicePrivacy,
    ),
    DataUsage(R.string.settings_data_usage, R.drawable.ic_settings_hard_drive, "data_usage", SettingsDetail.Data),
    Relays(R.string.relays, R.drawable.ic_settings_cell_tower, "relays", SettingsDetail.Relays),
    Help(R.string.help, R.drawable.ic_info, "help", SettingsDetail.Help),
    ChatWithSupport(R.string.chat_with_support, R.drawable.ic_settings_chat_bubble_outline, "support", null),
    Donate(R.string.settings_donate, R.drawable.ic_settings_favorite_border, "donate", SettingsDetail.Donate),
    DeveloperTools(
        R.string.settings_developer_tools,
        R.drawable.ic_settings_handyman,
        "developer_tools",
        SettingsDetail.Developer,
    ),
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
        val Top = SettingsHomeViewport(SettingsHomeSection.Profile, fallbackIndex = 0, scrollOffset = 0)

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
    val hubRows: List<SettingsHomeRow>,
    val supportRows: List<SettingsHomeRow>,
    val showProfileHeader: Boolean,
)

/** Sections and rows of the Settings home; the account-bound sections appear only with an active account. */
internal fun settingsHomeState(
    hasActiveAccount: Boolean,
    selfUpdateEnabled: Boolean,
): SettingsHomeState =
    SettingsHomeState(
        sections =
            buildList {
                if (hasActiveAccount) add(SettingsHomeSection.Profile)
                // Store-managed builds own updates; off-store redirects violate policy.
                if (selfUpdateEnabled) add(SettingsHomeSection.AppUpdates)
                add(SettingsHomeSection.Hub)
                add(SettingsHomeSection.Support)
                if (hasActiveAccount) add(SettingsHomeSection.SignOut)
                add(SettingsHomeSection.Version)
            },
        hubRows =
            listOf(
                SettingsHomeRow.Profile,
                SettingsHomeRow.ProfileKeys,
                SettingsHomeRow.AiAgents,
                SettingsHomeRow.Notifications,
                SettingsHomeRow.ReadAloud,
                SettingsHomeRow.Dictation,
                SettingsHomeRow.Appearance,
                SettingsHomeRow.ChatFolders,
                SettingsHomeRow.PrivacySecurity,
                SettingsHomeRow.DataUsage,
                SettingsHomeRow.Relays,
            ),
        supportRows =
            listOf(
                SettingsHomeRow.Help,
                SettingsHomeRow.ChatWithSupport,
                SettingsHomeRow.Donate,
                SettingsHomeRow.DeveloperTools,
            ),
        showProfileHeader = hasActiveAccount,
    )

// Parent of a settings detail for back navigation; null means the Settings
// home. Kept pure so the back-stack shape (Key Packages → Developer tools → home,
// About → Help → home, ChatBubbleColors → Appearance) is unit-testable without Compose.
internal fun settingsDetailParent(detail: SettingsDetail): SettingsDetail? =
    when (detail) {
        SettingsDetail.ActionColor,
        SettingsDetail.ChatBubbleColors,
        SettingsDetail.Language,
        -> SettingsDetail.Appearance
        SettingsDetail.About,
        SettingsDetail.BugReport,
        -> SettingsDetail.Help
        SettingsDetail.KeyPackages -> SettingsDetail.Developer
        SettingsDetail.SupportRelays -> SettingsDetail.Support
        SettingsDetail.DiagnosticsImprovements -> SettingsDetail.DevicePrivacy
        else -> null
    }

@Composable
private fun settingsBackHandler(
    signOutInProgress: Boolean,
    detail: SettingsDetail?,
    onBackToChats: () -> Unit,
    onDetailChange: (SettingsDetail?) -> Unit,
) {
    BackHandler {
        if (signOutInProgress) return@BackHandler
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
    settingsBackHandler(appState.signOutInProgress, detail, onBackToChats, onDetailChange)

    if (detail == null) {
        SettingsHomeScreen(
            appState = appState,
            onBackToChats = onBackToChats,
            onOpenDetail = { onDetailChange(it) },
            viewport = homeViewport,
            onViewportChange = onHomeViewportChange,
        )
        return
    }
    SettingsDetailRoute(
        appState = appState,
        detail = detail,
        onOpenSupportChat = onOpenSupportChat,
        onOpenDiagnostics = onOpenDiagnostics,
        onDetailChange = onDetailChange,
    )
}

/** Every Settings detail destination and the parent each one returns to. */
@Composable
@Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod")
private fun SettingsDetailRoute(
    appState: WhiteNoiseAppState,
    detail: SettingsDetail,
    onOpenSupportChat: (ChatListItem) -> Unit,
    onOpenDiagnostics: () -> Unit,
    onDetailChange: (SettingsDetail?) -> Unit,
) {
    when (detail) {
        SettingsDetail.ShareConnect -> ShareConnectScreen(appState, onBack = { onDetailChange(null) })
        SettingsDetail.Appearance ->
            AppearanceScreen(
                appState = appState,
                onBack = { onDetailChange(null) },
                onOpenActionColor = { onDetailChange(SettingsDetail.ActionColor) },
                onOpenChatBubbleColors = { onDetailChange(SettingsDetail.ChatBubbleColors) },
                onOpenLanguage = { onDetailChange(SettingsDetail.Language) },
            )
        SettingsDetail.ActionColor ->
            ActionColorScreen(appState, onBack = { onDetailChange(SettingsDetail.Appearance) })
        SettingsDetail.ChatBubbleColors ->
            ChatBubbleColorsScreen(appState, onBack = { onDetailChange(SettingsDetail.Appearance) })
        SettingsDetail.Language -> LanguageScreen(appState, onBack = { onDetailChange(SettingsDetail.Appearance) })
        SettingsDetail.Data -> DataUsageScreen(appState, onBack = { onDetailChange(null) })
        SettingsDetail.Profile -> ProfileEditScreen(appState, onBack = { onDetailChange(null) })
        SettingsDetail.AccountKeys -> AccountKeysScreen(appState, onBack = { onDetailChange(null) })
        SettingsDetail.Relays -> RelaysScreen(appState, onBack = { onDetailChange(null) })
        SettingsDetail.Support ->
            SupportScreen(
                appState = appState,
                onBack = { onDetailChange(null) },
                onOpenSupportChat = onOpenSupportChat,
                onRelays = { onDetailChange(SettingsDetail.SupportRelays) },
            )
        SettingsDetail.SupportRelays ->
            RelaysScreen(appState, onBack = { onDetailChange(SettingsDetail.Support) })
        SettingsDetail.KeyPackages ->
            KeyPackagesScreen(appState, onBack = { onDetailChange(SettingsDetail.Developer) })
        SettingsDetail.Notifications -> NotificationsScreen(appState, onBack = { onDetailChange(null) })
        SettingsDetail.DevicePrivacy ->
            DevicePrivacyScreen(
                appState,
                onBack = { onDetailChange(null) },
                onOpenDiagnostics = { onDetailChange(SettingsDetail.DiagnosticsImprovements) },
            )
        SettingsDetail.DiagnosticsImprovements ->
            DiagnosticsImprovementsScreen(appState, onBack = { onDetailChange(SettingsDetail.DevicePrivacy) })
        SettingsDetail.AiAgents -> AiAgentsScreen(appState, onBack = { onDetailChange(null) })
        SettingsDetail.Donate -> DonateScreen(onBack = { onDetailChange(null) })
        SettingsDetail.TextToSpeech -> TextToSpeechScreen(appState, onBack = { onDetailChange(null) })
        SettingsDetail.Dictation -> DictationSettingsScreen(appState, onBack = { onDetailChange(null) })
        SettingsDetail.ChatFolders -> ChatFoldersScreen(appState, onBack = { onDetailChange(null) })
        SettingsDetail.Help ->
            HelpScreen(
                onBack = { onDetailChange(null) },
                onOpenBugReport = { onDetailChange(SettingsDetail.BugReport) },
                onOpenAbout = { onDetailChange(SettingsDetail.About) },
            )
        SettingsDetail.BugReport -> BugReportScreen(onBack = { onDetailChange(SettingsDetail.Help) })
        SettingsDetail.About ->
            AboutScreen(
                appState = appState,
                onOpenDeveloper = { onDetailChange(SettingsDetail.Developer) },
                versionName = BuildConfig.VERSION_NAME,
                buildNumber = BuildConfig.VERSION_CODE.toString(),
                mdkShortSha = BuildConfig.MDK_SHORT_SHA,
                onBack = { onDetailChange(SettingsDetail.Help) },
            )
        SettingsDetail.Developer ->
            DeveloperScreen(
                appState = appState,
                onBack = { onDetailChange(null) },
                onOpenDiagnostics = onOpenDiagnostics,
                onOpenKeyPackages = { onDetailChange(SettingsDetail.KeyPackages) },
            )
    }
}

internal const val SETTINGS_HOME_CONTENT_TAG = "settings-home-content"

/** The active profile as the Settings home shows it: display name, short public key and avatar inputs. */
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
    viewport: SettingsHomeViewport,
    onViewportChange: (SettingsHomeViewport) -> Unit,
) {
    var showAccountSelector by remember { mutableStateOf(false) }
    var showAddIdentity by remember { mutableStateOf(false) }
    var showSignOut by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activeAccount = appState.activeAccount

    // Read the live flag on invocation as teardown can begin before the next recomposition.
    fun whenIdle(action: () -> Unit) {
        if (!appState.signOutInProgress) action()
    }

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
        profileCount = appState.accounts.size,
        appUpdateInfo = appState.appUpdateInfo,
        versionName = BuildConfig.VERSION_NAME,
        onBack = { whenIdle(onBackToChats) },
        onOpenShareConnect = { whenIdle { onOpenDetail(SettingsDetail.ShareConnect) } },
        onAddProfile = { whenIdle { showAddIdentity = true } },
        onSwitchProfile = { whenIdle { showAccountSelector = true } },
        onOpenDetail = { detail -> whenIdle { onOpenDetail(detail) } },
        onChatWithSupport = { whenIdle { onOpenDetail(SettingsDetail.Support) } },
        onSignOut = { whenIdle { showSignOut = true } },
        viewport = viewport,
        onViewportChange = onViewportChange,
        onAppUpdateAction = {
            if (!appState.signOutInProgress) {
                scope.launch {
                    // Await the check before acting so the first tap uses a fresh result.
                    if (appState.appUpdateInfo.latestVersion == null) {
                        appState.refreshAppUpdate(force = true, notifyIfNewer = false)
                    }
                    appState.handleAppUpdateAction(context)
                }
            }
        },
    )

    if (appState.signOutInProgress) {
        SignOutProgressDialog()
        return
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
    if (showSignOut) {
        SignOutSheet(
            onConfirm = { deleteKeyPackages ->
                showSignOut = false
                signOutActiveAccount(appState, deleteKeyPackages)
            },
            onDismiss = { showSignOut = false },
        )
    }
}

/**
 * Settings home as the prototype lays it out: profile header, optional app updates, the hub group, the
 * support group, sign out and the version footer, in one lazy list whose viewport survives detail visits.
 */
@Composable
@Suppress("FunctionNaming", "LongMethod", "LongParameterList")
internal fun SettingsHomeContent(
    state: SettingsHomeState,
    account: SettingsHomeAccount?,
    profileCount: Int,
    appUpdateInfo: AppUpdateInfo,
    versionName: String,
    onBack: () -> Unit,
    onOpenShareConnect: () -> Unit,
    onAddProfile: () -> Unit,
    onSwitchProfile: () -> Unit,
    onOpenDetail: (SettingsDetail) -> Unit,
    onAppUpdateAction: () -> Unit,
    onChatWithSupport: () -> Unit = {},
    onSignOut: () -> Unit = {},
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
    SettingsScaffold(
        title = stringResource(R.string.settings),
        onBack = onBack,
        modifier = Modifier.testTag(SETTINGS_HOME_CONTENT_TAG),
        prominentTitle = true,
    ) {
        SettingsList(state = listState) {
            state.sections.forEach { section ->
                item(key = section.name) {
                    when (section) {
                        SettingsHomeSection.Profile ->
                            account?.let {
                                SettingsProfileHeader(
                                    account = it,
                                    profileCount = profileCount,
                                    onOpenShareConnect = onOpenShareConnect,
                                    onAddProfile = onAddProfile,
                                    onSwitchProfile = onSwitchProfile,
                                )
                            }
                        SettingsHomeSection.AppUpdates -> AppUpdateGroup(appUpdateInfo, onAppUpdateAction)
                        SettingsHomeSection.Hub ->
                            SettingsHubGroup(
                                rows = state.hubRows,
                                modifier = Modifier.padding(top = WhiteNoiseSpacing.FormField),
                                onOpenDetail = onOpenDetail,
                                onChatWithSupport = onChatWithSupport,
                            )
                        SettingsHomeSection.Support ->
                            SettingsHubGroup(
                                rows = state.supportRows,
                                modifier = Modifier.padding(top = WhiteNoiseSpacing.Section),
                                onOpenDetail = onOpenDetail,
                                onChatWithSupport = onChatWithSupport,
                            )
                        SettingsHomeSection.SignOut -> SignOutGroup(onSignOut)
                        SettingsHomeSection.Version -> SettingsVersionFooter(versionName)
                    }
                }
            }
        }
    }
}

/** Profile header group: the active profile row that opens Share & Connect, then add or switch profile. */
@Composable
@Suppress("FunctionNaming")
private fun SettingsProfileHeader(
    account: SettingsHomeAccount,
    profileCount: Int,
    onOpenShareConnect: () -> Unit,
    onAddProfile: () -> Unit,
    onSwitchProfile: () -> Unit,
) {
    Column(Modifier.padding(vertical = WhiteNoiseSpacing.Related).testTag("settings.profile_group")) {
        SettingsGroup {
            row("profile") { context -> SettingsProfileRow(context, account, onOpenShareConnect) }
            if (profileCount == 1) {
                row("add_profile") { context ->
                    SettingsAction(
                        context = context,
                        title = stringResource(R.string.settings_add_profile),
                        onClick = onAddProfile,
                        modifier = Modifier.testTag("settings.add_profile"),
                        leading = {
                            Icon(painterResource(R.drawable.ic_settings_person_add), contentDescription = null)
                        },
                    )
                }
            } else if (profileCount > 1) {
                // The prototype switches profiles from the chat list (M135); until that lands, switching stays here.
                row("switch_profile") { context ->
                    SettingsAction(
                        context = context,
                        title = stringResource(R.string.settings_switch_profile),
                        onClick = onSwitchProfile,
                        modifier = Modifier.testTag("settings.switch_profile"),
                        leading = {
                            Icon(painterResource(R.drawable.ic_settings_account_circle), contentDescription = null)
                        },
                    )
                }
            }
        }
    }
}

/** The active profile as one tappable row: avatar, name, short key, QR glyph and chevron. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
@Suppress("FunctionNaming")
private fun SettingsProfileRow(
    context: SettingsRowContext,
    account: SettingsHomeAccount,
    onClick: () -> Unit,
) {
    val shareDescription = stringResource(R.string.settings_open_share_connect_for, account.title)
    Surface(
        color = context.containerColor,
        shape = context.shapes.shape,
        modifier = Modifier.fillMaxWidth().settingsRowBorder(context, editable = true),
    ) {
        ListItem(
            headlineContent = {
                Text(
                    text = account.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            supportingContent = {
                Text(
                    text = account.subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            leadingContent = {
                Avatar(
                    title = account.title,
                    seed = account.seed,
                    size = SettingsHomeDefaults.ProfileAvatarSize,
                    pictureUrl = account.pictureUrl,
                )
            },
            trailingContent = { SettingsProfileTrailing() },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick)
                    .testTag("settings.active_profile")
                    .semantics(mergeDescendants = true) {
                        contentDescription = shareDescription
                        role = Role.Button
                    },
        )
    }
}

/** QR glyph and chevron that announce the profile row as the way to Share & Connect. */
@Composable
@Suppress("FunctionNaming")
private fun SettingsProfileTrailing() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_settings_qr_code_2),
            contentDescription = null,
            modifier = Modifier.size(SettingsHomeDefaults.IconSize),
        )
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One connected group of hub rows; the support group reuses it with a different top spacing. */
@Composable
@Suppress("FunctionNaming")
private fun SettingsHubGroup(
    rows: List<SettingsHomeRow>,
    modifier: Modifier,
    onOpenDetail: (SettingsDetail) -> Unit,
    onChatWithSupport: () -> Unit,
) {
    SettingsGroup(modifier = modifier) {
        rows.forEach { entry ->
            row(entry.name) { context ->
                SettingsHubLink(
                    context = context,
                    row = entry,
                    onClick = {
                        val detail = entry.detail
                        if (detail != null) onOpenDetail(detail) else onChatWithSupport()
                    },
                )
            }
        }
    }
}

/** Hub row: title from the row's string, Material Symbols glyph at the leading slot. */
@Composable
@Suppress("FunctionNaming")
private fun SettingsHubLink(
    context: SettingsRowContext,
    row: SettingsHomeRow,
    onClick: () -> Unit,
) {
    SettingsLink(
        context = context,
        title = stringResource(row.titleRes),
        onClick = onClick,
        leading = { SettingsHubIcon(row.iconRes, row.iconTag, MaterialTheme.colorScheme.onSurfaceVariant) },
    )
}

/** 24 dp leading glyph carrying the prototype's `settings.icon.*` test tag. */
@Composable
@Suppress("FunctionNaming")
private fun SettingsHubIcon(
    @DrawableRes iconRes: Int,
    iconTag: String,
    tint: Color,
) {
    Icon(
        painter = painterResource(iconRes),
        contentDescription = null,
        modifier = Modifier.size(SettingsHomeDefaults.IconSize).testTag("settings.icon.$iconTag"),
        tint = tint,
    )
}

/** Self-managed distribution only: one row that checks for or installs the latest release. */
@Composable
@Suppress("FunctionNaming")
private fun AppUpdateGroup(
    info: AppUpdateInfo,
    onClick: () -> Unit,
) {
    SettingsGroup(modifier = Modifier.padding(top = WhiteNoiseSpacing.FormField).testTag("settings.app_updates")) {
        row("app_updates") { context ->
            SettingsLink(
                context = context,
                title = stringResource(R.string.app_updates),
                subtitle = appUpdateSubtitle(info),
                onClick = onClick,
                leading = {
                    SettingsHubIcon(R.drawable.ic_download, "app_updates", MaterialTheme.colorScheme.onSurfaceVariant)
                },
            )
        }
    }
}

/** Installed and latest version summary, or the failure hint, worded as the previous row did. */
@Composable
private fun appUpdateSubtitle(info: AppUpdateInfo): String {
    val latest = info.latestVersion
    return when {
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
}

/** Sign out as the last group: a destructive link that opens the production sign-out sheet. */
@Composable
@Suppress("FunctionNaming")
private fun SignOutGroup(onSignOut: () -> Unit) {
    SettingsGroup(modifier = Modifier.padding(top = WhiteNoiseSpacing.Section)) {
        row("sign_out") { context ->
            SettingsLink(
                context = context,
                title = stringResource(R.string.sign_out),
                onClick = onSignOut,
                destructive = true,
                leading = {
                    SettingsHubIcon(R.drawable.ic_settings_logout, "sign_out", MaterialTheme.colorScheme.error)
                },
            )
        }
    }
}

/** Sizes the Settings home fixes independently of the row components. */
private object SettingsHomeDefaults {
    val ProfileAvatarSize = 56.dp
    val IconSize = 24.dp
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
