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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.ChatListFilter
import dev.ipf.whitenoise.android.core.ChatListIdentifierSearch
import dev.ipf.whitenoise.android.core.ChatListMessageSearch
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.core.MessageBodyMatch
import dev.ipf.whitenoise.android.core.Nip05Resolver
import dev.ipf.whitenoise.android.core.applyChatListSearchAndFilter
import dev.ipf.whitenoise.android.core.chatListItemDisplayTitle
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ChatMutePreferences
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.chats.newchat.NewChatFlowHost
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.common.ConfirmDialog
import dev.ipf.whitenoise.android.ui.common.ErrorContent
import dev.ipf.whitenoise.android.ui.common.LoadingScreen
import dev.ipf.whitenoise.android.ui.common.LocalSnackbarBottomInset
import dev.ipf.whitenoise.android.ui.common.rememberGroupTitleCopy
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

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
    onOpenGroup: (ChatListItem, String?, Boolean) -> Unit,
) {
    val groupTitleCopy = rememberGroupTitleCopy()
    var showNewChatFlow by rememberSaveable { mutableStateOf(false) }
    var pendingBulkDelete by remember { mutableStateOf<List<ChatListItem>?>(null) }
    val selectedChatIds = remember { mutableStateSetOf<String>() }
    val selectionMode = selectedChatIds.isNotEmpty()
    val mutedConversations by appState.chatMutePreferences.mutedConversations.collectAsState()
    // Search expand/collapse + live query. The search input is anchored in
    // the top bar; tapping the magnifier swaps the chrome (account avatar
    // + nav icons) for a TextField that filters in real time on title +
    // last-message preview. Closing the search clears the query so the
    // next open starts fresh.
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    // Async message-body search results (issue #290), keyed by group id. The
    // title/preview match in `applyChatListSearchAndFilter` is synchronous;
    // body matching has to query each conversation's local timeline via the
    // `timelineMessages` FFI, so it lands here a debounce later. A row uses
    // its entry (if any) to render the highlighted snippet line and to scroll
    // to the matched message on tap-through.
    var bodyMatches by remember { mutableStateOf<Map<String, MessageBodyMatch>>(emptyMap()) }
    // Resolution state for a pasted Nostr identifier in the search field (#344).
    // An npub resolves synchronously; a NIP-05 address resolves over the network
    // (loading → resolved/failed). Plain-text queries stay [None] and the list
    // filters exactly as before.
    var identifierResolution by remember { mutableStateOf<IdentifierResolution>(IdentifierResolution.None) }
    var filter by remember { mutableStateOf(ChatListFilter.All) }
    // The Archived filter is a view switch, not a row predicate: it swaps the
    // source list to archived chats (replacing the old dedicated Archived row).
    val showArchived = filter == ChatListFilter.Archived
    val searchFocusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
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
        } else {
            searchQuery = ""
        }
    }
    // Back from chat-list search unwinds the search state — close the field and
    // restore the normal top bar (which drops focus and the IME) — instead of
    // exiting the app, matching the Settings/Diagnostics back behavior (#121,
    // #149). See #320. Selection mode takes priority (#1169).
    BackHandler(enabled = chatListBackHandlerEnabled(selectionMode, searchOpen)) {
        when {
            selectionMode -> clearSelection()
            searchOpen -> searchOpen = false
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
                    searchQuery = recognized
                }
            }
        }

    val sourceList = if (showArchived) controller.archivedItems else controller.items
    // Subscribing read of the profile-cache revision so the filter
    // re-runs when a DM peer's display name resolves — the title
    // projection inside `applyChatListSearchAndFilter` reads
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
    val bodySearchGroupIds = remember(sourceList) { sourceList.map { it.id }.sorted() }
    LaunchedEffect(trimmedQuery, showArchived, bodySearchGroupIds) {
        if (trimmedQuery.isEmpty()) {
            bodyMatches = emptyMap()
            return@LaunchedEffect
        }
        delay(CHAT_LIST_SEARCH_DEBOUNCE_MS)
        bodyMatches = controller.searchMessageBodies(sourceList, trimmedQuery)
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
                val npub = hex?.let { appState.npub(it) }
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
    val visibleItems =
        remember(sourceList, searchQuery, filter, groupTitleCopy, profileRev, bodyMatches) {
            applyChatListSearchAndFilter(
                sourceList,
                searchQuery,
                filter,
                appState,
                groupTitleCopy,
                bodyMatchGroupIds = bodyMatches.keys,
            )
        }
    val visibleChatIds = remember(visibleItems) { visibleItems.map { it.id }.toSet() }
    LaunchedEffect(visibleChatIds, selectionMode) {
        if (selectionMode) {
            selectedChatIds.retainAll(reconcileChatListSelection(selectedChatIds, visibleChatIds))
        }
    }
    val selectedVisibleItems =
        remember(visibleItems, selectedChatIds.size) {
            visibleItems.filter { it.id in selectedChatIds }
        }
    val bulkArchiveAction =
        remember(selectedVisibleItems) {
            chatListBulkArchiveAction(selectedVisibleItems.map { it.group.archived })
        }
    val singleSelectedItem = selectedVisibleItems.singleOrNull()
    val singleSelectionMuted =
        singleSelectedItem?.let { item ->
            appState.activeAccountRef?.let { accountRef ->
                ChatMutePreferences.compositeKey(accountRef, item.group.groupIdHex) in mutedConversations
            }
        } ?: false
    // Hoisted list state so the jump-to-top FAB (issue #413) can both read the
    // scroll position for its visibility predicate and drive the animated
    // scroll-to-top on tap. Wrapped in key(showArchived) so switching the
    // active vs. archived view starts a fresh state at the top rather than
    // carrying a stale scroll anchor across the source-list swap.
    val chatListState = key(showArchived) { rememberLazyListState() }
    // Hysteresis for the jump-to-top button: show once the user is ≥ 5 rows
    // deep, hide only after they climb back to ≤ 2. The 3–4 dead band keeps a
    // quick scroll wiggle near the threshold from toggling the button (issue
    // #413). derivedStateOf reads the previous decision so the band is sticky.
    var jumpToTopVisible by remember(showArchived) { mutableStateOf(false) }
    // Keyed on chatListState: switching active/archived recreates the list
    // state under key(showArchived) above, so the derived read must re-bind to
    // the new state or the FAB would keep observing the disposed one and reflect
    // a stale scroll position after the toggle (issue #413 review).
    val firstVisibleIndex by remember(chatListState) { derivedStateOf { chatListState.firstVisibleItemIndex } }
    LaunchedEffect(firstVisibleIndex) {
        if (firstVisibleIndex >= CHAT_LIST_JUMP_TO_TOP_SHOW_INDEX) {
            jumpToTopVisible = true
        } else if (firstVisibleIndex <= CHAT_LIST_JUMP_TO_TOP_HIDE_INDEX) {
            jumpToTopVisible = false
        }
    }
    // Snap the list flush to the top when a different chat reorders into
    // position 0 *and the head row is sitting clipped* (issue #541). A send
    // bumps the messaged conversation to the head via the live subscription;
    // with keyed `items`, LazyColumn pins the previously-anchored row at its
    // old pixel offset, so the new head lands one row down / clipped instead of
    // flush. We snap to offset 0 so the freshest chat is fully visible.
    //
    // The snap is deliberately constrained to the clipped-head case so a plain
    // background/incoming reorder can't yank an idle reader to the top (issue
    // #541 review). It fires only when ALL hold:
    //   - active (non-archived) list,
    //   - the head row's identity actually changed (a different chat reordered
    //     in; the first established head is seeded without snapping),
    //   - the user is not mid-scroll (`isScrollInProgress`),
    //   - the viewport is anchored at item 0 (`firstVisibleItemIndex == 0`) —
    //     i.e. the user was at/near the top, the send/back-return case — and
    //   - that item-0 anchor is clipped (`firstVisibleItemScrollOffset > 0`),
    //     the symptom we're correcting.
    // A reader scrolled deeper has `firstVisibleItemIndex > 0`, so an unrelated
    // reorder to data-index-0 leaves their position untouched.
    // Keyed on `showArchived` so the tracked head resets alongside
    // `chatListState` on a view swap.
    val activeHeadId = if (showArchived) null else visibleItems.firstOrNull()?.id
    var lastActiveHeadId by remember(showArchived) { mutableStateOf(activeHeadId) }
    LaunchedEffect(activeHeadId) {
        val previous = lastActiveHeadId
        lastActiveHeadId = activeHeadId
        if (activeHeadId == null || previous == null || activeHeadId == previous) return@LaunchedEffect
        if (chatListState.isScrollInProgress) return@LaunchedEffect
        // Only correct the clipped head at the top of the list; leave a reader
        // scrolled further down (firstVisibleItemIndex > 0) where they are.
        if (chatListState.firstVisibleItemIndex != 0) return@LaunchedEffect
        if (chatListState.firstVisibleItemScrollOffset == 0) return@LaunchedEffect
        chatListState.scrollToItem(0)
    }
    val archivedUnreadCount =
        remember(controller.archivedItems) {
            controller.archivedItems.count { it.hasUnread }
        }
    // Leave the archived view if its last chat is unarchived — the chip is then
    // hidden, so don't strand the selection on it.
    LaunchedEffect(controller.archivedItems.isEmpty(), filter) {
        if (filter == ChatListFilter.Archived && controller.archivedItems.isEmpty()) filter = ChatListFilter.All
    }

    if (showNewChatFlow) {
        NewChatFlowHost(
            appState = appState,
            onOpenConversation = { item, justCreated ->
                showNewChatFlow = false
                onOpenGroup(item, null, justCreated)
            },
            onClose = { showNewChatFlow = false },
        )
        return
    }

    Scaffold(
        topBar = {
            if (selectionMode) {
                ChatListSelectionBar(
                    count = selectedChatIds.size,
                    archiveAction = bulkArchiveAction,
                    actionsEnabled = selectedChatIds.isNotEmpty(),
                    allVisibleSelected = visibleChatIds.isNotEmpty() && selectedChatIds.containsAll(visibleChatIds),
                    showMarkRead =
                        singleSelectedItem?.effectiveHasUnread(appState.activeAccount?.accountIdHex) == true,
                    showMuteToggle = singleSelectedItem != null,
                    muted = singleSelectionMuted,
                    onClose = ::clearSelection,
                    onArchive = {
                        val selected = selectedVisibleItems
                        if (selected.isEmpty()) return@ChatListSelectionBar
                        val archive = bulkArchiveAction == ChatListBulkArchiveAction.Archive
                        clearSelection()
                        appState.launchMutation {
                            var succeeded = 0
                            selected.forEach { item ->
                                if (controller.setArchived(item.group.groupIdHex, archive, notify = false)) {
                                    succeeded++
                                }
                            }
                            if (succeeded > 0) {
                                val pluralRes =
                                    if (archive) {
                                        R.plurals.toast_chat_list_chats_archived
                                    } else {
                                        R.plurals.toast_chat_list_chats_restored
                                    }
                                appState.present(
                                    context.resources.getQuantityString(pluralRes, succeeded, succeeded),
                                )
                            }
                        }
                    },
                    onDelete = {
                        pendingBulkDelete = selectedVisibleItems.takeIf { it.isNotEmpty() }
                    },
                    onMarkRead = {
                        val item = singleSelectedItem ?: return@ChatListSelectionBar
                        clearSelection()
                        appState.launchMutation { controller.markAllRead(item) }
                    },
                    onMuteToggle = {
                        val item = singleSelectedItem ?: return@ChatListSelectionBar
                        val nextMuted = !singleSelectionMuted
                        clearSelection()
                        appState.setConversationMuted(
                            item.group.groupIdHex,
                            nextMuted,
                        )
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
                    onSearchQueryChange = { searchQuery = it },
                    onSearchOpen = { searchOpen = true },
                    onSearchClose = { searchOpen = false },
                    onSwitchAccount = { label -> scope.launch { appState.setActiveAccount(label) } },
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
                )
            }
        },
        floatingActionButton = {
            if (!searchOpen && !selectionMode) {
                FloatingActionButton(onClick = { showNewChatFlow = true }) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.new_message))
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Filter chips visible whenever there's content to filter — both
            // in the active and archived lists. They're sticky above the
            // list rather than sticky inside the LazyColumn so they survive
            // an empty-state swap without flicker.
            if (controller.items.isNotEmpty() || controller.archivedItems.isNotEmpty()) {
                ChatListFilterChips(
                    filter = filter,
                    onChange = { filter = it },
                    activeUnreadCount = controller.items.count { it.hasUnread },
                    hasArchived = controller.archivedItems.isNotEmpty(),
                    archivedUnreadCount = archivedUnreadCount,
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
                existingDirectChat = { npub -> appState.existingDirectChat(npub) },
                onOpenChat = { chat ->
                    searchOpen = false
                    onOpenGroup(chat, null, false)
                },
                onOpenProfile = { npub ->
                    searchOpen = false
                    appState.presentProfile(npub)
                },
            )
            Box(Modifier.fillMaxSize()) {
                when {
                    controller.isLoading && sourceList.isEmpty() -> LoadingScreen()
                    controller.error != null ->
                        ErrorContent(
                            stringResource(R.string.couldnt_load_chats),
                            controller.error.orEmpty(),
                        )
                    sourceList.isEmpty() && showArchived ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                stringResource(R.string.no_archived_chats),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    sourceList.isEmpty() ->
                        EmptyChats(onCreate = { showNewChatFlow = true })
                    visibleItems.isEmpty() && identifierResolution != IdentifierResolution.None -> Unit
                    visibleItems.isEmpty() ->
                        ChatListNoResults(
                            query = searchQuery.trim(),
                            filter = filter,
                        )
                    else ->
                        LazyColumn(Modifier.fillMaxSize(), state = chatListState) {
                            items(visibleItems, key = { it.id }) { item ->
                                // Body-match snippet + tap-to-message focus are
                                // for rows that matched ONLY on an older message
                                // body. A row that also matches its title or
                                // current preview keeps the normal single-line
                                // layout and a normal conversation open, so drop
                                // its body match here (issue #290 contract). The
                                // title/preview test mirrors the synchronous
                                // match in applyChatListSearchAndFilter so the
                                // classification can't drift from the filter.
                                val rawBodyMatch = bodyMatches[item.id]
                                val bodyMatch =
                                    rawBodyMatch?.takeUnless {
                                        ChatListMessageSearch.titleOrPreviewMatches(
                                            displayTitle = chatListItemDisplayTitle(item, appState, groupTitleCopy),
                                            previewText = item.projectedPreviewText(),
                                            ciNeedle = trimmedQuery.lowercase(),
                                            description = item.group.description,
                                        )
                                    }
                                ChatListRow(
                                    item = item,
                                    appState = appState,
                                    isMuted =
                                        appState.activeAccountRef?.let { accountRef ->
                                            ChatMutePreferences.compositeKey(accountRef, item.group.groupIdHex) in
                                                mutedConversations
                                        } ?: false,
                                    selectionMode = selectionMode,
                                    selected = item.id in selectedChatIds,
                                    bodyMatch = bodyMatch,
                                    onOpen = { onOpenGroup(item, bodyMatch?.messageIdHex, false) },
                                    onEnterSelection = {
                                        selectedChatIds.clear()
                                        selectedChatIds.addAll(enterChatListSelection(item.id))
                                    },
                                    onToggleSelection = {
                                        val updated = toggleChatListSelection(selectedChatIds, item.id)
                                        selectedChatIds.clear()
                                        selectedChatIds.addAll(updated)
                                    },
                                )
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
                            // end = 24dp (not 16) so this 40dp small FAB's centre
                            // lines up with the 56dp quick-action FAB above it,
                            // which the Scaffold insets 16dp from the edge:
                            // 24 + 40/2 == 16 + 56/2 (#451).
                            .padding(end = 24.dp, bottom = FAB_SNACKBAR_INSET),
                ) {
                    SmallFloatingActionButton(
                        onClick = {
                            scope.launch {
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
                    ) {
                        Icon(
                            Icons.Default.ArrowUpward,
                            contentDescription = stringResource(R.string.scroll_to_top),
                        )
                    }
                }
            }
        }
    }

    pendingBulkDelete?.let { items ->
        val count = items.size
        ConfirmDialog(
            title = stringResource(R.string.delete_group_confirm),
            message = pluralStringResource(R.plurals.chat_list_bulk_delete_confirm, count, count),
            confirmLabel = stringResource(R.string.delete_group_confirm),
            destructive = true,
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
                        appState.present(
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
                    onClick = { onOpenChat(existing) },
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

// Debounce before the chat-list message-body search fires its per-chat FFI
// queries (issue #290). Sits inside the existing 250–300 ms chat-list input
// debounce band so a fast typist doesn't trigger a query per keystroke.
internal const val CHAT_LIST_SEARCH_DEBOUNCE_MS: Long = 275L
