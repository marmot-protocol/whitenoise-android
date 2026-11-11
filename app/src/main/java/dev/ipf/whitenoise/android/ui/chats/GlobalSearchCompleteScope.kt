package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.ipf.marmotkit.HostPerformanceOperationFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.core.ChatListSearchSections
import dev.ipf.whitenoise.android.core.GroupTitleCopy
import dev.ipf.whitenoise.android.core.canonicalChatListGroupId
import dev.ipf.whitenoise.android.core.chatFolderChatIds
import dev.ipf.whitenoise.android.core.chatListItemDisplayTitle
import dev.ipf.whitenoise.android.core.projectChatListSearchSections
import dev.ipf.whitenoise.android.state.AccountWideForwardTargets
import dev.ipf.whitenoise.android.state.AccountWideRead
import dev.ipf.whitenoise.android.state.ChatFolderQuery
import dev.ipf.whitenoise.android.state.ChatListActionOwner
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ChatListSelectAllScope
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.chatFolderSource
import dev.ipf.whitenoise.android.state.departedFolderMatches
import dev.ipf.whitenoise.android.state.folderQuery
import dev.ipf.whitenoise.android.state.readAccountWideForwardTargets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

/**
 * Every chat of the account for global search (#3720), not just the bounded Home window. [source] is the folder
 * source the Home folder draws from over the complete rows, [allRows] every complete row, and [folderChatIds] the
 * folder scope (the Home folder intersected with the search's folder filters) resolved over complete membership,
 * null when unscoped. [fence] names the account, runtime, binding and folder inputs the scope answers for.
 */
internal class GlobalSearchCompleteScope(
    val fence: GlobalSearchScopeFence,
    val source: List<ChatListItem>,
    val allRows: List<ChatListItem>,
    val folderChatIds: Set<String>?,
) {
    /** The chats global search is scoped to: filtered complete rows, plus explicit chats no complete row knows. */
    fun scopedChats(state: GlobalSearchState): List<ChatListItem> =
        globalSearchScopedChats(source, state, folderChatIds)

    /**
     * Group ids for a message or attachment query. An explicitly chosen chat is an identity, so one that is in no
     * complete row yet stays in scope unless a folder narrows it away.
     */
    fun scopedChatIds(state: GlobalSearchState): List<String> {
        val ids = scopedChats(state).mapTo(ArrayList()) { it.group.groupIdHex }
        if (folderChatIds == null) {
            val known = allRows.mapTo(HashSet()) { canonicalChatListGroupId(it.group.groupIdHex) }
            state.chatFilters
                .map { it.stableId }
                .filter { canonicalChatListGroupId(it) !in known }
                .forEach(ids::add)
        }
        return ids
    }
}

/** What a complete scope is valid for; a scope for a different fence is never used. */
internal data class GlobalSearchScopeFence(
    val controller: ChatsController,
    val activeAccountRef: String?,
    val runtimeGeneration: Int,
    val homeFolderId: String?,
    val folderFilters: Set<String>,
    val folderStore: Any?,
)

private data class ScopeRevisions(
    val groups: Long,
    val forwardTargets: Long,
    val ambiguousRemovals: Long,
)

private fun ChatsController.scopeRevisions() =
    ScopeRevisions(materializedGroupsRevision, forwardTargetsRevision, ambiguousMembershipRemovalsRevision)

/**
 * The complete scope while search is [enabled], or null while it is pending: no covering account-wide snapshot yet,
 * a folder's native membership not ready, or a result for an account or folder state that has since changed.
 * The snapshot is [seed] when that still covers membership, and is reread on the same revision signals the chat
 * list's unread snapshot uses. Projection and folder resolution run off the main thread.
 */
@Composable
internal fun rememberGlobalSearchCompleteScope(
    appState: WhiteNoiseAppState,
    controller: ChatsController,
    titleCopy: GroupTitleCopy,
    enabled: Boolean,
    seed: AccountWideForwardTargets?,
    homeFolderId: String?,
    folderFilters: Set<String>,
    folderStore: Any?,
): GlobalSearchCompleteScope? {
    val fence =
        GlobalSearchScopeFence(
            controller,
            appState.activeAccountRef,
            appState.runtimeGeneration,
            homeFolderId,
            folderFilters,
            folderStore,
        )
    val own = rememberScopeSnapshot(appState, controller, enabled, seed)
    val snapshot = listOfNotNull(own, seed).firstOrNull { it.isCurrent && it.coversCurrentMembership }
    val produced = rememberResolvedScope(appState, controller, titleCopy, enabled, snapshot, fence)
    return produced?.takeIf { enabled && it.first == fence }?.second
}

/** This screen's own account-wide snapshot, reread on each scope revision unless a covering [seed] opens search. */
@Composable
private fun rememberScopeSnapshot(
    appState: WhiteNoiseAppState,
    controller: ChatsController,
    enabled: Boolean,
    seed: AccountWideForwardTargets?,
): AccountWideForwardTargets? {
    val account = appState.activeAccountRef
    val generation = appState.runtimeGeneration
    var own by remember(controller, account, generation) { mutableStateOf<AccountWideForwardTargets?>(null) }
    val currentSeed by rememberUpdatedState(seed)
    LaunchedEffect(controller, account, generation, enabled) {
        if (!enabled || account == null) return@LaunchedEffect
        var first = true
        snapshotFlow { controller.scopeRevisions() }
            .conflate()
            .collect {
                // A prepared unread snapshot that still covers membership opens search with no read.
                val seeded = first && currentSeed?.let { it.isCurrent && it.coversCurrentMembership } == true
                first = false
                if (seeded) return@collect
                val read = controller.readAccountWideForwardTargets()
                val sameRuntime = appState.activeAccountRef == account && appState.runtimeGeneration == generation
                if (read is AccountWideRead.Loaded && read.snapshot.isCurrent && sameRuntime) own = read.snapshot
            }
    }
    return own
}

/** The scope resolved off the main thread for [fence]; a late result for a fence that has moved on is dropped. */
@Composable
private fun rememberResolvedScope(
    appState: WhiteNoiseAppState,
    controller: ChatsController,
    titleCopy: GroupTitleCopy,
    enabled: Boolean,
    snapshot: AccountWideForwardTargets?,
    fence: GlobalSearchScopeFence,
): Pair<GlobalSearchScopeFence, GlobalSearchCompleteScope>? {
    val currentFence by rememberUpdatedState(fence)
    val revisions = controller.scopeRevisions()
    val items = controller.items
    val archived = controller.archivedItems
    val profileRevision = appState.profileRevisionForCompose
    val produced: State<Pair<GlobalSearchScopeFence, GlobalSearchCompleteScope>?> =
        produceState<Pair<GlobalSearchScopeFence, GlobalSearchCompleteScope>?>(
            null,
            snapshot,
            items,
            archived,
            fence,
            revisions,
            titleCopy,
            profileRevision,
            enabled,
        ) {
            val scope =
                snapshot?.takeIf { enabled }?.let { covering ->
                    controller.captureChatListActionOwner()?.let { owner ->
                        withContext(Dispatchers.Default) {
                            val rows = CompleteScopeRows(appState, controller, owner, covering, items + archived)
                            rows.resolve(titleCopy, fence)
                        }?.takeIf { controller.chatListActionIsCurrent(owner) }
                    }
                }
            // A late result for an account, binding or folder state that has moved on is dropped.
            when {
                snapshot == null || !enabled -> value = null
                scope != null && fence == currentFence -> value = fence to scope
                value?.first != fence -> value = null
            }
        }
    return produced.value
}

/** The complete rows of [snapshot] for [owner]'s account, from which folder scopes are resolved. */
private class CompleteScopeRows(
    private val appState: WhiteNoiseAppState,
    private val controller: ChatsController,
    private val owner: ChatListActionOwner,
    private val snapshot: AccountWideForwardTargets,
    windowItems: List<ChatListItem>,
) {
    val rows =
        snapshot.chatListItems(windowItems) { item ->
            controller.boundAccountRef?.let { appState.draftStore.draftedAtSecondsFor(it, item.group.groupIdHex) }
        }
    private val active = rows.filterNot { it.group.archived }
    private val archived = rows.filter { it.group.archived }
    private val preferences = appState.chatFolderPreferences
    private val account = owner.accountRef

    /**
     * The complete scope for [fence], or null when a folder's native membership is not ready or the snapshot or
     * binding is no longer current.
     */
    suspend fun resolve(
        titleCopy: GroupTitleCopy,
        fence: GlobalSearchScopeFence,
    ): GlobalSearchCompleteScope? {
        val displayTitle: (ChatListItem) -> String = { chatListItemDisplayTitle(it, appState, titleCopy) }
        val home = fence.homeFolderId?.let { folderChatIds(it, displayTitle) }
        val filters = fence.folderFilters.map { folderChatIds(it, displayTitle) }
        val ready = (fence.homeFolderId == null || home != null) && filters.none { it == null }
        val current = (rows.isNotEmpty() || snapshot.isCurrent) && controller.chatListActionIsCurrent(owner)
        val filtered = filters.takeIf { it.isNotEmpty() }?.flatMapTo(HashSet()) { it.orEmpty() }
        val homeRule = fence.homeFolderId?.let { preferences.folderRule(account, it) }
        return if (ready && current) {
            GlobalSearchCompleteScope(
                fence = fence,
                source = chatFolderSource(homeRule, active, archived),
                allRows = rows,
                folderChatIds = intersectChatScopes(home, filtered),
            )
        } else {
            null
        }
    }

    /** A folder's complete membership, or null while its native membership is not ready. */
    private suspend fun folderChatIds(
        folderId: String,
        displayTitle: (ChatListItem) -> String,
    ): Set<String>? {
        val rule = preferences.folderRule(account, folderId)
        val manual = preferences.membershipFor(account, folderId)
        val excluded = preferences.excludedChats(account, folderId)
        val query = preferences.folderQuery(account, folderId)
        val accountIdHex = controller.boundAccountIdHex()
        return if (query != null) {
            nativeChatIds(query)?.let { native ->
                native.toSet() +
                    departedFolderMatches(rule, manual, excluded, active, archived, accountIdHex, displayTitle)
            }
        } else {
            val source = chatFolderSource(rule, active, archived)
            val muted = source.filter { it.engineMuted() }.mapTo(HashSet()) { it.group.groupIdHex }
            chatFolderChatIds(
                items = source,
                manualChatIds = manual,
                rule = rule,
                activeAccountIdHex = accountIdHex,
                isMuted = { it in muted },
                displayTitle = displayTitle,
                excludedChatIds = excluded,
            )
        }
    }

    private suspend fun nativeChatIds(query: ChatFolderQuery): List<String>? =
        try {
            resolveCompleteChatListIds {
                controller.completeChatListIds(ChatListSelectAllScope.of(query), owner)
            }
        } catch (_: MarmotKitException.ChatPresentationNotReady) {
            null
        }
}

/**
 * The chat-row sections over the complete scope, or null until they are projected for exactly these inputs, so the
 * caller keeps showing its window sections meanwhile. Projection runs off the main thread.
 */
@Composable
internal fun rememberCompleteChatSearchSections(
    appState: WhiteNoiseAppState,
    scope: GlobalSearchCompleteScope?,
    state: GlobalSearchState,
    rawQuery: String,
    normalizedQuery: String,
    titleCopy: GroupTitleCopy,
    profileRevision: Any,
    messageOnly: Boolean,
    searchActive: Boolean,
): ChatListSearchSections<ChatListItem>? {
    val token =
        remember(
            scope,
            normalizedQuery,
            titleCopy,
            profileRevision,
            messageOnly,
            state.chatTypeFilters,
            state.chatFilters,
        ) { Any() }
    val produced =
        produceState<Pair<Any, ChatListSearchSections<ChatListItem>>?>(null, token) {
            val complete = scope ?: return@produceState
            val sections =
                withContext(Dispatchers.Default) {
                    val attempt =
                        if (searchActive) {
                            appState.beginHostPerformance(HostPerformanceOperationFfi.CONVERSATION_SEARCH)
                        } else {
                            null
                        }
                    var succeeded = false
                    try {
                        projectChatListSearchSections(
                            source = complete.scopedChats(state),
                            rawQuery = rawQuery,
                            appState = appState,
                            titleCopy = titleCopy,
                            folderChatIds = complete.folderChatIds,
                            messageOnly = messageOnly,
                        ).also {
                            succeeded = true
                            attempt?.success()
                        }
                    } finally {
                        if (!succeeded) attempt?.failure()
                    }
                }
            value = token to sections
        }
    return produced.value?.takeIf { scope != null && it.first === token }?.second
}
