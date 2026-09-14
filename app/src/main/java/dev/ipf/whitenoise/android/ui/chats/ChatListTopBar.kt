package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AccountSwitchPreloadPolicy
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.chatsAvatarOpensSelector
import dev.ipf.whitenoise.android.state.quickProfileCycleTarget
import dev.ipf.whitenoise.android.state.requestQuickProfileCycle
import dev.ipf.whitenoise.android.ui.account.AccountAvatarButton
import dev.ipf.whitenoise.android.ui.account.AccountSelectorSheet
import dev.ipf.whitenoise.android.ui.account.QuickProfileCycleButton
import dev.ipf.whitenoise.android.ui.account.rememberQuickProfileCycleNotice
import dev.ipf.whitenoise.android.ui.common.LocalWhiteNoiseHeaderScroll
import dev.ipf.whitenoise.android.ui.common.accountActionColors
import dev.ipf.whitenoise.android.ui.profile.AddIdentitySheet
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke
import dev.ipf.whitenoise.android.ui.updates.AppUpdateIconButton
import dev.ipf.whitenoise.android.updates.AppUpdateInfo

internal const val CHAT_LIST_FILTER_CHIP_ALL_TAG = "chats.scope.chats"
internal const val CHAT_LIST_OTHER_ACCOUNT_AVATARS_TAG = "chat-list-other-account-avatars"

/** Stable test tag for a folder pill. */
internal fun chatListFilterChipTag(folderId: String): String = "chats.folder.$folderId"

/** Chat list header: account avatar and quick switch, or the search field with its filter action. */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod", "UnusedParameter", "FunctionNaming")
@Composable
internal fun ChatListTopBar(
    appState: WhiteNoiseAppState,
    searchOpen: Boolean,
    searchQuery: String,
    searchFocusRequester: FocusRequester,
    onSearchQueryChange: (String) -> Unit,
    onSearchOpen: () -> Unit,
    onSearchClose: () -> Unit,
    onMic: () -> Unit,
    onOpenSettings: () -> Unit,
    onSwitchAccount: (String) -> Unit,
    connectivityState: ConnectivityBannerState = ConnectivityBannerState.Hidden,
    onCycleAccount: (() -> Unit)? = null,
    updateInfo: AppUpdateInfo = appState.appUpdateInfo,
    selfUpdateEnabled: Boolean = BuildConfig.SELF_UPDATE_ENABLED,
    searchFilterState: GlobalSearchState = GlobalSearchState(),
    onSearchFilterCategory: ((GlobalSearchFilterCategory) -> Unit)? = null,
    onClearSearchFilters: () -> Unit = {},
) {
    var showSelector by remember(appState.runtimeGeneration) { mutableStateOf(false) }
    var showAddIdentity by remember(appState.runtimeGeneration) { mutableStateOf(false) }
    val cycleNotice = rememberQuickProfileCycleNotice()
    LaunchedEffect(appState.signOutInProgress, appState.wipeInProgress) {
        if (appState.signOutInProgress || appState.wipeInProgress) {
            showSelector = false
            showAddIdentity = false
        }
    }

    /** Opens the account selector unless sign-out or a wipe is in progress. */
    fun openSelector() {
        if (!appState.signOutInProgress && !appState.wipeInProgress) showSelector = true
    }

    /** Switches to the next quick-cycle account through the caller's handler or the app state. */
    fun cycle() {
        if (appState.quickProfileCycleTarget() == null) return
        if (onCycleAccount != null) {
            onCycleAccount()
        } else {
            appState.requestQuickProfileCycle(
                requestSwitch = { target, activated ->
                    appState.launchMutation {
                        appState.setActiveAccount(
                            label = target,
                            preloadPolicy = AccountSwitchPreloadPolicy.INTERACTIVE_LOCAL_ROWS,
                            onActivated = activated,
                        )
                    }
                },
                onSwitched = cycleNotice::show,
            )
        }
    }
    LaunchedEffect(appState.accounts, appState.runtimeGeneration) {
        appState.requestProfiles(appState.accounts.map { it.accountIdHex })
    }
    TopAppBar(
        title = {
            when {
                searchOpen ->
                    dev.ipf.whitenoise.android.ui.common.WhiteNoiseCompactSearchField(
                        value = searchQuery,
                        clearDescription = stringResource(R.string.chat_list_search_clear),
                        outlined = true,
                        onValueChange = onSearchQueryChange,
                        placeholder = stringResource(R.string.chat_list_search_hint),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .focusRequester(searchFocusRequester)
                                .testTag("chats.searchField"),
                        keyboardOptions =
                            KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences,
                                imeAction = ImeAction.Search,
                            ),
                        emptyTrailingIcon = {
                            IconButton(onClick = onMic) {
                                Icon(
                                    painter =
                                        androidx.compose.ui.res
                                            .painterResource(R.drawable.ic_mic),
                                    contentDescription = stringResource(R.string.chat_list_search_voice),
                                )
                            }
                        },
                    )
                else ->
                    ChatListInlineConnectivityIndicator(
                        state = connectivityState,
                        modifier = Modifier.padding(start = 12.dp),
                    )
            }
        },
        navigationIcon = {
            when {
                searchOpen ->
                    IconButton(onClick = onSearchClose) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                else -> {
                    val active = appState.activeAccount
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AccountAvatarButton(
                            title =
                                active?.let { appState.accountDisplayNameCached(it.accountIdHex) }
                                    ?: stringResource(R.string.app_name),
                            seed = active?.accountIdHex ?: "whitenoise",
                            pictureUrl = active?.let { appState.avatarUrl(it.accountIdHex) },
                            size = 40.dp,
                            touchTargetSize = 48.dp,
                            actionDescription =
                                stringResource(
                                    if (appState.chatsAvatarOpensSelector()) {
                                        R.string.switch_profile
                                    } else {
                                        R.string.open_settings
                                    },
                                ),
                            onClick = {
                                if (!appState.signOutInProgress && !appState.wipeInProgress) {
                                    if (appState.chatsAvatarOpensSelector()) openSelector() else onOpenSettings()
                                }
                            },
                            modifier = Modifier.padding(start = 8.dp).testTag("chats.switchProfile"),
                            // Per-account dot: light only when the active account
                            // itself has unread, same shared decision the other
                            // avatars use — not "some other account has unread" (#805).
                            showUnreadDot = appState.accountShowsUnreadDot(active?.label),
                            unreadDotColor = accountActionColors(appState, active?.label).container,
                        )
                        appState.quickProfileCycleTarget()?.let { next ->
                            QuickProfileCycleButton(
                                nextTitle = appState.accountDisplayNameCached(next.accountIdHex),
                                onClick = ::cycle,
                            )
                        }
                    }
                }
            }
        },
        actions = {
            if (searchOpen) {
                if (onSearchFilterCategory != null) {
                    ChatListSearchFilterAction(
                        state = searchFilterState,
                        onCategory = onSearchFilterCategory,
                        onClearAll = onClearSearchFilters,
                    )
                }
            } else {
                AppUpdateIconButton(
                    info = updateInfo,
                    selfUpdateEnabled = selfUpdateEnabled,
                    onOpenSettings = onOpenSettings,
                )
                IconButton(onClick = onSearchOpen) {
                    Icon(
                        painterResource(R.drawable.ic_search),
                        contentDescription = stringResource(R.string.chat_list_search_open),
                    )
                }
            }
        },
        scrollBehavior = LocalWhiteNoiseHeaderScroll.current,
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
    )
    if (showSelector) {
        AccountSelectorSheet(
            appState = appState,
            onDismiss = { showSelector = false },
            onAddAccount = {
                showSelector = false
                showAddIdentity = true
            },
            onAccountSwitched = { showSelector = false },
            onSettings = {
                showSelector = false
                onOpenSettings()
            },
        )
    }
    if (showAddIdentity) {
        AddIdentitySheet(appState = appState, onDismiss = { showAddIdentity = false })
    }
}

/** The prototype's filter entry beside the search field: the menu opens with the keyboard dismissed. */
@Suppress("FunctionNaming")
@Composable
private fun ChatListSearchFilterAction(
    state: GlobalSearchState,
    onCategory: (GlobalSearchFilterCategory) -> Unit,
    onClearAll: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    Box {
        GlobalSearchFilterIconButton(
            state = state,
            onClick = {
                focusManager.clearFocus()
                keyboardController?.hide()
                menuOpen = true
            },
        )
        GlobalSearchFilterMenu(
            expanded = menuOpen,
            state = state,
            onDismiss = { menuOpen = false },
            onCategory = onCategory,
            onClearAll = onClearAll,
        )
    }
}

/**
 * Inline top-bar search for a single conversation (#292): a back arrow plus an
 * auto-focused field (✕ clears). The result count and previous/next match
 * navigation live on the bottom bar above the keyboard
 * ([ConversationSearchNavBar]); the IME "search" action steps to the next
 * match so the on-screen keyboard alone can walk the results.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationSearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
    onSearchAction: () -> Unit,
    focusRequester: FocusRequester,
) {
    val searchDescription = stringResource(R.string.conversation_search_hint)
    TopAppBar(
        title = {
            dev.ipf.whitenoise.android.ui.common.WhiteNoiseCompactSearchField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = stringResource(R.string.conversation_search_messages),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .testTag("conversation.searchField")
                        .semantics { contentDescription = searchDescription },
                clearDescription = stringResource(R.string.conversation_search_clear),
                onClear = onClear,
                keyboardOptions =
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Search,
                    ),
                keyboardActions = KeyboardActions(onSearch = { onSearchAction() }),
            )
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(
                    painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.conversation_search_close),
                )
            }
        },
        actions = {
            // Date-jump has no native owner yet (M128); expose the prototype affordance truthfully as disabled.
            IconButton(onClick = {}, enabled = false, modifier = Modifier.testTag("conversation.search.calendar")) {
                Icon(painterResource(R.drawable.ic_calendar_month), stringResource(R.string.conversation_jump_to_date))
            }
        },
        scrollBehavior = LocalWhiteNoiseHeaderScroll.current,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

/**
 * Search match navigation pinned above the keyboard while in-chat search is
 * open: a centered result count with previous/next steppers on the trailing
 * edge. Lives in the conversation `bottomBar` slot in place of the composer,
 * mirroring the composer's `navigationBarsPadding().imePadding()` so it rides
 * up with the soft keyboard rather than hiding behind it.
 */
@Composable
internal fun ConversationSearchNavBar(
    matchCount: Int,
    activeIndex: Int,
    hasQuery: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    val navEnabled = matchCount > 0
    Surface(
        border = amoledSurfaceBorderStroke(),
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text =
                    when {
                        !hasQuery -> ""
                        matchCount > 0 ->
                            stringResource(
                                R.string.conversation_search_match_count,
                                activeIndex + 1,
                                matchCount,
                            )
                        else -> stringResource(R.string.conversation_search_no_matches)
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onPrev, enabled = navEnabled) {
                Icon(
                    Icons.Default.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.conversation_search_prev),
                )
            }
            IconButton(onClick = onNext, enabled = navEnabled) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.conversation_search_next),
                )
            }
        }
    }
}

/** Keeps native caller state stable while the visual row follows the shared folder-pill composition. */
@Suppress("LongParameterList", "FunctionNaming")
@Composable
internal fun ChatListFilterChips(
    chips: List<ChatFolderChipModel>,
    selectedFolderId: String?,
    onSelect: (String?) -> Unit,
    onEditFolder: (String) -> Unit = {},
    onManageFolders: (() -> Unit)? = null,
) {
    ChatFolderPills(chips, selectedFolderId, onSelect, onEditFolder, onManageFolders)
}
