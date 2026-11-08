@file:Suppress("TooManyFunctions")

package dev.ipf.whitenoise.android.ui.settings

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.account.AccountSelectorSheet
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.common.PublicKeyCopyText
import dev.ipf.whitenoise.android.ui.common.rememberSystemAnimationsEnabled
import dev.ipf.whitenoise.android.ui.icons.filled.Help
import dev.ipf.whitenoise.android.ui.icons.filled.Notifications
import dev.ipf.whitenoise.android.ui.navigation.SettingsDetail
import dev.ipf.whitenoise.android.ui.profile.AddIdentitySheet
import dev.ipf.whitenoise.android.ui.profile.ProfileEditScreen
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import dev.ipf.whitenoise.android.ui.updates.AppUpdateEmblem
import dev.ipf.whitenoise.android.updates.AppUpdateInfo
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keyed items of the Settings home list, in display order; the viewport is restored by section key.
 * The group sections render an unheaded connected group of [SettingsHomeRow]s; every other item renders its own
 * content. [searchCategoryRes] labels a group's rows in Settings search only.
 */
internal enum class SettingsHomeSection(
    val isGroup: Boolean = false,
    @param:StringRes val searchCategoryRes: Int? = null,
) {
    /** Profile row, Account, then add or switch profile, in one group. */
    Profile,

    /** A newer version is waiting, so the offer sits where a reader lands. */
    AppUpdateAvailable,

    AppPreferences(isGroup = true, searchCategoryRes = R.string.app_preferences),

    /** Agents and Help & support close the list of destinations. */
    Assist(isGroup = true),
    SignOut,

    /** Nothing to install: the check stays reachable, quietly, beside the version it reports on. */
    AppUpdateControls,
    Version,
}

/**
 * Rows of the Settings home in display order, grouped under the [section] each one belongs to. Each row
 * carries its label, Material Symbols icon and test-tag suffix, and the [detail] it opens.
 */
internal enum class SettingsHomeRow(
    @param:StringRes val titleRes: Int,
    @param:DrawableRes val iconRes: Int,
    val iconTag: String,
    val detail: SettingsDetail,
    val section: SettingsHomeSection,
) {
    Account(
        R.string.account,
        R.drawable.ic_settings_account_circle,
        "account",
        SettingsDetail.Account,
        SettingsHomeSection.Profile,
    ),
    Notifications(
        R.string.notifications,
        R.drawable.ic_settings_notifications,
        "notifications",
        SettingsDetail.Notifications,
        SettingsHomeSection.AppPreferences,
    ),
    Chats(
        R.string.chats,
        R.drawable.ic_settings_chat_bubble_outline,
        "chats",
        SettingsDetail.Chats,
        SettingsHomeSection.AppPreferences,
    ),
    Appearance(
        R.string.appearance,
        R.drawable.ic_settings_contrast,
        "appearance",
        SettingsDetail.Appearance,
        SettingsHomeSection.AppPreferences,
    ),
    VoiceLanguage(
        R.string.settings_voice_language,
        R.drawable.ic_translate,
        "voice_language",
        SettingsDetail.VoiceLanguage,
        SettingsHomeSection.AppPreferences,
    ),
    PrivacySecurity(
        R.string.settings_privacy_security,
        R.drawable.ic_settings_front_hand,
        "privacy_security",
        SettingsDetail.DevicePrivacy,
        SettingsHomeSection.AppPreferences,
    ),
    DataUsage(
        R.string.settings_data_usage,
        R.drawable.ic_settings_hard_drive,
        "data_usage",
        SettingsDetail.Data,
        SettingsHomeSection.AppPreferences,
    ),
    AiAgents(
        R.string.ai_agents,
        R.drawable.ic_ai_agents,
        "ai_agents",
        SettingsDetail.AiAgents,
        SettingsHomeSection.Assist,
    ),
    HelpSupport(
        R.string.settings_help_support,
        R.drawable.ic_info,
        "help_support",
        SettingsDetail.HelpSupport,
        SettingsHomeSection.Assist,
    ),
}

/** One connected group of the Settings home: its section key and rows in display order. */
internal data class SettingsHomeGroup(
    val section: SettingsHomeSection,
    val rows: List<SettingsHomeRow>,
)

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
    val groups: List<SettingsHomeGroup>,
    val showProfileHeader: Boolean,
)

/** The group sections in display order, derived from the enum rather than from fixed indices. */
private val settingsHomeGroupSections: List<SettingsHomeSection>
    get() = SettingsHomeSection.entries.filter { it.isGroup }

/**
 * Sections and groups of the Settings home; the account-bound sections appear only with an active account.
 * Every group section always appears, with the rows that declare it as their [SettingsHomeRow.section].
 */
internal fun settingsHomeState(
    hasActiveAccount: Boolean,
    selfUpdateEnabled: Boolean,
    updateAvailable: Boolean = false,
): SettingsHomeState =
    SettingsHomeState(
        sections =
            buildList {
                if (hasActiveAccount) add(SettingsHomeSection.Profile)
                // Store-managed builds own updates; off-store redirects violate policy.
                //
                // An update worth installing is the only thing here a reader has to act on, so it leads.
                // With nothing to install the same row would be a standing callout for news that never
                // changes, so it drops to the foot of the screen beside the version it reports on. A
                // dismissed-but-available update still counts as available: dismissal belongs to the
                // banner, and Settings should keep offering what the banner stopped interrupting with.
                if (selfUpdateEnabled && updateAvailable) add(SettingsHomeSection.AppUpdateAvailable)
                addAll(settingsHomeGroupSections)
                if (hasActiveAccount) add(SettingsHomeSection.SignOut)
                if (selfUpdateEnabled && !updateAvailable) add(SettingsHomeSection.AppUpdateControls)
                add(SettingsHomeSection.Version)
            },
        groups =
            settingsHomeGroupSections.map { section ->
                SettingsHomeGroup(
                    section = section,
                    rows = SettingsHomeRow.entries.filter { it.section == section },
                )
            },
        showProfileHeader = hasActiveAccount,
    )

// Parent of a settings detail for back navigation; null means the Settings
// home. Kept pure so the back-stack shape (Key Packages → Developer tools → Help & support → home,
// Sounds & vibration → Notifications → home) is unit-testable without Compose.

/** Parent destination a nested settings detail returns to. */
internal fun settingsDetailParent(detail: SettingsDetail): SettingsDetail? =
    when (detail) {
        SettingsDetail.Profile,
        SettingsDetail.AccountKeys,
        SettingsDetail.Relays,
        -> SettingsDetail.Account
        SettingsDetail.ActionColor,
        SettingsDetail.ChatBubbleColors,
        -> SettingsDetail.Appearance
        SettingsDetail.ChatFolders,
        SettingsDetail.Gestures,
        SettingsDetail.Composer,
        SettingsDetail.CustomEmoji,
        -> SettingsDetail.Chats
        SettingsDetail.Language,
        SettingsDetail.TextToSpeech,
        SettingsDetail.Translation,
        SettingsDetail.Dictation,
        -> SettingsDetail.VoiceLanguage
        SettingsDetail.NotificationSounds,
        SettingsDetail.NotificationDelivery,
        -> SettingsDetail.Notifications
        SettingsDetail.DiagnosticsImprovements,
        SettingsDetail.BlockedUsers,
        -> SettingsDetail.DevicePrivacy
        SettingsDetail.Help,
        SettingsDetail.Support,
        SettingsDetail.Donate,
        SettingsDetail.Developer,
        -> SettingsDetail.HelpSupport
        SettingsDetail.About,
        SettingsDetail.BugReport,
        -> SettingsDetail.Help
        SettingsDetail.SupportRelays -> SettingsDetail.Support
        SettingsDetail.KeyPackages,
        SettingsDetail.QuarantinedGroups,
        -> SettingsDetail.Developer
        SettingsDetail.ShareConnect,
        SettingsDetail.Account,
        SettingsDetail.Appearance,
        SettingsDetail.Chats,
        SettingsDetail.VoiceLanguage,
        SettingsDetail.HelpSupport,
        SettingsDetail.Notifications,
        SettingsDetail.DevicePrivacy,
        SettingsDetail.Data,
        SettingsDetail.AiAgents,
        -> null
    }

/** Back handler: nested detail, then home, blocked during sign-out. */
@Composable
private fun settingsBackHandler(
    signOutInProgress: Boolean,
    detail: SettingsDetail?,
    parentOf: (SettingsDetail) -> SettingsDetail?,
    onBackToChats: () -> Unit,
    onDetailChange: (SettingsDetail?) -> Unit,
) {
    BackHandler {
        if (signOutInProgress) return@BackHandler
        if (detail == null) {
            onBackToChats()
        } else {
            onDetailChange(parentOf(detail))
        }
    }
}

/** Settings root hosting the hub and every detail destination. */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming", "LongMethod", "LongParameterList", "CyclomaticComplexMethod")
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
    suppressMotion: Boolean = false,
) {
    // Issue #121: the prior shape only handled back from a detail
    // subscreen; when on the Settings home (detail == null) the system
    // back fell through to the Activity and exited the app. Always
    // claim back here — pop the detail when on a subscreen, otherwise
    // hand control to the chats list (mirroring the top-bar back arrow).
    val searchResources = LocalContext.current.resources
    val searchOwner = listOf<Any?>(appState.activeAccountRef, appState.runtimeGeneration)
    var searchOpen by rememberSaveable(appState.activeAccountRef, appState.runtimeGeneration) { mutableStateOf(false) }
    var searchQuery by rememberSaveable(appState.activeAccountRef, appState.runtimeGeneration) { mutableStateOf("") }
    var searchOriginId by rememberSaveable(appState.activeAccountRef, appState.runtimeGeneration) {
        mutableStateOf<String?>(null)
    }
    val searchResultList =
        rememberSaveable(
            appState.activeAccountRef,
            appState.runtimeGeneration,
            saver = LazyListState.Saver,
        ) { LazyListState() }
    var searchRevealed by rememberSaveable(
        appState.activeAccountRef,
        appState.runtimeGeneration,
    ) { mutableStateOf(false) }
    val catalog = remember { settingsSearchCatalog() }
    val searchOrigin = catalog.firstOrNull { it.id == searchOriginId }
    val eligibility = settingsSearchEligibility(appState)
    val searchConfiguration = LocalConfiguration.current
    val searchDocuments =
        remember(searchConfiguration, eligibility) {
            prepareSettingsSearch(catalog, eligibility, searchResources::getString)
        }
    LaunchedEffect(appState.appLockScreenVisible, appState.signOutInProgress, appState.wipeInProgress) {
        if (appState.appLockScreenVisible || appState.signOutInProgress || appState.wipeInProgress) {
            searchOpen = false
            searchQuery = ""
            searchOriginId = null
        }
    }
    // The profile photo on the home header opens Profile directly, so back returns home instead of Account.
    var profileFromHeader by rememberSaveable(appState.activeAccountRef) { mutableStateOf(false) }
    val parentOf: (SettingsDetail) -> SettingsDetail? = { target ->
        if (target == SettingsDetail.Profile && profileFromHeader) null else settingsDetailParent(target)
    }
    val changeDetail: (SettingsDetail?) -> Unit = { destination ->
        if (searchOrigin != null &&
            detail == searchOrigin.destination &&
            destination == detail?.let(parentOf)
        ) {
            searchOriginId = null
            onDetailChange(null)
        } else {
            if (destination == null) searchOriginId = null
            onDetailChange(destination)
        }
    }
    settingsBackHandler(appState.signOutInProgress, detail, parentOf, {
        if (searchOrigin != null) {
            searchOriginId = null
        } else if (searchOpen) {
            searchOpen = false
            searchQuery = ""
        } else {
            onBackToChats()
        }
    }, changeDetail)

    var openProfilePictureActions by
        rememberSaveable(appState.activeAccountRef) { mutableStateOf(false) }
    SettingsRouteMotion(
        target = detail,
        depth = settingsDetailDepth(detail),
        ownerKey = appState.activeAccountRef to appState.runtimeGeneration,
        suppressMotion = suppressMotion,
        contentVisible = !appState.appLockScreenVisible,
    ) { route ->
        CompositionLocalProvider(
            LocalSettingsSearchTarget provides
                searchOrigin?.takeIf { it.destination == route && eligibility.allows(it) },
            LocalSettingsSearchRevealConsumed provides searchRevealed,
            LocalSettingsSearchOnReveal provides { searchRevealed = true },
        ) {
            if (route == null) {
                SettingsHomeScreen(
                    appState = appState,
                    onBackToChats = { if (searchOrigin != null) searchOriginId = null else onBackToChats() },
                    searchOpen = searchOpen && searchOrigin == null,
                    searchQuery = searchQuery,
                    searchEligibility = eligibility,
                    searchDocuments = searchDocuments,
                    suppressSearchMotion = suppressMotion,
                    onSearchOpenChange = {
                        if (it) searchOriginId = null
                        searchOpen = it
                        if (!it) searchQuery = ""
                    },
                    onSearchQueryChange = { value ->
                        val next = value.take(SETTINGS_SEARCH_MAX_QUERY)
                        if (next != searchQuery) {
                            searchQuery = next
                            // An edit starts at the strongest result. Detail return does not edit
                            // the query, so its saved result viewport remains unchanged.
                            searchResultList.requestScrollToItem(0)
                        }
                    },
                    searchResultList = searchResultList,
                    onSearchSelection = { entry ->
                        val currentDocument =
                            prepareSettingsSearch(
                                listOf(entry),
                                settingsSearchEligibilitySnapshot(appState, eligibility.pushAvailable),
                                searchResources::getString,
                            )
                        if (searchOwner == listOf<Any?>(appState.activeAccountRef, appState.runtimeGeneration) &&
                            searchSettings(currentDocument, searchQuery).isNotEmpty()
                        ) {
                            searchOriginId = entry.id
                            searchRevealed = false
                            onDetailChange(entry.destination)
                        }
                    },
                    onOpenDetail = {
                        profileFromHeader = false
                        onDetailChange(it)
                    },
                    onOpenProfilePictureActions = {
                        profileFromHeader = true
                        openProfilePictureActions = true
                        onDetailChange(SettingsDetail.Profile)
                    },
                    viewport = homeViewport,
                    onViewportChange = onHomeViewportChange,
                )
            } else {
                SettingsDetailRoute(
                    appState = appState,
                    detail = route,
                    onOpenSupportChat = onOpenSupportChat,
                    onOpenDiagnostics = onOpenDiagnostics,
                    onDetailChange = { target ->
                        // Hub rows open their page from within a detail, so the header shortcut is spent.
                        if (target != null) profileFromHeader = false
                        changeDetail(target)
                    },
                    onBack = {
                        openProfilePictureActions = false
                        changeDetail(parentOf(route))
                    },
                    openProfilePictureActionsOnEntry = openProfilePictureActions,
                    onProfilePictureActionsOpened = { openProfilePictureActions = false },
                )
            }
        }
    }
}

/** Every Settings detail destination; each returns through [onBack] to the parent chosen by the caller. */
@Composable
@Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod")
private fun SettingsDetailRoute(
    appState: WhiteNoiseAppState,
    detail: SettingsDetail,
    onOpenSupportChat: (ChatListItem) -> Unit,
    onOpenDiagnostics: () -> Unit,
    onDetailChange: (SettingsDetail?) -> Unit,
    onBack: () -> Unit,
    openProfilePictureActionsOnEntry: Boolean,
    onProfilePictureActionsOpened: () -> Unit,
) {
    when (detail) {
        SettingsDetail.ShareConnect -> ShareConnectScreen(appState, onBack = onBack)
        SettingsDetail.Account ->
            AccountScreen(appState, onBack = onBack, onOpenDetail = onDetailChange)
        SettingsDetail.Chats -> ChatsScreen(appState, onBack = onBack, onOpenDetail = onDetailChange)
        SettingsDetail.VoiceLanguage ->
            VoiceLanguageScreen(appState, onBack = onBack, onOpenDetail = onDetailChange)
        SettingsDetail.HelpSupport ->
            HelpSupportScreen(appState, onBack = onBack, onOpenDetail = onDetailChange)
        SettingsDetail.Appearance ->
            AppearanceScreen(
                appState = appState,
                onBack = onBack,
                onOpenActionColor = { onDetailChange(SettingsDetail.ActionColor) },
                onOpenChatBubbleColors = { onDetailChange(SettingsDetail.ChatBubbleColors) },
            )
        SettingsDetail.Composer -> ComposerSettingsScreen(appState, onBack = onBack)
        SettingsDetail.ActionColor -> ActionColorScreen(appState, onBack = onBack)
        SettingsDetail.ChatBubbleColors -> ChatBubbleColorsScreen(appState, onBack = onBack)
        SettingsDetail.Language -> LanguageScreen(appState, onBack = onBack)
        SettingsDetail.CustomEmoji -> CustomEmojiScreen(appState, onBack = onBack)
        SettingsDetail.Data -> DataUsageScreen(appState, onBack = onBack)
        SettingsDetail.Profile ->
            ProfileEditScreen(
                appState = appState,
                onBack = onBack,
                openPictureActionsOnEntry = openProfilePictureActionsOnEntry,
                onPictureActionsOpened = onProfilePictureActionsOpened,
            )
        SettingsDetail.AccountKeys -> AccountKeysScreen(appState, onBack = onBack)
        SettingsDetail.Relays -> RelaysScreen(appState, onBack = onBack)
        SettingsDetail.Support ->
            SupportScreen(
                appState = appState,
                onBack = onBack,
                onOpenSupportChat = onOpenSupportChat,
                onRelays = { onDetailChange(SettingsDetail.SupportRelays) },
            )
        SettingsDetail.SupportRelays -> RelaysScreen(appState, onBack = onBack)
        SettingsDetail.KeyPackages -> KeyPackagesScreen(appState, onBack = onBack)
        SettingsDetail.QuarantinedGroups -> {
            if (appState.developerMode) {
                QuarantinedGroupsScreen(appState, onBack = onBack)
            } else {
                LaunchedEffect(detail) { onBack() }
            }
        }
        SettingsDetail.Notifications ->
            NotificationsScreen(
                appState,
                onBack = onBack,
                onOpenSounds = { onDetailChange(SettingsDetail.NotificationSounds) },
                onOpenDelivery = { onDetailChange(SettingsDetail.NotificationDelivery) },
            )
        SettingsDetail.NotificationSounds -> NotificationSoundsScreen(appState, onBack = onBack)
        SettingsDetail.NotificationDelivery -> NotificationDeliveryScreen(appState, onBack = onBack)
        SettingsDetail.DevicePrivacy ->
            DevicePrivacyScreen(
                appState,
                onBack = onBack,
                onOpenDiagnostics = { onDetailChange(SettingsDetail.DiagnosticsImprovements) },
                onOpenBlockedUsers = { onDetailChange(SettingsDetail.BlockedUsers) },
            )
        SettingsDetail.BlockedUsers -> BlockedUsersScreen(appState, onBack = onBack)
        SettingsDetail.DiagnosticsImprovements -> DiagnosticsImprovementsScreen(appState, onBack = onBack)
        SettingsDetail.AiAgents -> AiAgentsScreen(appState, onBack = onBack)
        SettingsDetail.Donate -> DonateScreen(onBack = onBack)
        SettingsDetail.TextToSpeech -> TextToSpeechScreen(appState, onBack = onBack)
        SettingsDetail.Translation -> TranslationSettingsScreen(onBack = onBack)
        SettingsDetail.Dictation -> DictationSettingsScreen(appState, onBack = onBack)
        SettingsDetail.Gestures -> GestureSettingsScreen(appState, onBack = onBack)
        SettingsDetail.ChatFolders -> ChatFoldersScreen(appState, onBack = onBack)
        SettingsDetail.Help ->
            HelpScreen(
                onBack = onBack,
                onOpenBugReport = { onDetailChange(SettingsDetail.BugReport) },
                onOpenAbout = { onDetailChange(SettingsDetail.About) },
            )
        SettingsDetail.BugReport -> BugReportScreen(onBack = onBack)
        SettingsDetail.About ->
            AboutScreen(
                appState = appState,
                onOpenDeveloper = { onDetailChange(SettingsDetail.Developer) },
                versionName = BuildConfig.VERSION_NAME,
                buildNumber = BuildConfig.VERSION_CODE.toString(),
                mdkShortSha = BuildConfig.MDK_SHORT_SHA,
                onBack = onBack,
            )
        SettingsDetail.Developer ->
            DeveloperScreen(
                appState = appState,
                onBack = onBack,
                onOpenDiagnostics = onOpenDiagnostics,
                onOpenKeyPackages = { onDetailChange(SettingsDetail.KeyPackages) },
                onOpenQuarantinedGroups = { onDetailChange(SettingsDetail.QuarantinedGroups) },
                onOpenDemoChat = onOpenSupportChat,
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
    val npub: String = "",
)

/** The Settings hub. */
@Composable
@Suppress("FunctionNaming", "LongMethod", "LongParameterList")
private fun SettingsHomeScreen(
    appState: WhiteNoiseAppState,
    onBackToChats: () -> Unit,
    onOpenDetail: (SettingsDetail) -> Unit,
    searchOpen: Boolean,
    searchQuery: String,
    searchEligibility: SettingsSearchEligibility,
    searchDocuments: List<SettingsSearchDocument>,
    onSearchOpenChange: (Boolean) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    searchResultList: LazyListState,
    suppressSearchMotion: Boolean,
    onSearchSelection: (SettingsSearchEntry) -> Unit,
    onOpenProfilePictureActions: () -> Unit,
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

    /** Runs [action] unless sign-out is in progress. */
    fun whenIdle(action: () -> Unit) {
        if (!appState.signOutInProgress) action()
    }

    LaunchedEffect(appState.accounts.size) {
        if (showAddIdentity) showAddIdentity = false
    }

    val keyboard = LocalSoftwareKeyboardController.current
    val eligibility = searchEligibility
    val homeConfiguration = LocalConfiguration.current
    // Query edits retain the existing header presentation without invoking cold identity/profile helpers.
    val homeAccount =
        remember(
            activeAccount,
            appState.runtimeGeneration,
            appState.profileRevisionForCompose,
            homeConfiguration,
        ) {
            activeAccount?.let { account ->
                SettingsHomeAccount(
                    title = appState.displayName(account.accountIdHex),
                    subtitle = appState.shortNpub(account.accountIdHex),
                    seed = account.accountIdHex,
                    pictureUrl = appState.avatarUrl(account.accountIdHex),
                    npub = appState.npubForDisplay(account.accountIdHex),
                )
            }
        }
    SettingsHomeContent(
        searchOpen = searchOpen,
        searchQuery = searchQuery,
        onSearchOpenChange = onSearchOpenChange,
        onSearchQueryChange = onSearchQueryChange,
        searchResults = searchSettings(searchDocuments, searchQuery),
        searchResultList = searchResultList,
        searchAnimationsEnabled = !suppressSearchMotion && rememberSystemAnimationsEnabled(),
        onSearchSelection = { entry ->
            if (canActivateSettingsSearchResult(appState, entry, eligibility.pushAvailable)) {
                keyboard?.hide()
                onSearchSelection(entry)
            }
        },
        state =
            settingsHomeState(
                hasActiveAccount = activeAccount != null,
                selfUpdateEnabled = BuildConfig.SELF_UPDATE_ENABLED,
                updateAvailable = appState.appUpdateInfo.isUpdateAvailable,
            ),
        account = homeAccount,
        profileCount = appState.accounts.size,
        appUpdateInfo = appState.appUpdateInfo,
        versionName = BuildConfig.VERSION_NAME,
        onBack = { whenIdle(onBackToChats) },
        onOpenShareConnect = { whenIdle { onOpenDetail(SettingsDetail.ShareConnect) } },
        onOpenProfilePictureActions = { whenIdle(onOpenProfilePictureActions) },
        onAddProfile = { whenIdle { showAddIdentity = true } },
        onSwitchProfile = { whenIdle { showAccountSelector = true } },
        onOpenDetail = { detail -> whenIdle { onOpenDetail(detail) } },
        onSignOut = { whenIdle { showSignOut = true } },
        viewport = viewport,
        onViewportChange = onViewportChange,
        onAppUpdateAction = {
            if (!appState.signOutInProgress) {
                scope.launch {
                    runSettingsAppUpdateAction(
                        info = appState.appUpdateInfo,
                        refresh = { appState.refreshAppUpdate(force = true, notifyIfNewer = false) },
                        onUpdate = { appState.handleAppUpdateAction(context) },
                    )
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

/** Recheck the existing memory prerequisites and live platform capability only at activation. */
private fun canActivateSettingsSearchResult(
    appState: WhiteNoiseAppState,
    entry: SettingsSearchEntry,
    pushAvailable: Boolean,
): Boolean {
    val current =
        settingsSearchEligibilitySnapshot(
            appState,
            if (entry.pushRequired) appState.nativePushCapability().isAvailable else pushAvailable,
        )
    val sessionInteractive = !appState.signOutInProgress && !appState.appLockScreenVisible && !appState.wipeInProgress
    return current.allows(entry) && sessionInteractive
}

/**
 * Settings home as the prototype lays it out: profile header, optional app updates, the labelled Account,
 * App and Support groups, sign out and the version footer, in one lazy list whose viewport survives
 * detail visits.
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
    onOpenProfilePictureActions: () -> Unit = {},
    onAddProfile: () -> Unit,
    onSwitchProfile: () -> Unit,
    onOpenDetail: (SettingsDetail) -> Unit,
    onAppUpdateAction: () -> Unit,
    onSignOut: () -> Unit = {},
    viewport: SettingsHomeViewport = SettingsHomeViewport.Top,
    onViewportChange: (SettingsHomeViewport) -> Unit = {},
    searchOpen: Boolean = false,
    searchQuery: String = "",
    onSearchOpenChange: (Boolean) -> Unit = {},
    onSearchQueryChange: (String) -> Unit = {},
    searchResults: List<SettingsSearchDocument> = emptyList(),
    searchResultList: LazyListState = rememberLazyListState(),
    searchAnimationsEnabled: Boolean = rememberSystemAnimationsEnabled(),
    onSearchSelection: (SettingsSearchEntry) -> Unit = {},
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
        onBack = { if (searchOpen) onSearchOpenChange(false) else onBack() },
        titleContent = settingsHomeSearchTitle(searchOpen, searchQuery, onSearchQueryChange),
        topBarActions = { SettingsHomeSearchAction(searchOpen) { onSearchOpenChange(true) } },
        modifier = Modifier.testTag(SETTINGS_HOME_CONTENT_TAG),
        prominentTitle = true,
    ) {
        if (searchOpen && searchQuery.isNotBlank()) {
            SettingsSearchResults(searchResults, searchResultList, onSearchSelection, searchAnimationsEnabled)
        } else {
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
                                        onOpenProfilePictureActions = onOpenProfilePictureActions,
                                        onAddProfile = onAddProfile,
                                        onSwitchProfile = onSwitchProfile,
                                        onOpenAccount = { onOpenDetail(SettingsHomeRow.Account.detail) },
                                    )
                                }
                            SettingsHomeSection.AppUpdateAvailable ->
                                AppUpdateGroup(appUpdateInfo, onAppUpdateAction, prominent = true)
                            SettingsHomeSection.AppUpdateControls ->
                                AppUpdateGroup(appUpdateInfo, onAppUpdateAction, prominent = false)
                            SettingsHomeSection.AppPreferences,
                            SettingsHomeSection.Assist,
                            ->
                                state.groups.firstOrNull { it.section == section }?.let { group ->
                                    SettingsHubSection(group, onOpenDetail)
                                }
                            SettingsHomeSection.SignOut -> SignOutGroup(onSignOut)
                            SettingsHomeSection.Version -> SettingsVersionFooter(versionName)
                        }
                    }
                }
            }
        }
    }
}

/** Build only the existing title slot; the Settings toolbar continues owning its layout. */
private fun settingsHomeSearchTitle(
    searchOpen: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
): (@Composable () -> Unit)? = if (searchOpen) ({ SettingsSearchField(query, onQueryChange) }) else null

@Composable
@Suppress("FunctionNaming")
private fun SettingsHomeSearchAction(
    searchOpen: Boolean,
    onOpen: () -> Unit,
) {
    if (!searchOpen) {
        IconButton(onClick = onOpen, modifier = Modifier.testTag("settings.search.open")) {
            Icon(painterResource(R.drawable.ic_search), contentDescription = stringResource(R.string.settings_search))
        }
    }
}

/** Profile header group: the active profile row that opens Share & Connect, Account, then add or switch profile. */
@Composable
@Suppress("FunctionNaming")
private fun SettingsProfileHeader(
    account: SettingsHomeAccount,
    profileCount: Int,
    onOpenShareConnect: () -> Unit,
    onOpenProfilePictureActions: () -> Unit,
    onAddProfile: () -> Unit,
    onSwitchProfile: () -> Unit,
    onOpenAccount: () -> Unit,
) {
    Column(Modifier.padding(vertical = WhiteNoiseSpacing.Related).testTag("settings.profile_group")) {
        SettingsGroup {
            row("profile") { context ->
                SettingsProfileRow(context, account, onOpenShareConnect, onOpenProfilePictureActions)
            }
            row(SettingsHomeRow.Account.name) { context ->
                SettingsLink(
                    context = context,
                    title = stringResource(SettingsHomeRow.Account.titleRes),
                    onClick = onOpenAccount,
                    leading = {
                        SettingsHubIcon(
                            SettingsHomeRow.Account.iconRes,
                            SettingsHomeRow.Account.iconTag,
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
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

/** The active profile as one tappable row: avatar, name, short key that copies the full key, QR glyph and chevron. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
@Suppress("FunctionNaming")
private fun SettingsProfileRow(
    context: SettingsRowContext,
    account: SettingsHomeAccount,
    onClick: () -> Unit,
    onAvatarClick: () -> Unit,
) {
    val shareDescription = stringResource(R.string.settings_open_share_connect_for, account.title)
    val editPictureDescription = stringResource(R.string.profile_picture_edit)
    Surface(
        color = context.containerColor,
        shape = context.shapes.shape,
        modifier = Modifier.fillMaxWidth().settingsRowBorder(context),
    ) {
        ListItem(
            headlineContent = {
                EmojiLabel(
                    text = account.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            supportingContent = {
                // The short key copies the whole key; the rest of the row still opens Share & Connect (#3565).
                PublicKeyCopyText(
                    account.subtitle,
                    account.npub,
                    Modifier.testTag("settings.active_profile_public_key"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            leadingContent = {
                Box(
                    modifier =
                        Modifier
                            .size(SettingsHomeDefaults.ProfileAvatarSize)
                            .clickable(role = Role.Button, onClick = onAvatarClick)
                            .testTag("settings.active_profile_avatar")
                            .semantics {
                                contentDescription = editPictureDescription
                                role = Role.Button
                            },
                ) {
                    Avatar(
                        title = account.title,
                        seed = account.seed,
                        size = SettingsHomeDefaults.ProfileAvatarSize,
                        pictureUrl = account.pictureUrl,
                        pictureDomain = AvatarImageLoader.publicProfiles(),
                    )
                }
            },
            trailingContent = { SettingsProfileTrailing() },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick)
                    .testTag("settings.active_profile")
                    .semantics {
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

/** A hub section: one connected, unheaded group of its rows. */
@Composable
@Suppress("FunctionNaming")
internal fun SettingsHubSection(
    group: SettingsHomeGroup,
    onOpenDetail: (SettingsDetail) -> Unit,
) {
    Column(
        modifier = Modifier.testTag("settings.section.${group.section.name}"),
        verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
    ) {
        SettingsGroup {
            group.rows.forEach { entry ->
                row(entry.name) { context ->
                    SettingsLink(
                        context = context,
                        title = stringResource(entry.titleRes),
                        onClick = { onOpenDetail(entry.detail) },
                        leading = {
                            SettingsHubIcon(entry.iconRes, entry.iconTag, MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                    )
                }
            }
        }
    }
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
    prominent: Boolean,
) {
    SettingsGroup(modifier = Modifier.padding(top = WhiteNoiseSpacing.FormField).testTag("settings.app_updates")) {
        row("app_updates") { context ->
            SettingsLink(
                context = context,
                title = stringResource(R.string.app_updates),
                subtitle = appUpdateSubtitle(info),
                onClick = onClick,
                // The emblem announces something to do. Without an update it would be announcing
                // that there is nothing to announce.
                leading = if (prominent) ({ AppUpdateEmblem() }) else null,
            )
        }
    }
}

/** Concise prototype status uses the real release version and keeps a failed check actionable. */
@Composable
private fun appUpdateSubtitle(info: AppUpdateInfo): String =
    when {
        info.lastAttemptErrorReport != null -> stringResource(R.string.app_update_settings_check_failed)
        info.latestVersion == null -> stringResource(R.string.app_update_row_unknown)
        !info.isUpdateAvailable -> stringResource(R.string.app_update_row_current)
        else -> stringResource(R.string.app_update_row_available, info.latestVersion)
    }

/** Retry failed/unknown/current checks before delegating to the existing verified update flow. */
internal suspend fun runSettingsAppUpdateAction(
    info: AppUpdateInfo,
    refresh: suspend () -> AppUpdateInfo,
    onUpdate: () -> Unit,
) {
    if (!info.isUpdateAvailable || info.lastAttemptErrorReport != null) {
        val refreshed = refresh()
        // A cached newer release must not turn another failed check into an install request.
        if (refreshed.lastAttemptErrorReport != null) return
    }
    onUpdate()
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
