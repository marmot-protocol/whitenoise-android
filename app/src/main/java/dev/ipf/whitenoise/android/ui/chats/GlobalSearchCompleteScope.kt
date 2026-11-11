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
import dev.ipf.whitenoise.android.state.ChatListActionOwner
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ChatListSelectAllScope
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.chatFolderSource
import dev.ipf.whitenoise.android.state.departedFolderMatches
import dev.ipf.whitenoise.android.state.folderQuery
import dev.ipf.whitenoise.android.state.readAccountWideForwardTargets
import kotlinx.coroutines.CancellationException
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
    fun scopedChats(state: GlobalSearchState): List<ChatListItem> = globalSearchScopedChats(source, state, folderChatIds)

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
    val account = appState.activeAccountRef
    val generation = appState.runtimeGeneration
    val fence = GlobalSearchScopeFence(controller, account, generation, homeFolderId, folderFilters, folderStore)
    val currentFence by rememberUpdatedState(fence)
    var own by remember(controller, account, generation) { mutableStateOf<AccountWideForwardTargets?>(null) }
    val currentSeed by rememberUpdatedState(seed)
    LaunchedEffect(controller, account, generation, enabled) {
        if (!enabled || account == null) return@LaunchedEffect
        var first = true
        snapshotFlow { controller.scopeRevisions() }
            .conflate()
            .collect {
                val covered = currentSeed?.takeIf { it.isCurrent && it.coversCurrentMembership }
                // A prepared unread snapshot that still covers membership opens search with no read.
                if (first && covered != null) {
                    first = false
                    return@collect
                }
                first = false
                val read = controller.readAccountWideForwardTargets()
                if (read is AccountWideRead.Loaded &&
                    read.snapshot.isCurrent &&
                    appState.activeAccountRef == account &&
                    appState.runtimeGeneration == generation
                ) {
                    own = read.snapshot
                }
            }
    }
    val revisions = controller.scopeRevisions()
    val items = controller.items
    val archived = controller.archivedItems
    val profileRevision = appState.profileRevisionForCompose
    val snapshot =
        listOfNotNull(own, seed).firstOrNull { it.isCurrent && it.coversCurrentMembership }
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
            if (!enabled || snapshot == null) {
                value = null
                return@produceState
            }
            val owner = controller.captureChatListActionOwner()
            val scope =
                owner?.let {
                    withContext(Dispatchers.Default) {
                        resolveCompleteScope(appState, controller, it, snapshot, items, archived, titleCopy, fence)
                    }
                }
            // A late result for an account, binding or folder state that has moved on is dropped.
            if (scope != null && owner != null && controller.chatListActionIsCurrent(owner) && fence == currentFence) {
                value = fence to scope
            } else if (value?.first != fence) {
                value = null
            }
        }
    return produced.value?.takeIf { enabled && it.first == fence }?.second
}

/** Builds the complete rows and folder scope, or null when native folder membership is not ready or stale. */
private suspend fun resolveCompleteScope(
    appState: WhiteNoiseAppState,
    controller: ChatsController,
    owner: ChatListActionOwner,
    snapshot: AccountWideForwardTargets,
    items: List<ChatListItem>,
    archivedItems: List<ChatListItem>,
    titleCopy: GroupTitleCopy,
    fence: GlobalSearchScopeFence,
): GlobalSearchCompleteScope? {
    val account = owner.accountRef
    val rows =
        snapshot.chatListItems(items + archivedItems) { item ->
            controller.boundAccountRef?.let { appState.draftStore.draftedAtSecondsFor(it, item.group.groupIdHex) }
        }
    if (rows.isEmpty() && !snapshot.isCurrent) return null
    val active = rows.filterNot { it.group.archived }
    val archived = rows.filter { it.group.archived }
    val preferences = appState.chatFolderPreferences
    val accountIdHex = controller.boundAccountIdHex()
    val displayTitle: (ChatListItem) -> String = { chatListItemDisplayTitle(it, appState, titleCopy) }

    suspend fun resolve(folderId: String): Set<String>? {
        val rule = preferences.folderRule(account, folderId)
        val manual = preferences.membershipFor(account, folderId)
        val excluded = preferences.excludedChats(account, folderId)
        val query = preferences.folderQuery(account, folderId)
        return if (query != null) {
            val native =
                try {
                    resolveCompleteChatListIds { controller.completeChatListIds(ChatListSelectAllScope.of(query), owner) }
                } catch (_: MarmotKitException.ChatPresentationNotReady) {
                    null
                } ?: return null
            native.toSet() +
                departedFolderMatches(rule, manual, excluded, active, archived, accountIdHex, displayTitle)
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

    val home = fence.homeFolderId?.let { resolve(it) ?: return null }
    val filtered =
        fence.folderFilters.takeIf { it.isNotEmpty() }?.let { ids ->
            val union = HashSet<String>()
            for (id in ids) union += resolve(id) ?: return null
            union
        }
    if (!controller.chatListActionIsCurrent(owner)) return null
    val homeRule = fence.homeFolderId?.let { preferences.folderRule(account, it) }
    return GlobalSearchCompleteScope(
        fence = fence,
        source = chatFolderSource(homeRule, active, archived),
        allRows = rows,
        folderChatIds = intersectChatScopes(home, filtered),
    )
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
        remember(scope, normalizedQuery, titleCopy, profileRevision, messageOnly, state.chatTypeFilters, state.chatFilters) {
            Any()
        }
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
                    try {
                        projectChatListSearchSections(
                            source = complete.scopedChats(state),
                            rawQuery = rawQuery,
                            appState = appState,
                            titleCopy = titleCopy,
                            folderChatIds = complete.folderChatIds,
                            messageOnly = messageOnly,
                        ).also { attempt?.success() }
                    } catch (cancelled: CancellationException) {
                        attempt?.failure()
                        throw cancelled
                    } catch (throwable: Throwable) {
                        attempt?.failure()
                        throw throwable
                    }
                }
            value = token to sections
        }
    return produced.value?.takeIf { scope != null && it.first === token }?.second
}
