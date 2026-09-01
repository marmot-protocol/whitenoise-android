package dev.ipf.whitenoise.android.ui.chats

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.key
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.ChatListIdentifierSearch
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.core.MessageBodyMatch
import dev.ipf.whitenoise.android.core.Nip05Resolver
import dev.ipf.whitenoise.android.core.canonicalChatListBodyMatches
import dev.ipf.whitenoise.android.core.canonicalChatListGroupId
import dev.ipf.whitenoise.android.core.chatFolderChatIds
import dev.ipf.whitenoise.android.core.chatListItemDisplayTitle
import dev.ipf.whitenoise.android.core.localeInvariantFold
import dev.ipf.whitenoise.android.core.projectChatListSearchSections
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.chats.newchat.NewChatFlowHost
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.common.DragSelectionVisibleItem
import dev.ipf.whitenoise.android.ui.common.ErrorContent
import dev.ipf.whitenoise.android.ui.common.InlineErrorBanner
import dev.ipf.whitenoise.android.ui.common.LoadFailurePlacement
import dev.ipf.whitenoise.android.ui.common.LoadingScreen
import dev.ipf.whitenoise.android.ui.common.LocalSnackbarBottomInset
import dev.ipf.whitenoise.android.ui.common.LocalSnackbarContentInset
import dev.ipf.whitenoise.android.ui.common.accountActionColors
import dev.ipf.whitenoise.android.ui.common.anchoredDragSelection
import dev.ipf.whitenoise.android.ui.common.dragSelectionAutoScrollDelta
import dev.ipf.whitenoise.android.ui.common.dragSelectionEndpoint
import dev.ipf.whitenoise.android.ui.common.loadFailurePlacement
import dev.ipf.whitenoise.android.ui.common.rememberGroupTitleCopy
import dev.ipf.whitenoise.android.ui.conversation.TtsTransportBar
import dev.ipf.whitenoise.android.ui.settings.ChatFolderEditScreen
import dev.ipf.whitenoise.android.ui.testing.PerformanceTestTags
import dev.ipf.whitenoise.android.ui.testing.performanceTestTag
import dev.ipf.whitenoise.android.ui.theme.Dimens
import dev.ipf.whitenoise.android.updates.AppUpdateInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

/** Keeps the process-wide TTS transport in normal flow above every chat-list state. */
@Suppress("FunctionNaming")
@Composable
internal fun ChatListBodyFrame(
    ttsTransport: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Column(modifier) {
        ttsTransport()
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
            content = content,
        )
    }
}

/** Renders the active account's authoritative chat-list projection and actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatsScreen(
    appState: WhiteNoiseAppState,
    controller: ChatsController,
    onOpenSettings: () -> Unit,
    // (chat, focusMessageId, justCreated): focusMessageId is non-null only when
    // the row was a message-body search hit (issue #290); the conversation then
    // scrolls to that message on open. justCreated is true only when the chat
    // was just created by the New Chat / Create Group flow (issue #321), which
    // opens it with the composer focused + keyboard up.
    onOpenGroup: (ChatListItem, String?, Boolean, visibleActiveListHeadId: String?) -> Unit,
    // MainShell supplies the request-owned presentation handoff for the
    // home-screen quick toggle. Isolated screens keep the legacy direct path.
    onQuickSwitchAccount: ((String) -> Unit)? = null,
    // Head row id captured when the shell opened a conversation from this list.
    // Compared once on re-entry so a background reorder while away can snap to
    // item 0 without yanking an active on-list reader (issue #1313).
    conversationReturnHeadId: String? = null,
    onConversationReturnHeadHandled: () -> Unit = {},
    // Chat-list profile opens must capture the same visible filtered head used
    // by direct conversation opens so profile-sheet Message/shared-group routes
    // can arm the return snap (#1313).
    onPresentProfile: (npub: String, visibleActiveListHeadId: String?) -> Unit = { npub, _ ->
        appState.presentProfile(npub)
    },
    // Shell-owned global search state survives conversation navigation (#1941).
    globalSearchState: GlobalSearchState = GlobalSearchState(),
    onGlobalSearchStateChange: ((GlobalSearchState) -> GlobalSearchState) -> Unit = {},
    // Shell-owned so the filter survives conversation navigation (issue #1897).
    selectedFolderId: String? = null,
    onSelectFolder: (String?) -> Unit = {},
    onTtsTransportBodyClick: (() -> Unit)? = null,
    onGroupCreateSubmitted: () -> Long = { 0L },
    onGroupCreateCompletedOpen: (ChatListItem, Long) -> Unit = { item, _ ->
        onOpenGroup(item, null, false, null)
    },
    onGroupCreateFlowSuperseded: () -> Unit = {},
) {
    val groupTitleCopy = rememberGroupTitleCopy()
    var showNewChatFlow by rememberSaveable { mutableStateOf(false) }
    val openNewMessageFlow = { showNewChatFlow = true }
    var pendingBulkDelete by remember { mutableStateOf<List<ChatListItem>?>(null) }
    var actionSheetChatId by
        remember(appState.activeAccountRef, appState.runtimeGeneration) { mutableStateOf<String?>(null) }
    // Folder-assignment sheet targets for the current selection, and the
    // create form pre-populated with them when New folder is picked there.
    val folderHandoff = rememberFolderHandoff(appState.activeAccountRef)
    val selectedChatIds = remember { mutableStateSetOf<String>() }
    val selectionMode = selectedChatIds.isNotEmpty()
    // Typed Date and Content filters cannot be exposed until the MDK search
    // contract executes them. Keeping this false prevents the UI from claiming
    // that results are filtered when only the ordinary text query is applied.
    val interactiveGlobalSearchFilterSectionsAvailable = false
    val searchOpen = globalSearchState.isOpen
    val searchQuery = globalSearchState.query
    val globalSearchPresentationState =
        reconcileGlobalSearchFilterSheet(
            searchState = globalSearchState,
            interactiveSectionsAvailable = interactiveGlobalSearchFilterSectionsAvailable,
            selectionMode = selectionMode,
        )
    // Async message-body results retain their exact query/account/list key.
    // A superseding key therefore hides stale matches synchronously, before
    // the replacement effect gets its first post-composition frame (#2202).
    var bodySearchResult by remember { mutableStateOf<ChatListBodySearchResult?>(null) }
    // Resolution state for a pasted Nostr identifier in the search field (#344).
    // An npub resolves synchronously; a NIP-05 address resolves over the network
    // (loading → resolved/failed). Plain-text queries stay [None] and the list
    // filters exactly as before.
    var identifierResolution by remember { mutableStateOf<IdentifierResolution>(IdentifierResolution.None) }
    val folderStoreState by appState.chatFolderPreferences.state.collectAsState()
    val accountFolders =
        remember(folderStoreState, appState.activeAccountRef) {
            appState.activeAccountRef?.let { appState.chatFolderPreferences.foldersFor(it) }.orEmpty()
        }
    val selectedFolder = accountFolders.firstOrNull { it.id == selectedFolderId }
    val selectedFolderRule =
        remember(folderStoreState, appState.activeAccountRef, selectedFolder) {
            selectedFolder?.let { folder ->
                appState.activeAccountRef?.let { appState.chatFolderPreferences.folderRule(it, folder.id) }
            }
        }
    // Effective folder membership: manual members plus rule matches,
    // re-derived from the live list so rule-driven chats join and leave
    // folders as rosters, unread state, and mute state change.
    val resolveFolderChatIds: (String) -> Set<String> =
        remember(
            folderStoreState,
            appState.activeAccountRef,
            controller.items,
            controller.archivedItems,
            groupTitleCopy,
            // Keyword rules match the rendered row title, which resolves as
            // peer profiles land — re-derive when the presentation cache bumps.
            appState.profileRevisionForCompose,
        ) {
            { folderId ->
                appState.activeAccountRef
                    ?.let { accountRef ->
                        // An archived-only folder draws from the archived
                        // list; every other folder from the active one.
                        val rule = appState.chatFolderPreferences.folderRule(accountRef, folderId)
                        val archivedSource = rule?.archivedOnly == true
                        val sourceItems = if (archivedSource) controller.archivedItems else controller.items
                        val engineMutedChatIds =
                            sourceItems
                                .asSequence()
                                .filter { it.engineMuted() }
                                .map { it.group.groupIdHex }
                                .toSet()
                        chatFolderChatIds(
                            items = sourceItems,
                            manualChatIds = appState.chatFolderPreferences.membershipFor(accountRef, folderId),
                            rule = rule,
                            activeAccountIdHex = appState.activeAccount?.accountIdHex,
                            isMuted = { groupIdHex ->
                                groupIdHex in engineMutedChatIds
                            },
                            displayTitle = { chatListItemDisplayTitle(it, appState, groupTitleCopy) },
                        )
                    }.orEmpty()
            }
        }
    val selectedFolderChatIds =
        remember(selectedFolder, resolveFolderChatIds) {
            selectedFolder?.let { resolveFolderChatIds(it.id) }
        }
    // An archived-only folder is a view switch as well as a filter: it swaps
    // the source list to archived chats (replacing the old Archived chip).
    val showArchived = selectedFolderRule?.archivedOnly == true
    val searchFocusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    /** Preserves the legacy direct-switch behavior for isolated screen owners. */
    fun switchAccountDirectly(label: String) {
        scope.launch { appState.setActiveAccount(label) }
    }
    val switchAccount: (String) -> Unit = onQuickSwitchAccount ?: ::switchAccountDirectly
    val context = LocalContext.current

    fun clearSelection() {
        selectedChatIds.clear()
    }

    // Lift the app-level toast host (it flows through WhiteNoiseSnackbarHost,
    // which reads LocalSnackbarBottomInset) above the quick-action FAB so a
    // toast — e.g. the archive confirmation — can't sit over the FAB and
    // intercept its taps for the toast's duration — issue #352. Resets to
    // zero on dispose so other surfaces aren't affected, mirroring
    // ConversationScreen's composer lift (#122).
    val snackbarBottomInset = LocalSnackbarBottomInset.current
    val snackbarContentInset = LocalSnackbarContentInset.current
    DisposableEffect(Unit) {
        snackbarBottomInset.value = FAB_SNACKBAR_INSET
        onDispose { snackbarBottomInset.value = 0.dp }
    }

    LaunchedEffect(searchOpen) {
        if (searchOpen) {
            // LaunchedEffect runs after composition + layout, so the
            // TextField node is already attached by the time we call
            // requestFocus — no explicit frame deferral needed.
            searchFocusRequester.requestFocus()
        }
    }
    LaunchedEffect(
        selectionMode,
        globalSearchState.filterSheetOpen,
        interactiveGlobalSearchFilterSectionsAvailable,
    ) {
        if (globalSearchPresentationState != globalSearchState) {
            onGlobalSearchStateChange { currentState ->
                reconcileGlobalSearchFilterSheet(
                    searchState = currentState,
                    interactiveSectionsAvailable = interactiveGlobalSearchFilterSectionsAvailable,
                    selectionMode = selectionMode,
                )
            }
        }
    }
    // Back from chat-list search unwinds search state — dismiss the filter sheet,
    // then close search (which resets query/filters per close rule) — instead of
    // exiting the app (#121, #149, #320). Selection mode takes priority (#1169).
    BackHandler(
        enabled =
            chatListBackHandlerEnabled(
                selectionMode,
                searchOpen,
                globalSearchPresentationState.filterSheetOpen,
            ),
    ) {
        when (chatListBackDismissal(selectionMode, globalSearchPresentationState)) {
            ChatListBackDismissal.ClearSelection -> clearSelection()
            ChatListBackDismissal.DismissFilterSheet ->
                onGlobalSearchStateChange(GlobalSearchTransitions::dismissFilterSheet)
            ChatListBackDismissal.CloseSearch ->
                onGlobalSearchStateChange(GlobalSearchTransitions::closeSearch)
            null -> Unit
        }
    }

    // System voice-input integration for the dictation button. The recognizer
    // is invoked via the standard `ACTION_RECOGNIZE_SPEECH` intent; on a
    // successful capture we paste the spoken text straight into the search
    // field. No permissions to declare ourselves — the system dialog handles
    // mic access on the user's behalf.
    val voiceSearchLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                val recognized =
                    result.data
                        ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
                        ?.firstOrNull()
                        ?.trim()
                if (!recognized.isNullOrBlank()) {
                    onGlobalSearchStateChange { state ->
                        GlobalSearchTransitions.setQuery(state, recognized)
                    }
                }
            }
        }

    val sourceList = if (showArchived) controller.archivedItems else controller.items
    LaunchedEffect(controller, controller.recoveryProjectionGeneration, sourceList) {
        val generation = controller.recoveryProjectionGeneration
        if (generation > 0L) {
            withFrameNanos { }
            appState.recoveryDiagnostics.recordFirstVisibleFrame(generation)
        }
    }
    val loadFailurePlacement = loadFailurePlacement(controller.error != null, sourceList.isNotEmpty())
    // Subscribing read of the profile-cache revision so the filter
    // re-runs when a DM peer's display name resolves — the title
    // projection inside `projectChatListSearchSections` reads
    // `appState.chatMemberTitle(...)`, but that read happens from a
    // `remember` block where Compose's snapshot system doesn't track
    // state. Keying on `profileRev` re-fires the filter when the
    // backing presentation cache invalidates.
    val profileRev = appState.profileRevisionForCompose
    // Debounced async message-body search. Key the expensive per-chat FFI fanout
    // by the query and stable group-id membership, not the live row list object:
    // unrelated chat-list republishes or row reordering must not restart a
    // full-corpus body search while the user is typing (#1201).
    val trimmedQuery = searchQuery.trim()
    val normalizedSearchQuery = remember(trimmedQuery) { localeInvariantFold(trimmedQuery) }
    val searchActive = trimmedQuery.isNotEmpty()
    val bodySearchGroupIds =
        remember(sourceList) {
            sourceList.map { canonicalChatListGroupId(it.id) }.distinct().sorted()
        }
    val bodySearchKey =
        remember(normalizedSearchQuery, controller.boundAccountRef, showArchived, bodySearchGroupIds) {
            ChatListBodySearchKey(
                query = normalizedSearchQuery,
                accountRef = controller.boundAccountRef,
                showArchived = showArchived,
                canonicalGroupIds = bodySearchGroupIds,
            )
        }
    // Use a request identity as well as the structural cache key. If a user
    // types A → B → A, the second A owns a fresh token, so the first A's
    // result cannot flash for one frame before this query's effect starts.
    val bodySearchRequest = remember(bodySearchKey) { ChatListBodySearchRequest() }
    val bodyMatches =
        bodySearchResult
            ?.takeIf { it.request === bodySearchRequest }
            ?.matches
            .orEmpty()
    LaunchedEffect(bodySearchRequest) {
        if (!searchActive) {
            return@LaunchedEffect
        }
        delay(CHAT_LIST_SEARCH_DEBOUNCE_MS)
        bodySearchResult =
            ChatListBodySearchResult(
                request = bodySearchRequest,
                matches = canonicalChatListBodyMatches(controller.searchMessageBodies(sourceList, trimmedQuery)),
            )
    }
    // Resolve a pasted Nostr identifier in the search field (#344). An npub is
    // validated (and normalized) via the FFI key parser — no network. A NIP-05
    // address shows a loading state, then a `/.well-known/nostr.json` lookup
    // resolves it to a pubkey. Each keystroke re-keys this effect, cancelling
    // any in-flight lookup, and a plain-text query resets to None so the list
    // filters as before. The resolve result drives ChatListIdentifierResult.
    LaunchedEffect(trimmedQuery) {
        when (val id = ChatListIdentifierSearch.classify(trimmedQuery)) {
            null -> identifierResolution = IdentifierResolution.None
            is ChatListIdentifierSearch.Identifier.Npub -> {
                // accountIdHex validates the bech32 key (rejecting a checksum
                // failure that the cheap format check in ProfileLink lets
                // through); a non-null result means the npub is real.
                val hex = appState.accountIdHex(id.npub)
                identifierResolution =
                    if (hex != null) {
                        IdentifierResolution.Resolved(id.npub)
                    } else {
                        IdentifierResolution.Failed(R.string.chat_list_search_invalid_npub)
                    }
            }
            is ChatListIdentifierSearch.Identifier.Nip05 -> {
                identifierResolution = IdentifierResolution.Resolving
                // Small debounce so a half-typed address (e.g. mid-domain)
                // doesn't fire a lookup on every keystroke; the effect re-keys
                // and cancels the prior attempt as the user keeps typing.
                delay(CHAT_LIST_SEARCH_DEBOUNCE_MS)
                val hex = Nip05Resolver.resolve(id.identifier)
                val npub = presentableIdentifierNpub(hex, appState::npubForDisplay)
                identifierResolution =
                    if (npub != null) {
                        IdentifierResolution.Resolved(npub)
                    } else {
                        IdentifierResolution.Failed(
                            R.string.chat_list_search_nip05_not_found,
                            id.identifier,
                        )
                    }
            }
        }
    }
    val chatListState = key(showArchived) { rememberLazyListState() }
    val userGestureGeneration = rememberChatListUserGestureGeneration(chatListState)
    var programmaticViewportGeneration by remember(chatListState) { mutableLongStateOf(0L) }
    val viewportGeneration = userGestureGeneration + programmaticViewportGeneration
    val searchSections =
        // Keyed on the normalized query: whitespace and case-only edits change
        // nothing the filter can see, so they must not re-run the O(n) pass.
        remember(sourceList, normalizedSearchQuery, selectedFolderChatIds, groupTitleCopy, profileRev, bodyMatches) {
            projectChatListSearchSections(
                source = sourceList,
                rawQuery = trimmedQuery,
                appState = appState,
                titleCopy = groupTitleCopy,
                bodyMatchGroupIds = bodyMatches.keys,
                folderChatIds = selectedFolderChatIds,
            )
        }
    val visibleItems = remember(searchSections) { searchSections.orderedItems() }

    fun visibleRowId(item: ChatListItem): String =
        if (searchActive) {
            canonicalChatListGroupId(item.id)
        } else {
            item.id
        }

    val visibleChatIds =
        remember(visibleItems, searchActive) {
            visibleItems.mapTo(mutableSetOf(), ::visibleRowId)
        }
    val orderedVisibleChatIds =
        remember(visibleItems, searchActive) {
            visibleItems.map(::visibleRowId)
        }
    val leadingChatListItemCount =
        if (controller.error != null && loadFailurePlacement == LoadFailurePlacement.Inline) 1 else 0
    val visiblePinnedOrder =
        remember(visibleItems, searchActive) {
            if (searchActive) emptyList() else visibleItems.filter { it.pinned() }.map { it.id }
        }
    val pinnedBoundary =
        remember(visibleItems, showArchived, searchActive) {
            if (searchActive) {
                null
            } else {
                pinnedBoundaryIndex(
                    pinnedStates = visibleItems.map(ChatListItem::pinned),
                    showArchived = showArchived,
                )
            }
        }
    val ordinaryActiveList = !showArchived && !searchActive
    var nextHeadDemotionTransactionId by remember { mutableLongStateOf(0L) }
    var pendingHeadDemotion by
        remember(
            appState.activeAccountRef,
            appState.runtimeGeneration,
            showArchived,
            selectedFolderId,
            trimmedQuery,
        ) {
            mutableStateOf<ChatListHeadDemotion?>(null)
        }
    val pendingHeadDemotionTarget =
        pendingHeadDemotion?.viewportAnchor?.let { anchor ->
            visibleItems.firstOrNull { it.id == anchor.chatId }
        }
    val pendingHeadDemotionSettled =
        pendingHeadDemotion != null &&
            visibleItems.none { it.id == pendingHeadDemotion?.chatId && it.pinned() }
    val pendingHeadDemotionTargetIndex =
        pendingHeadDemotionTarget
            ?.let { visibleItems.indexOf(it) }
            ?.let { rowIndex ->
                chatListHeadDemotionTargetIndex(
                    rowIndex = rowIndex,
                    pinnedBoundaryIndex = pinnedBoundary,
                    leadingItemCount = leadingChatListItemCount,
                )
            }

    fun openGroupFromVisibleList(
        item: ChatListItem,
        focusMessageId: String?,
        justCreated: Boolean,
    ) {
        val visibleHeadId = if (showArchived) null else visibleItems.firstOrNull()?.id
        onOpenGroup(item, focusMessageId, justCreated, visibleHeadId)
    }

    fun presentProfileFromVisibleList(npub: String) {
        val visibleHeadId = if (showArchived) null else visibleItems.firstOrNull()?.id
        onPresentProfile(npub, visibleHeadId)
    }
    LaunchedEffect(visibleChatIds, selectionMode) {
        if (selectionMode) {
            selectedChatIds.retainAll(reconcileChatListSelection(selectedChatIds, visibleChatIds))
        }
    }
    LaunchedEffect(visibleChatIds, actionSheetChatId) {
        if (actionSheetChatId != null && actionSheetChatId !in visibleChatIds) actionSheetChatId = null
    }
    val selectedVisibleItems =
        remember(visibleItems, searchActive, selectedChatIds.size) {
            visibleItems.filter { visibleRowId(it) in selectedChatIds }
        }
    val bulkArchiveAction =
        remember(selectedVisibleItems) {
            chatListBulkArchiveAction(selectedVisibleItems.map { it.group.archived })
        }
    val singleSelectedItem = selectedVisibleItems.singleOrNull()
    // Engine-normalized manual order of the pinned block, for the selection
    // bar's move actions. Ids keep the projection's casing — they round-trip
    // straight back into setPinnedChatOrder, which must receive the pinned
    // set exactly. Active items suffice: the engine only pins unarchived
    // chats and clears a pin on archive, so no archived row can be pinned.
    val pinnedOrderedIds =
        remember(controller.items) {
            controller.items
                .filter { it.pinned() }
                .sortedBy { it.pinnedPosition()?.toLong() ?: Long.MAX_VALUE }
                .map { it.projection?.groupIdHex ?: it.group.groupIdHex }
        }

    fun pinnedIndex(item: ChatListItem): Int? =
        item
            .takeIf { it.pinned() }
            ?.let { pinnedItem ->
                pinnedOrderedIds.indexOfFirst {
                    it.equals(pinnedItem.group.groupIdHex, ignoreCase = true)
                }
            }?.takeIf { it >= 0 }

    val singleSelectedPinnedIndex = singleSelectedItem?.let(::pinnedIndex)
    val singleSelectionMuted =
        singleSelectedItem?.let { item ->
            item.engineMuted()
        } ?: false

    fun archiveChats(
        items: List<ChatListItem>,
        archive: Boolean,
    ) {
        if (items.isEmpty()) return
        clearSelection()
        appState.launchMutation {
            val succeeded =
                controller.setArchived(
                    groupIds = items.map { it.group.groupIdHex },
                    archived = archive,
                    notify = false,
                )
            if (succeeded > 0) {
                val pluralRes =
                    if (archive) {
                        R.plurals.toast_chat_list_chats_archived
                    } else {
                        R.plurals.toast_chat_list_chats_restored
                    }
                appState.presentTransient(context.resources.getQuantityString(pluralRes, succeeded, succeeded))
            }
        }
    }

    fun openFolderPicker(items: List<ChatListItem>) {
        folderHandoff.pickerChatIds =
            items
                .map { it.group.groupIdHex.lowercase(Locale.ROOT) }
                .takeIf { it.isNotEmpty() }
    }

    fun markChatRead(
        item: ChatListItem,
        unread: Boolean,
    ) {
        clearSelection()
        appState.launchMutation {
            if (unread) controller.markUnread(item) else controller.markAllRead(item)
        }
    }

    fun toggleChatMute(
        item: ChatListItem,
        muted: Boolean,
    ) {
        clearSelection()
        appState.setConversationMuted(item.group.groupIdHex, !muted)
    }

    fun toggleChatPin(item: ChatListItem) {
        if (item.group.archived) return
        val nextPinned = !item.pinned()
        val headDemotion =
            if (!nextPinned && ordinaryActiveList && visibleItems.firstOrNull()?.id == item.id) {
                nextHeadDemotionTransactionId += 1L
                ChatListHeadDemotion(
                    chatId = item.id,
                    transactionId = nextHeadDemotionTransactionId,
                    viewportAnchor = chatListState.chatListViewportAnchor(visibleChatIds),
                    viewportGeneration = viewportGeneration,
                )
            } else {
                null
            }
        if (headDemotion != null) {
            pendingHeadDemotion = headDemotion
        } else if (nextPinned && pendingHeadDemotion?.chatId == item.id) {
            pendingHeadDemotion = null
        }
        clearSelection()
        appState.launchMutation {
            val succeeded = controller.setPinned(item, nextPinned)
            if (!succeeded && pendingHeadDemotion == headDemotion) {
                pendingHeadDemotion = null
            }
        }
    }

    fun movePinnedChat(
        item: ChatListItem,
        delta: Int,
    ) {
        val index = pinnedIndex(item) ?: return
        val target = index + delta
        if (target !in pinnedOrderedIds.indices) return
        val reordered =
            pinnedOrderedIds.toMutableList().also { ids ->
                val id = ids.removeAt(index)
                ids.add(target, id)
            }
        clearSelection()
        appState.launchMutation { controller.setPinnedOrder(reordered) }
    }

    // Hoisted list state so the jump-to-top FAB (issue #413) can both read the
    // scroll position for its visibility predicate and drive the animated
    // scroll-to-top on tap. Archived/active sources own separate state. Folder
    // and search datasets retain this state so LazyColumn can preserve a valid
    // keyed anchor and animate shared rows without an unsolicited scroll.
    val chatListDatasetKey =
        ChatListDatasetKey(
            showArchived = showArchived,
            folderId = selectedFolderId,
            query = normalizedSearchQuery,
            accountRef = appState.activeAccountRef,
            runtimeGeneration = appState.runtimeGeneration,
        )
    ChatListSearchTopResetEffect(
        listState = chatListState,
        datasetKey = chatListDatasetKey,
        searchActive = searchActive,
        onScrollRequested = { programmaticViewportGeneration += 1L },
    )
    var headScrollCorrectionInProgress by remember(chatListDatasetKey) { mutableStateOf(false) }
    val density = LocalDensity.current
    val dragEdgeThresholdPx = with(density) { 56.dp.toPx() }
    val dragMaxScrollStepPx = with(density) { 18.dp.toPx() }
    var chatListWindowTop by
        remember(appState.activeAccountRef, appState.runtimeGeneration) { mutableFloatStateOf(0f) }
    var chatListHeightPx by
        remember(appState.activeAccountRef, appState.runtimeGeneration) { mutableFloatStateOf(0f) }
    var dragAnchorChatId by
        remember(appState.activeAccountRef, appState.runtimeGeneration) { mutableStateOf<String?>(null) }
    var dragPointerWindowY by
        remember(appState.activeAccountRef, appState.runtimeGeneration) { mutableStateOf<Float?>(null) }

    @Suppress("ReturnCount") // Guard clauses keep invalid live-list gesture state explicit.
    fun updateChatDragSelection(pointerWindowY: Float): Boolean {
        val anchorId = dragAnchorChatId ?: return false
        val pointerY = pointerWindowY - chatListWindowTop
        val endpointId =
            dragSelectionEndpoint(
                chatListState.layoutInfo.visibleItemsInfo.mapNotNull { visible ->
                    val id = visible.key as? String
                    id
                        ?.takeIf(visibleChatIds::contains)
                        ?.let {
                            DragSelectionVisibleItem(
                                key = it,
                                start = visible.offset.toFloat(),
                                end = (visible.offset + visible.size).toFloat(),
                            )
                        }
                },
                pointerY,
            ) ?: return false
        if (endpointId == anchorId && selectedChatIds.isEmpty()) return false
        val next =
            anchoredDragSelection(
                orderedIds = orderedVisibleChatIds,
                eligibleIds = visibleChatIds,
                anchorId = anchorId,
                endpointId = endpointId,
            )
        selectedChatIds.clear()
        selectedChatIds.addAll(next)
        return true
    }

    fun finishChatDrag(clearSelection: Boolean) {
        dragAnchorChatId = null
        dragPointerWindowY = null
        if (clearSelection) selectedChatIds.clear()
    }

    LaunchedEffect(appState.activeAccountRef, appState.runtimeGeneration) {
        finishChatDrag(clearSelection = true)
        actionSheetChatId = null
    }
    LaunchedEffect(chatListDatasetKey) {
        if (dragAnchorChatId != null) finishChatDrag(clearSelection = true)
    }
    LaunchedEffect(visibleChatIds) {
        val anchorId = dragAnchorChatId ?: return@LaunchedEffect
        if (anchorId !in visibleChatIds) finishChatDrag(clearSelection = true)
    }

    LaunchedEffect(dragAnchorChatId, chatListState) {
        var autoScrollActive = false
        while (dragAnchorChatId != null) {
            withFrameNanos { }
            val pointerWindowY = dragPointerWindowY ?: continue
            val pointerY = pointerWindowY - chatListWindowTop
            val scrollDelta =
                dragSelectionAutoScrollDelta(
                    pointerY = pointerY,
                    viewportStart = 0f,
                    viewportEnd = chatListHeightPx,
                    edgeThreshold = dragEdgeThresholdPx,
                    maxStep = dragMaxScrollStepPx,
                )
            if (scrollDelta != 0f) {
                if (chatListAutoScrollSessionStarts(autoScrollActive, scrollDelta)) {
                    programmaticViewportGeneration += 1L
                }
                autoScrollActive = true
                chatListState.scrollBy(scrollDelta)
                updateChatDragSelection(pointerWindowY)
            } else {
                autoScrollActive = false
            }
        }
    }
    // Hysteresis for the jump-to-top button: show once the user is ≥ 5 rows
    // deep, hide only after they climb back to ≤ 2. The 3–4 dead band keeps a
    // quick scroll wiggle near the threshold from toggling the button (issue
    // #413). The previous decision keeps the band sticky.
    var jumpToTopVisible by remember(showArchived) { mutableStateOf(false) }
    // Observe scroll-index changes in an effect so the whole screen does not
    // subscribe to every LazyColumn index update during a fling.
    LaunchedEffect(chatListState) {
        snapshotFlow { chatListState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { firstVisibleIndex ->
                if (firstVisibleIndex >= CHAT_LIST_JUMP_TO_TOP_SHOW_INDEX) {
                    jumpToTopVisible = true
                } else if (firstVisibleIndex <= CHAT_LIST_JUMP_TO_TOP_HIDE_INDEX) {
                    jumpToTopVisible = false
                }
            }
    }
    // Keep a new chat-list head flush at the top when live activity reorders
    // keyed items (issues #541 / #1313 / #1651). LazyColumn otherwise pins the
    // previous head by key, leaving the promoted row above or clipped by the
    // viewport.
    //
    // Two paths, both via [ChatListHeadSnap]:
    //   - Return from a conversation: if the head changed while the list was
    //     off-screen, snap regardless of the restored scroll index (#1313).
    //   - Active on-list reader: animate the one-row correction only from the
    //     true top so both keyed rows move together; never yank a deeper reader
    //     or interrupt active scrolling (#541 / #1651).
    // Keyed on `showArchived` so the tracked head resets alongside
    // `chatListState` on a view swap.
    val activeHeadId = visibleItems.firstOrNull()?.id.takeIf { ordinaryActiveList }
    LaunchedEffect(conversationReturnHeadId, activeHeadId, ordinaryActiveList) {
        val headAtConversationOpen = conversationReturnHeadId ?: return@LaunchedEffect
        snapshotFlow {
            canDecideConversationReturnHeadSnap(
                headIdAtConversationOpen = headAtConversationOpen,
                currentHeadId = activeHeadId,
                isScrollInProgress = chatListState.isScrollInProgress,
                isActiveList = ordinaryActiveList,
            )
        }.first { it }
        if (
            shouldSnapChatListOnConversationReturn(
                headIdAtConversationOpen = headAtConversationOpen,
                currentHeadId = activeHeadId,
                isActiveList = ordinaryActiveList,
            )
        ) {
            programmaticViewportGeneration += 1L
            chatListState.scrollToItem(0)
        }
        onConversationReturnHeadHandled()
    }
    ChatListActiveHeadScrollEffect(
        listState = chatListState,
        activeHeadId = activeHeadId,
        pinnedOrder = visiblePinnedOrder,
        datasetKey = chatListDatasetKey,
        isActiveList = ordinaryActiveList,
        userHeadDemotion = pendingHeadDemotion,
        userHeadDemotionSettled = pendingHeadDemotionSettled,
        userHeadDemotionTargetIndex = pendingHeadDemotionTargetIndex,
        viewportGeneration = viewportGeneration,
        onUserHeadDemotionConsumed = { consumed ->
            if (pendingHeadDemotion == consumed) pendingHeadDemotion = null
        },
        onHeadReorderInProgressChange = { inProgress ->
            if (inProgress && !headScrollCorrectionInProgress) programmaticViewportGeneration += 1L
            headScrollCorrectionInProgress = inProgress
        },
    )
    val headReorderInProgress =
        rememberChatListHeadReorderGate(
            activeHeadId = activeHeadId,
            datasetKey = chatListDatasetKey,
            isActiveList = ordinaryActiveList,
            scrollCorrectionInProgress = headScrollCorrectionInProgress,
        )
    val headPromotionMotionEligible by
        remember(chatListState, ordinaryActiveList) {
            derivedStateOf {
                ordinaryActiveList &&
                    chatListState.firstVisibleItemIndex == 0 &&
                    !chatListState.isScrollInProgress
            }
        }
    val rowPlacementDurationMillis =
        rememberChatListRowPlacementDurationMillis(
            orderedRowIds = if (searchActive) emptyList() else orderedVisibleChatIds,
            pinnedBoundaryIndex = pinnedBoundary.takeUnless { searchActive },
            datasetKey = chatListDatasetKey,
            headPromotionEligible = headPromotionMotionEligible,
        )
    val rowPlacementInProgress =
        rememberChatListRowPlacementGate(
            orderedRowIds = if (searchActive) emptyList() else orderedVisibleChatIds,
            pinnedBoundaryIndex = pinnedBoundary.takeUnless { searchActive },
            leadingItemCount = leadingChatListItemCount.takeUnless { searchActive } ?: 0,
            placementDurationMillis = rowPlacementDurationMillis,
        )
    val chatListInteractionsEnabled = !headReorderInProgress && !rowPlacementInProgress
    val archivedUnreadCount =
        remember(controller.archivedItems) {
            controller.archivedItems.count { it.hasUnread }
        }
    val activeUnreadCount =
        remember(controller.items) {
            controller.items.count { it.hasUnread }
        }
    val pendingFolderIds =
        remember(
            accountFolders,
            folderStoreState,
            appState.activeAccountRef,
            controller.items,
            controller.archivedItems,
            controller.memberSnapshotsRevision,
        ) {
            accountFolders
                .mapNotNullTo(mutableSetOf()) { folder ->
                    val accountRef = appState.activeAccountRef ?: return@mapNotNullTo null
                    val rule = appState.chatFolderPreferences.folderRule(accountRef, folder.id)
                    val source = if (rule?.archivedOnly == true) controller.archivedItems else controller.items
                    folder.id.takeIf { memberBasedFolderPending(rule, source) }
                }
        }
    val folderChipModels =
        remember(
            accountFolders,
            controller.items,
            controller.archivedItems,
            resolveFolderChatIds,
            pendingFolderIds,
            selectedFolderId,
        ) {
            chatFolderChipModels(
                folders = accountFolders,
                activeItems = controller.items,
                archivedItems = controller.archivedItems,
                activeAccountIdHex = appState.activeAccount?.accountIdHex,
                ruleOf = { folderId ->
                    appState.activeAccountRef?.let { appState.chatFolderPreferences.folderRule(it, folderId) }
                },
                membershipOf = resolveFolderChatIds,
                pendingFolderIds = pendingFolderIds,
                selectedFolderId = selectedFolderId,
            )
        }
    // An empty selected folder remains visible and active until the user picks
    // another chip. Clear only when the configured folder itself is deleted.
    LaunchedEffect(accountFolders, selectedFolderId) {
        if (selectedFolderId != null && accountFolders.none { it.id == selectedFolderId }) {
            onSelectFolder(null)
        }
    }

    if (showNewChatFlow) {
        NewChatFlowHost(
            appState = appState,
            onOpenConversation = { item, justCreated ->
                showNewChatFlow = false
                openGroupFromVisibleList(item, null, justCreated)
            },
            onClose = {
                showNewChatFlow = false
                onGroupCreateFlowSuperseded()
            },
            onGroupCreateSubmitted = onGroupCreateSubmitted,
            onGroupCreateCompletedOpen = { item, requestToken ->
                showNewChatFlow = false
                onGroupCreateCompletedOpen(item, requestToken)
            },
        )
        return
    }

    // Folder editor handoff: create-from-selection (folderId = null) or
    // long-press edit on a chip (folderId set). Same in-place swap as the
    // new-chat flow so filter/search/list state survives close/save.
    val folderEditorTargets = folderHandoff.editorChatIds
    val folderEditorAccountRef = appState.activeAccountRef
    val folderEditId = folderHandoff.editingFolderId
    if (folderEditorAccountRef != null && (folderEditorTargets != null || folderEditId != null)) {
        ChatFolderEditScreen(
            appState = appState,
            accountRef = folderEditorAccountRef,
            folderId = folderEditId,
            onClose = {
                folderHandoff.editorChatIds = null
                folderHandoff.editingFolderId = null
            },
            initialManualChatIds = folderEditorTargets.orEmpty(),
        )
        return
    }

    val chatRowContent: @Composable LazyItemScope.(ChatListItem, Int, MessageBodyMatch?) -> Unit =
        { item, targetIndex, bodyMatch ->
            val rowId = visibleRowId(item)
            Box(
                modifier =
                    if (searchActive) {
                        Modifier
                    } else {
                        chatListRowMotion(targetIndex, rowPlacementDurationMillis)
                    },
            ) {
                ChatListRow(
                    item = item,
                    appState = appState,
                    accountRef = controller.boundAccountRef,
                    isMuted = item.engineMuted(),
                    interactionsEnabled = chatListInteractionsEnabled,
                    selectionMode = selectionMode,
                    selected = rowId in selectedChatIds,
                    bodyMatch = bodyMatch,
                    onOpen = { openGroupFromVisibleList(item, bodyMatch?.messageIdHex, false) },
                    onOpenProfile = { npub -> presentProfileFromVisibleList(npub) },
                    onOpenActions = {
                        actionSheetChatId = rowId
                    },
                    onDragSelectionStart = { pointerWindowY ->
                        actionSheetChatId = null
                        dragAnchorChatId = rowId
                        dragPointerWindowY = pointerWindowY
                    },
                    onDragSelection = { pointerWindowY ->
                        dragPointerWindowY = pointerWindowY
                        updateChatDragSelection(pointerWindowY)
                    },
                    onDragSelectionEnd = { finishChatDrag(clearSelection = false) },
                    onDragSelectionCancel = {
                        actionSheetChatId = null
                        finishChatDrag(clearSelection = true)
                    },
                    rangeDragActive = dragAnchorChatId == rowId,
                    onToggleSelection = {
                        val updated = toggleChatListSelection(selectedChatIds, rowId)
                        selectedChatIds.clear()
                        selectedChatIds.addAll(updated)
                    },
                )
            }
        }

    Scaffold(
        topBar = {
            val connectivityState = rememberChatListConnectivityState(appState, controller)
            Column {
                if (selectionMode) {
                    ChatListSelectionBar(
                        count = selectedChatIds.size,
                        archiveAction = bulkArchiveAction,
                        actionsEnabled = selectedChatIds.isNotEmpty(),
                        allVisibleSelected = visibleChatIds.isNotEmpty() && selectedChatIds.containsAll(visibleChatIds),
                        showMarkRead =
                            singleSelectedItem?.effectiveHasUnread(appState.activeAccount?.accountIdHex) == true,
                        showMarkUnread =
                            singleSelectedItem?.removedFromGroup(appState.activeAccount?.accountIdHex) == false &&
                                singleSelectedItem.effectiveHasUnread(appState.activeAccount?.accountIdHex) != true,
                        showMuteToggle = singleSelectedItem != null,
                        muted = singleSelectionMuted,
                        // The engine only pins unarchived chats, so an archived
                        // selection gets no pin affordance instead of a
                        // silently failing one.
                        showPinToggle = singleSelectedItem?.group?.archived == false,
                        pinned = singleSelectedItem?.pinned() == true,
                        showMovePinnedUp = (singleSelectedPinnedIndex ?: 0) > 0,
                        showMovePinnedDown =
                            singleSelectedPinnedIndex != null &&
                                singleSelectedPinnedIndex < pinnedOrderedIds.lastIndex,
                        onClose = ::clearSelection,
                        onArchive = {
                            val selected = selectedVisibleItems
                            if (selected.isEmpty()) return@ChatListSelectionBar
                            val archive = bulkArchiveAction == ChatListBulkArchiveAction.Archive
                            archiveChats(selected, archive)
                        },
                        onDelete = {
                            pendingBulkDelete = selectedVisibleItems.takeIf { it.isNotEmpty() }
                        },
                        onAddToFolder = {
                            openFolderPicker(selectedVisibleItems)
                        },
                        onMarkRead = {
                            val item = singleSelectedItem ?: return@ChatListSelectionBar
                            markChatRead(item, unread = false)
                        },
                        onMarkUnread = {
                            val item = singleSelectedItem ?: return@ChatListSelectionBar
                            markChatRead(item, unread = true)
                        },
                        onMuteToggle = {
                            val item = singleSelectedItem ?: return@ChatListSelectionBar
                            toggleChatMute(item, singleSelectionMuted)
                        },
                        onPinToggle = {
                            val item = singleSelectedItem ?: return@ChatListSelectionBar
                            toggleChatPin(item)
                        },
                        onMovePinned = { delta ->
                            val item = singleSelectedItem ?: return@ChatListSelectionBar
                            movePinnedChat(item, delta)
                        },
                        onSelectAll = { selectedChatIds.addAll(selectAllVisibleChats(visibleChatIds)) },
                        onDeselectAll = { selectedChatIds.clear() },
                    )
                } else {
                    ChatListTopBar(
                        appState = appState,
                        searchOpen = searchOpen,
                        searchQuery = searchQuery,
                        searchFocusRequester = searchFocusRequester,
                        onSearchQueryChange = { query ->
                            onGlobalSearchStateChange { state -> GlobalSearchTransitions.setQuery(state, query) }
                        },
                        onSearchOpen = {
                            onGlobalSearchStateChange(GlobalSearchTransitions::openSearch)
                        },
                        onSearchClose = {
                            onGlobalSearchStateChange(GlobalSearchTransitions::closeSearch)
                        },
                        onSwitchAccount = switchAccount,
                        onMic = {
                            val intent =
                                android.content
                                    .Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                    .putExtra(
                                        android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                        android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                                    )
                            // ActivityNotFoundException fires on devices without
                            // any RecognizerIntent handler (rare on consumer
                            // hardware; possible on AOSP forks or kiosk-mode
                            // devices). Surface that as a toast instead of
                            // swallowing — otherwise the mic tap is silent.
                            try {
                                voiceSearchLauncher.launch(intent)
                            } catch (_: android.content.ActivityNotFoundException) {
                                appState.present(R.string.chat_list_voice_unavailable)
                            }
                        },
                        onOpenSettings = onOpenSettings,
                        connectivityState = connectivityState,
                    )
                }
            }
        },
        floatingActionButton = {
            if (!searchOpen && !selectionMode) {
                val actionColors = accountActionColors(appState)
                FloatingActionButton(
                    onClick = openNewMessageFlow,
                    modifier = Modifier.performanceTestTag(PerformanceTestTags.NEW_MESSAGE),
                    containerColor = actionColors.container,
                    contentColor = actionColors.content,
                ) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = stringResource(R.string.new_message),
                    )
                }
            }
        },
    ) { padding ->
        GlobalSearchFilterSheet(
            visible =
                shouldPresentGlobalSearchFilterSheet(
                    searchState = globalSearchState,
                    interactiveSectionsAvailable = interactiveGlobalSearchFilterSectionsAvailable,
                    selectionMode = selectionMode,
                ),
            onDismiss = {
                onGlobalSearchStateChange(GlobalSearchTransitions::dismissFilterSheet)
            },
        )
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (appState.appUpdateInfo.shouldShowBanner) {
                AppUpdateBanner(
                    info = appState.appUpdateInfo,
                    onUpdateNow = { appState.handleAppUpdateAction(context) },
                    onDismiss = { appState.dismissAppUpdateBanner() },
                )
            }
            // Filter chips visible whenever there's content to filter — both
            // in the active and archived lists. They're sticky above the
            // list rather than sticky inside the LazyColumn so they survive
            // an empty-state swap without flicker.
            if (controller.items.isNotEmpty() || controller.archivedItems.isNotEmpty()) {
                ChatListFilterChips(
                    chips = folderChipModels,
                    selectedFolderId = selectedFolderId,
                    onSelect = onSelectFolder,
                    onEditFolder = { folderHandoff.editingFolderId = it },
                )
            }
            if (
                shouldShowGlobalSearchFilterControls(
                    searchState = globalSearchState,
                    interactiveSectionsAvailable = interactiveGlobalSearchFilterSectionsAvailable,
                    selectionMode = selectionMode,
                )
            ) {
                GlobalSearchFilterControlsRow(
                    state = globalSearchState,
                    onOpenFilters =
                        if (interactiveGlobalSearchFilterSectionsAvailable) {
                            {
                                onGlobalSearchStateChange(GlobalSearchTransitions::openFilterSheet)
                            }
                        } else {
                            null
                        },
                    onRemoveFilter = { chipId ->
                        onGlobalSearchStateChange { state -> GlobalSearchTransitions.removeFilter(state, chipId) }
                    },
                    onClearAll = {
                        onGlobalSearchStateChange(GlobalSearchTransitions::clearAllFilters)
                    },
                )
            }
            // Pasted-identifier resolution result (#344). Sits above the list so
            // a recognized npub / NIP-05 surfaces a tappable result while
            // plain-text queries below keep filtering the list. When the active
            // account already has a 1:1 chat with the resolved key, the result
            // IS that chat row and tapping it opens the conversation directly
            // (issue #344 step 2); otherwise it's an "open profile" affordance
            // routing into the shared profile sheet, which handles the
            // Start-chat path (step 3). Hidden for plain-text queries.
            ChatListIdentifierResult(
                resolution = identifierResolution,
                appState = appState,
                accountRef = controller.boundAccountRef,
                existingDirectChat = { npub -> appState.existingDirectChat(npub) },
                onOpenChat = { chat ->
                    openGroupFromVisibleList(chat, null, false)
                },
                onOpenProfile = { npub ->
                    presentProfileFromVisibleList(npub)
                },
            )
            ChatListBodyFrame(
                modifier = Modifier.fillMaxSize(),
                ttsTransport = {
                    TtsTransportBar(
                        appState = appState,
                        onBodyClick = onTtsTransportBodyClick,
                    )
                },
            ) {
                when {
                    controller.isLoading && sourceList.isEmpty() -> LoadingScreen()
                    loadFailurePlacement == LoadFailurePlacement.FullScreen ->
                        ErrorContent(
                            stringResource(R.string.couldnt_load_chats),
                            requireNotNull(controller.error),
                            onRetry = controller::retryLoad,
                        )
                    sourceList.isEmpty() && showArchived ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                stringResource(R.string.no_archived_chats),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    sourceList.isEmpty() ->
                        EmptyChats(onCreate = openNewMessageFlow)
                    visibleItems.isEmpty() && identifierResolution != IdentifierResolution.None ->
                        controller.error
                            ?.takeIf { loadFailurePlacement == LoadFailurePlacement.Inline }
                            ?.let { failure ->
                                InlineErrorBanner(
                                    error = failure,
                                    onRetry = controller::retryLoad,
                                    modifier = Modifier.align(Alignment.TopCenter),
                                )
                            }
                    visibleItems.isEmpty() ->
                        Column(Modifier.fillMaxSize()) {
                            controller.error
                                ?.takeIf { loadFailurePlacement == LoadFailurePlacement.Inline }
                                ?.let { failure ->
                                    InlineErrorBanner(
                                        error = failure,
                                        onRetry = controller::retryLoad,
                                    )
                                }
                            Box(Modifier.fillMaxWidth().weight(1f)) {
                                ChatListNoResults(
                                    query = searchQuery.trim(),
                                    unreadFolderSelected = selectedFolderRule?.unreadOnly == true,
                                )
                            }
                        }
                    else ->
                        LazyColumn(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .clipToBounds()
                                    .cancelPointersAcrossChatListMotion(chatListInteractionsEnabled)
                                    .onGloballyPositioned { coordinates ->
                                        chatListWindowTop = coordinates.positionInWindow().y
                                        chatListHeightPx = coordinates.size.height.toFloat()
                                    },
                            state = chatListState,
                            contentPadding = PaddingValues(bottom = snackbarContentInset.value),
                        ) {
                            controller.error
                                ?.takeIf { loadFailurePlacement == LoadFailurePlacement.Inline }
                                ?.let { failure ->
                                    item(key = "chat-list-load-error") {
                                        InlineErrorBanner(
                                            error = failure,
                                            onRetry = controller::retryLoad,
                                        )
                                    }
                                }
                            if (searchActive) {
                                if (searchSections.groups.isNotEmpty()) {
                                    item(
                                        key = CHAT_LIST_SEARCH_GROUPS_HEADER_KEY,
                                        contentType = CHAT_LIST_SEARCH_HEADER_CONTENT_TYPE,
                                    ) {
                                        ChatListSearchSectionHeader(
                                            title = stringResource(R.string.chat_list_filter_groups),
                                            testTag = CHAT_LIST_SEARCH_GROUPS_HEADER_TAG,
                                        )
                                    }
                                }
                                searchSections.groups.forEachIndexed { targetIndex, item ->
                                    this.item(
                                        key = visibleRowId(item),
                                        contentType = CHAT_LIST_ROW_CONTENT_TYPE,
                                    ) {
                                        chatRowContent(item, targetIndex, null)
                                    }
                                }
                                if (searchSections.messages.isNotEmpty()) {
                                    item(
                                        key = CHAT_LIST_SEARCH_MESSAGES_HEADER_KEY,
                                        contentType = CHAT_LIST_SEARCH_HEADER_CONTENT_TYPE,
                                    ) {
                                        ChatListSearchSectionHeader(
                                            title = stringResource(R.string.notification_channel_messages),
                                            testTag = CHAT_LIST_SEARCH_MESSAGES_HEADER_TAG,
                                        )
                                    }
                                }
                                searchSections.messages.forEachIndexed { messageIndex, item ->
                                    val canonicalId = visibleRowId(item)
                                    this.item(
                                        key = canonicalId,
                                        contentType = CHAT_LIST_ROW_CONTENT_TYPE,
                                    ) {
                                        val bodyMatch = bodyMatches[canonicalId]
                                        chatRowContent(item, searchSections.groups.size + messageIndex, bodyMatch)
                                    }
                                }
                            } else {
                                visibleItems.forEachIndexed { targetIndex, item ->
                                    if (targetIndex == pinnedBoundary) {
                                        this.item(
                                            key = CHAT_LIST_PINNED_BOUNDARY_KEY,
                                            contentType = CHAT_LIST_PINNED_BOUNDARY_CONTENT_TYPE,
                                        ) {
                                            ChatListPinnedBoundary()
                                        }
                                    }
                                    this.item(key = item.id, contentType = CHAT_LIST_ROW_CONTENT_TYPE) {
                                        chatRowContent(item, targetIndex, null)
                                    }
                                }
                            }
                        }
                }
                // Jump-to-top FAB (issue #413). Overlaid on the chat-list Box
                // (a sibling of the list `when`) and aligned bottom-end so it
                // floats above the rows. Fades + slides in from the bottom-right
                // when the user is scrolled deep, hides near the top — the
                // `jumpToTopVisible` hysteresis above debounces threshold
                // jitter. Lifted by FAB_SNACKBAR_INSET (the same clearance the
                // snackbar host uses, #352/#356) on top of the navigation-bar
                // inset so it clears the quick-action FAB and the nav bar.
                // Fully-qualified so Kotlin binds the top-level overload rather
                // than the ColumnScope extension (the outer Column is also an
                // implicit receiver here) — the bottom-end alignment is carried
                // by the Modifier in the BoxScope, not a scoped AnimatedVisibility.
                androidx.compose.animation.AnimatedVisibility(
                    visible = jumpToTopVisible && !selectionMode,
                    enter = fadeIn() + slideInHorizontally { it / 2 },
                    exit = fadeOut() + slideOutHorizontally { it / 2 },
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .navigationBarsPadding()
                            // end = 23dp (not 16) so this 42dp control's centre
                            // lines up with the 56dp quick-action FAB above it,
                            // which the Scaffold insets 16dp from the edge:
                            // 23 + 42/2 == 16 + 56/2 (#451).
                            .padding(end = 23.dp, bottom = FAB_SNACKBAR_INSET),
                ) {
                    val scrollToTopLabel = stringResource(R.string.scroll_to_top)
                    Box(
                        modifier =
                            Modifier
                                .size(42.dp)
                                .semantics { contentDescription = scrollToTopLabel }
                                .clickable(role = Role.Button) {
                                    scope.launch {
                                        programmaticViewportGeneration += 1L
                                        // For a very deep scroll, animating every row to
                                        // the top is a multi-second crawl. Snap to a
                                        // near-top index first, then animate the final
                                        // viewport so the motion stays short and smooth
                                        // regardless of how far down the user was.
                                        if (chatListState.firstVisibleItemIndex > CHAT_LIST_JUMP_TO_TOP_SNAP_INDEX) {
                                            chatListState.scrollToItem(CHAT_LIST_JUMP_TO_TOP_SNAP_INDEX)
                                        }
                                        chatListState.animateScrollToItem(0)
                                    }
                                },
                        contentAlignment = Alignment.Center,
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            shadowElevation = 2.dp,
                            modifier = Modifier.size(34.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.ArrowUpward,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    actionSheetChatId
        ?.let { id -> visibleItems.firstOrNull { visibleRowId(it) == id } }
        ?.let { item ->
            val hasUnread = item.effectiveHasUnread(appState.activeAccount?.accountIdHex)
            val muted = item.engineMuted()
            val pinnedIndex = pinnedIndex(item)
            ChatActionSheet(
                hasUnread = hasUnread,
                canMarkUnread = !item.removedFromGroup(appState.activeAccount?.accountIdHex),
                archived = item.group.archived,
                muted = muted,
                pinned = item.pinned(),
                showPinToggle = !item.group.archived,
                showMovePinnedUp = (pinnedIndex ?: 0) > 0,
                showMovePinnedDown = pinnedIndex != null && pinnedIndex < pinnedOrderedIds.lastIndex,
                onMarkRead = { markChatRead(item, unread = false) },
                onMarkUnread = { markChatRead(item, unread = true) },
                onAddToFolder = { openFolderPicker(listOf(item)) },
                onArchiveToggle = { archiveChats(listOf(item), archive = !item.group.archived) },
                onMuteToggle = { toggleChatMute(item, muted) },
                onPinToggle = { toggleChatPin(item) },
                onMovePinned = { delta -> movePinnedChat(item, delta) },
                onSelect = {
                    selectedChatIds.clear()
                    selectedChatIds.addAll(enterChatListSelection(visibleRowId(item)))
                },
                onDelete = { pendingBulkDelete = listOf(item) },
                onDismiss = { actionSheetChatId = null },
            )
        }

    folderHandoff.pickerChatIds?.let { targets ->
        ChatFolderPickerSheet(
            appState = appState,
            targetChatIds = targets,
            onCreateFolder = {
                folderHandoff.pickerChatIds = null
                folderHandoff.editorChatIds = targets.toSet()
                clearSelection()
            },
            onDismiss = { folderHandoff.pickerChatIds = null },
        )
    }

    pendingBulkDelete?.let { items ->
        ChatDeleteConfirmationDialog(
            count = items.size,
            onConfirm = {
                pendingBulkDelete = null
                clearSelection()
                appState.launchMutation {
                    var succeeded = 0
                    items.forEach { item ->
                        if (controller.deleteGroupLocalFromChatList(item.group.groupIdHex, notify = false)) {
                            succeeded++
                        }
                    }
                    if (succeeded > 0) {
                        appState.presentTransient(
                            context.resources.getQuantityString(
                                R.plurals.toast_chat_list_chats_deleted,
                                succeeded,
                                succeeded,
                            ),
                        )
                    }
                }
            },
            onDismiss = { pendingBulkDelete = null },
        )
    }
}

/**
 * Resolution state for a chat-list search query that parsed as a Nostr
 * identifier (npub / NIP-05) — issue #344. An npub resolves synchronously to
 * its key; a NIP-05 address has to be looked up over the network, so it passes
 * through [Resolving] before landing on [Resolved] or [Failed]. [None] is the
 * ordinary plain-text-query case where the chat list filters as before.
 */
private sealed interface IdentifierResolution {
    data object None : IdentifierResolution

    data object Resolving : IdentifierResolution

    /** Resolved to a profile; [npub] is ready to hand to the profile sheet. */
    data class Resolved(
        val npub: String,
    ) : IdentifierResolution

    /** Resolution failed; [messageRes] is the inline error to show. */
    data class Failed(
        @StringRes val messageRes: Int,
        val arg: String? = null,
    ) : IdentifierResolution
}

internal fun presentableIdentifierNpub(
    resolvedAccountIdHex: String?,
    npubForDisplay: (String) -> String,
): String? =
    resolvedAccountIdHex
        ?.let(npubForDisplay)
        ?.takeIf { it.startsWith("npub1") }

/**
 * Inline chat-list search result for a pasted Nostr identifier (#344). Renders
 * the resolve lifecycle: a spinner while a NIP-05 lookup is in flight; once
 * resolved, either the existing 1:1 chat row (when the active account already
 * has a DM with the resolved key — tapping opens that conversation directly,
 * issue #344 step 2) or an "open profile" row (no existing chat — tapping
 * routes into the shared profile sheet, which surfaces the Start-chat
 * affordance, step 3); or an inline error on failure. Plain-text queries never
 * reach here — they keep filtering the list as before.
 */
@Composable
private fun ChatListIdentifierResult(
    resolution: IdentifierResolution,
    appState: WhiteNoiseAppState,
    accountRef: String?,
    existingDirectChat: (String) -> ChatListItem?,
    onOpenChat: (ChatListItem) -> Unit,
    onOpenProfile: (String) -> Unit,
) {
    when (resolution) {
        IdentifierResolution.None -> Unit
        IdentifierResolution.Resolving ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(R.string.chat_list_search_resolving),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        is IdentifierResolution.Resolved -> {
            // Surface the existing 1:1 chat as the top result so a single tap
            // opens the conversation (issue #344 step 2). existingDirectChat
            // reads snapshot-backed projected items, so this recomposes if the
            // chat list lands the DM after the npub already resolved. Only when
            // there is no existing DM do we fall back to the profile preview /
            // Start-chat path (step 3).
            val existing = existingDirectChat(resolution.npub)
            if (existing != null) {
                ChatRow(
                    item = existing,
                    appState = appState,
                    accountRef = accountRef,
                    onClick = { onOpenChat(existing) },
                    onOpenProfile = onOpenProfile,
                )
            } else {
                ListItem(
                    modifier =
                        Modifier.clickable(role = Role.Button) { onOpenProfile(resolution.npub) },
                    leadingContent = {
                        Avatar(
                            title = IdentityFormatter.short(resolution.npub),
                            seed = resolution.npub,
                            size = 40.dp,
                            pictureUrl = null,
                        )
                    },
                    headlineContent = {
                        Text(
                            stringResource(R.string.chat_list_search_open_profile),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    supportingContent = {
                        Text(
                            IdentityFormatter.short(resolution.npub, prefix = 12, suffix = 8),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailingContent = {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null)
                    },
                )
            }
        }
        is IdentifierResolution.Failed ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    resolution.arg
                        ?.let { stringResource(resolution.messageRes, it) }
                        ?: stringResource(resolution.messageRes),
                    color = MaterialTheme.colorScheme.error,
                )
            }
    }
}

// Approximate clearance the chat-list quick-action FAB occupies above the
// navigation bar. Used by ChatsScreen to push the global + chat-list snackbar
// hosts above the FAB so a toast (e.g. "npub copied" from a profile sheet)
// doesn't sit over the FAB and swallow its taps for the toast's duration —
// issue #352. The toggle FAB is ~56.dp and the Scaffold floats it 16.dp above
// the bottom inset; 80.dp clears that stack with a small gap so the lifted
// snackbar reads as sitting *above* the FAB, per Material guidance.
private val FAB_SNACKBAR_INSET = 80.dp

// Chat-list jump-to-top FAB thresholds (issue #413). The button shows once the
// first visible row index reaches SHOW, and only hides again once it falls back
// to HIDE — the 3–4 dead band gives hysteresis so a quick scroll wiggle near
// the threshold doesn't flicker the button. SNAP bounds the tap animation: from
// deeper than SNAP rows down we hard-jump to SNAP first, then animate the final
// stretch, so a tap from hundreds of rows deep isn't a multi-second crawl.
private const val CHAT_LIST_JUMP_TO_TOP_SHOW_INDEX = 5

private const val CHAT_LIST_JUMP_TO_TOP_HIDE_INDEX = 2

private const val CHAT_LIST_JUMP_TO_TOP_SNAP_INDEX = 10

private const val CHAT_LIST_PINNED_BOUNDARY_CONTENT_TYPE = "pinned-boundary"

private const val CHAT_LIST_ROW_CONTENT_TYPE = "chat-row"

private const val CHAT_LIST_SEARCH_HEADER_CONTENT_TYPE = "search-section-header"

internal const val CHAT_LIST_SEARCH_GROUPS_HEADER_KEY = "chat-list-search-groups-header"
internal const val CHAT_LIST_SEARCH_MESSAGES_HEADER_KEY = "chat-list-search-messages-header"
internal const val CHAT_LIST_SEARCH_GROUPS_HEADER_TAG = CHAT_LIST_SEARCH_GROUPS_HEADER_KEY
internal const val CHAT_LIST_SEARCH_MESSAGES_HEADER_TAG = CHAT_LIST_SEARCH_MESSAGES_HEADER_KEY

private data class ChatListBodySearchKey(
    val query: String,
    val accountRef: String?,
    val showArchived: Boolean,
    val canonicalGroupIds: List<String>,
)

private class ChatListBodySearchRequest

private data class ChatListBodySearchResult(
    val request: ChatListBodySearchRequest,
    val matches: Map<String, MessageBodyMatch>,
)

// Debounce before the chat-list message-body search fires its per-chat FFI
// queries (issue #290). Sits inside the existing 250–300 ms chat-list input
// debounce band so a fast typist doesn't trigger a query per keystroke.
internal const val CHAT_LIST_SEARCH_DEBOUNCE_MS: Long = 275L

@Composable
@Suppress("FunctionNaming")
internal fun ChatListSearchSectionHeader(
    title: String,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Text(
            text = title,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics { heading() }
                    .testTag(testTag)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun AppUpdateBanner(
    info: AppUpdateInfo,
    onUpdateNow: () -> Unit,
    onDismiss: () -> Unit,
) {
    val latest = info.latestVersion ?: return
    val description = stringResource(R.string.app_update_available_description, latest)
    val releasesBehind = info.releasesBehind
    ElevatedCard(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.spaceMd, vertical = Dimens.spaceSm),
        colors =
            CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Dimens.spaceMd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.spaceMd),
        ) {
            Icon(Icons.Default.Download, contentDescription = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(if (info.isFarBehind) R.string.app_update_persistent_title else R.string.app_update_available_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(description, style = MaterialTheme.typography.bodyMedium)
                if (releasesBehind != null && releasesBehind > 0) {
                    Text(
                        pluralStringResource(R.plurals.app_update_releases_behind, releasesBehind, releasesBehind),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(onClick = onUpdateNow) {
                    Text(stringResource(R.string.app_update_now))
                }
            }
            if (!info.isFarBehind) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.dismiss))
                }
            }
        }
    }
}
