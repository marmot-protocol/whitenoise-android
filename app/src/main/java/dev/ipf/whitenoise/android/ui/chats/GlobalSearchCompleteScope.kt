package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.ipf.marmotkit.HostPerformanceOperationFfi
import dev.ipf.whitenoise.android.core.ChatListSearchSections
import dev.ipf.whitenoise.android.core.GroupTitleCopy
import dev.ipf.whitenoise.android.core.canonicalChatListGroupId
import dev.ipf.whitenoise.android.core.chatFolderChatIds
import dev.ipf.whitenoise.android.core.chatListItemDisplayTitle
import dev.ipf.whitenoise.android.core.memberCandidateIds
import dev.ipf.whitenoise.android.core.projectChatListSearchSections
import dev.ipf.whitenoise.android.core.unreadNeedsMembers
import dev.ipf.whitenoise.android.state.AccountWideForwardTargets
import dev.ipf.whitenoise.android.state.AccountWideRead
import dev.ipf.whitenoise.android.state.ChatFolderQuery
import dev.ipf.whitenoise.android.state.ChatFolderRule
import dev.ipf.whitenoise.android.state.ChatListActionOwner
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ChatListSelectAllScope
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.chatFolderSource
import dev.ipf.whitenoise.android.state.departedFolderMatches
import dev.ipf.whitenoise.android.state.folderQuery
import dev.ipf.whitenoise.android.state.readAccountWideForwardTargets
import dev.ipf.whitenoise.android.state.runCatchingCancellable
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
        globalSearchScopedChats(
            source,
            state,
            folderChatIds,
        )

    /**
     * Group ids for a message or attachment query. An explicitly chosen chat is an identity, so one that is in no
     * complete row yet stays in scope unless the folder scope leaves it out.
     */
    fun scopedChatIds(state: GlobalSearchState): List<String> {
        val ids = scopedChats(state).mapTo(ArrayList()) { it.group.groupIdHex }
        val known = allRows.mapTo(HashSet()) { canonicalChatListGroupId(it.group.groupIdHex) }
        val inFolder = folderChatIds?.mapTo(HashSet(), ::canonicalChatListGroupId)
        state.chatFilters
            .map { it.stableId }
            .filter { canonicalChatListGroupId(it) !in known }
            .filter { inFolder == null || canonicalChatListGroupId(it) in inFolder }
            .forEach(ids::add)
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

/** What a global search knows about its complete scope: still resolving, unable to resolve, or proven. */
internal sealed interface GlobalSearchScopeStatus {
    /** No covering snapshot, native membership or roster is ready yet; no scoped query may run. */
    data object Pending : GlobalSearchScopeStatus

    /** The read, a native folder query or a roster resolution failed; the surface offers Retry. */
    data object Failed : GlobalSearchScopeStatus

    /** Every chat the search is scoped to is known. */
    data class Ready(
        val scope: GlobalSearchCompleteScope,
    ) : GlobalSearchScopeStatus
}

/** The scope [status] plus [retry], which reruns the account-wide read and every resolution behind it. */
internal class GlobalSearchScopeState(
    val status: GlobalSearchScopeStatus,
    val retry: () -> Unit,
) {
    /** The proven scope, or null while it is pending or failed. */
    val scope: GlobalSearchCompleteScope? get() = (status as? GlobalSearchScopeStatus.Ready)?.scope

    /** Whether resolving the scope failed, so a chat-scoped search shows its error state instead of loading. */
    val failed: Boolean get() = status is GlobalSearchScopeStatus.Failed
}

/** Membership revisions a scope is reread on; row-level revisions reach the scope through the retained rows. */
private data class Revisions(
    val groups: Long,
    val ambiguousRemovals: Long,
)

/**
 * The revisions that can change which chats exist: window membership and removals of unknown cause. Row-level
 * changes (such as [ChatsController.forwardTargetsRevision], bumped on every recompute) are not among them,
 * because retained rows override the snapshot and a stale membership is withheld by `coversCurrentMembership`.
 */
private fun ChatsController.revisions() = Revisions(materializedGroupsRevision, ambiguousMembershipRemovalsRevision)

/**
 * The complete scope state while search is [enabled]: pending until a covering account-wide snapshot, every
 * folder's native membership and every needed participant roster are known, [GlobalSearchScopeStatus.Failed]
 * when the read, a native query or a roster resolution fails, and otherwise the scope. A result for an account
 * or folder state that has since changed is dropped. The snapshot is [seed] when that still covers membership,
 * and is reread on membership revisions. Row projection runs on the main thread that owns the controller's caches;
 * only matching and native queries run off it.
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
    queryKey: Any?,
): GlobalSearchScopeState {
    val fence =
        GlobalSearchScopeFence(
            controller,
            appState.activeAccountRef,
            appState.runtimeGeneration,
            homeFolderId,
            folderFilters,
            folderStore,
        )
    var retryRevision by remember(controller, fence.activeAccountRef, fence.runtimeGeneration) { mutableIntStateOf(0) }
    val read = rememberScopeSnapshot(appState, controller, enabled, seed, retryRevision)
    val snapshot = listOfNotNull(read.own, seed).firstOrNull { it.isCurrent && it.coversCurrentMembership }
    val produced = rememberResolvedScope(appState, controller, titleCopy, enabled, snapshot, fence, retryRevision)
    val status =
        when {
            !enabled -> GlobalSearchScopeStatus.Pending
            snapshot == null -> if (read.failed) GlobalSearchScopeStatus.Failed else GlobalSearchScopeStatus.Pending
            else -> {
                produced?.takeIf { it.fence == fence && it.snapshot === snapshot }?.status
                    ?: GlobalSearchScopeStatus.Pending
            }
        }
    val held = remember(fence, queryKey, enabled) { HeldScope() }
    return GlobalSearchScopeState(held.settle(status)) { retryRevision += 1 }
}

/**
 * The last Ready scope for one fence and query. While a newer snapshot of the same query resolves, the search keeps
 * running on it instead of pausing; a failure withdraws it, and a new query or fence starts without one, so a
 * changed query always waits for a proven scope. An unchanged Ready scope keeps its identity, so the sections
 * projected from it are not recomputed.
 */
private class HeldScope {
    private var scope: GlobalSearchCompleteScope? = null

    fun settle(status: GlobalSearchScopeStatus): GlobalSearchScopeStatus =
        when (status) {
            is GlobalSearchScopeStatus.Ready -> {
                val previous = scope
                val kept = previous?.takeIf { it.sameAs(status.scope) }
                scope = kept ?: status.scope
                if (kept != null) GlobalSearchScopeStatus.Ready(kept) else status
            }
            GlobalSearchScopeStatus.Failed -> {
                scope = null
                status
            }
            GlobalSearchScopeStatus.Pending -> scope?.let(GlobalSearchScopeStatus::Ready) ?: status
        }
}

/** Whether [other] scopes exactly the same chats with the same rows. */
private fun GlobalSearchCompleteScope.sameAs(other: GlobalSearchCompleteScope): Boolean =
    fence == other.fence && folderChatIds == other.folderChatIds && source == other.source && allRows == other.allRows

/** A scope [status] and the [fence] and [snapshot] it was resolved from; it applies only while both are current. */
private class ResolvedScope(
    val fence: GlobalSearchScopeFence,
    val snapshot: AccountWideForwardTargets,
    val status: GlobalSearchScopeStatus,
)

/** The account-wide read this screen holds for the scope: its snapshot, and whether the latest read failed. */
private class ScopeRead {
    var own by mutableStateOf<AccountWideForwardTargets?>(null)
    var failed by mutableStateOf(false)

    /** Records [read] for the binding it started under; a read for another binding or an unavailable one is ignored. */
    fun apply(
        read: AccountWideRead,
        sameRuntime: Boolean,
    ) {
        if (!sameRuntime) return
        when (read) {
            is AccountWideRead.Loaded ->
                if (read.snapshot.isCurrent) {
                    own = read.snapshot
                    failed = false
                }
            AccountWideRead.Failed -> failed = true
            AccountWideRead.Unavailable -> Unit
        }
    }
}

/**
 * This screen's own account-wide read, redone on each scope revision and each [retryRevision] unless a covering
 * [seed] opens search. A failed read is kept apart from a pending one so the surface can offer Retry.
 */
@Composable
private fun rememberScopeSnapshot(
    appState: WhiteNoiseAppState,
    controller: ChatsController,
    enabled: Boolean,
    seed: AccountWideForwardTargets?,
    retryRevision: Int,
): ScopeRead {
    val account = appState.activeAccountRef
    val generation = appState.runtimeGeneration
    val state = remember(controller, account, generation) { ScopeRead() }
    val currentSeed by rememberUpdatedState(seed)
    LaunchedEffect(controller, account, generation, enabled, retryRevision) {
        if (!enabled || account == null) return@LaunchedEffect
        var first = retryRevision == 0
        snapshotFlow { controller.revisions() }
            .conflate()
            .collect {
                // A prepared unread snapshot that still covers membership opens search with no read.
                val seeded = first && currentSeed?.let { it.isCurrent && it.coversCurrentMembership } == true
                first = false
                if (seeded) return@collect
                state.failed = false
                val read = controller.readAccountWideForwardTargets()
                state.apply(read, appState.activeAccountRef == account && appState.runtimeGeneration == generation)
            }
    }
    return state
}

/**
 * The scope status resolved for [fence] and [snapshot]; a late result for a fence that has moved on is dropped, and
 * a status resolved from an earlier snapshot never applies to a newer one. Participant folders re-resolve when a
 * roster arrives, so the revision that publishes it is a key only for such folders.
 */
@Composable
private fun rememberResolvedScope(
    appState: WhiteNoiseAppState,
    controller: ChatsController,
    titleCopy: GroupTitleCopy,
    enabled: Boolean,
    snapshot: AccountWideForwardTargets?,
    fence: GlobalSearchScopeFence,
    retryRevision: Int,
): ResolvedScope? {
    val currentFence by rememberUpdatedState(fence)
    val items = controller.items
    val archived = controller.archivedItems
    val profileRevision = appState.profileRevisionForCompose
    val preferences = appState.chatFolderPreferences
    val needsRosters =
        fence.activeAccountRef?.let { account ->
            (listOfNotNull(fence.homeFolderId) + fence.folderFilters).any {
                preferences.folderRule(account, it).unreadNeedsMembers()
            }
        } == true
    val rosterRevision = if (needsRosters) controller.memberSnapshotsRevision else 0L
    // Chats whose roster resolution already completed for this snapshot, so a roster that stays missing fails
    // once instead of being requested again on every revision.
    val attempted = remember(snapshot, fence, retryRevision) { HashSet<String>() }
    val produced =
        produceState<ResolvedScope?>(
            null,
            snapshot,
            items,
            archived,
            fence,
            titleCopy,
            profileRevision,
            enabled,
            rosterRevision,
            retryRevision,
        ) {
            val owner = snapshot?.takeIf { enabled }?.let { controller.captureChatListActionOwner() }
            if (snapshot == null || owner == null) {
                value = null
                return@produceState
            }
            val publish = { status: GlobalSearchScopeStatus ->
                // A late result for a fence that has moved on is dropped; a failed refresh withdraws the old scope.
                if (fence == currentFence) value = ResolvedScope(fence, snapshot, status)
            }
            val status =
                runCatchingCancellable {
                    CompleteScopeRows(appState, controller, owner, snapshot, items + archived, attempted)
                        .resolve(titleCopy, fence) { publish(GlobalSearchScopeStatus.Pending) }
                }.getOrDefault(GlobalSearchScopeStatus.Failed)
            publish(if (controller.chatListActionIsCurrent(owner)) status else GlobalSearchScopeStatus.Pending)
        }
    return produced.value
}

/** One folder's inputs, read on the main thread so matching can run elsewhere without touching shared state. */
private class FolderInput(
    val rule: ChatFolderRule?,
    val manual: Set<String>,
    val excluded: Set<String>,
    val query: ChatFolderQuery?,
)

/** The complete rows of [snapshot] for [owner]'s account, from which folder scopes are resolved. */
private class CompleteScopeRows(
    private val appState: WhiteNoiseAppState,
    private val controller: ChatsController,
    private val owner: ChatListActionOwner,
    private val snapshot: AccountWideForwardTargets,
    private val windowItems: List<ChatListItem>,
    private val attempted: MutableSet<String>,
) {
    private val preferences = appState.chatFolderPreferences
    private val account = owner.accountRef

    /** Projects the rows and captures the folder inputs; both read the controller's main-thread caches. */
    private fun capture(fence: GlobalSearchScopeFence): ScopeInputs {
        val rows =
            snapshot.chatListItems(windowItems) { item ->
                controller.boundAccountRef?.let { appState.draftStore.draftedAtSecondsFor(it, item.group.groupIdHex) }
            }
        val folder = { id: String ->
            FolderInput(
                preferences.folderRule(account, id),
                preferences.membershipFor(account, id),
                preferences.excludedChats(account, id),
                preferences.folderQuery(account, id),
            )
        }
        return ScopeInputs(
            rows = rows,
            accountIdHex = controller.boundAccountIdHex(),
            home = fence.homeFolderId?.let(folder),
            filters = fence.folderFilters.map(folder),
        )
    }

    /**
     * The scope for [fence]. Chats whose participant rule needs a roster they lack are requested once; if any
     * still lack it afterwards the scope fails rather than silently dropping them. [onResolving] reports the
     * pending state while rosters resolve.
     */
    suspend fun resolve(
        titleCopy: GroupTitleCopy,
        fence: GlobalSearchScopeFence,
        onResolving: () -> Unit,
    ): GlobalSearchScopeStatus {
        val displayTitle: (ChatListItem) -> String = { chatListItemDisplayTitle(it, appState, titleCopy) }
        var inputs = capture(fence)
        val missing = inputs.missingRosters(displayTitle)
        if (missing.isNotEmpty()) {
            onResolving()
            val requested = missing - attempted
            if (requested.isNotEmpty()) {
                snapshot.resolveChatListMembers(requested)
                attempted += requested
            }
            inputs = capture(fence)
            if (inputs.missingRosters(displayTitle).isNotEmpty()) return GlobalSearchScopeStatus.Failed
        }
        val current = snapshot.coversCurrentMembership && controller.chatListActionIsCurrent(owner)
        val scope =
            if (current) withContext(Dispatchers.Default) { inputs.scope(fence, displayTitle) } else null
        return scope?.let(GlobalSearchScopeStatus::Ready) ?: GlobalSearchScopeStatus.Pending
    }

    /** The scope over the complete rows; folder ids come from their complete native membership. */
    private suspend fun ScopeInputs.scope(
        fence: GlobalSearchScopeFence,
        displayTitle: (ChatListItem) -> String,
    ): GlobalSearchCompleteScope {
        val homeIds = home?.let { folderChatIds(it, displayTitle) }
        val filtered = filters.takeIf { it.isNotEmpty() }?.flatMapTo(HashSet()) { folderChatIds(it, displayTitle) }
        return GlobalSearchCompleteScope(
            fence = fence,
            source = chatFolderSource(home?.rule, active, archived),
            allRows = rows,
            folderChatIds = intersectChatScopes(homeIds, filtered),
        )
    }

    /** A folder's complete membership; a native membership that cannot be read throws. */
    private suspend fun ScopeInputs.folderChatIds(
        input: FolderInput,
        displayTitle: (ChatListItem) -> String,
    ): Set<String> =
        if (input.query != null) {
            nativeChatIds(input.query).toSet() +
                departedFolderMatches(
                    input.rule,
                    input.manual,
                    input.excluded,
                    active,
                    archived,
                    accountIdHex,
                    displayTitle,
                )
        } else {
            val source = chatFolderSource(input.rule, active, archived)
            val muted = source.filter { it.engineMuted() }.mapTo(HashSet()) { it.group.groupIdHex }
            chatFolderChatIds(
                items = source,
                manualChatIds = input.manual,
                rule = input.rule,
                activeAccountIdHex = accountIdHex,
                isMuted = { it in muted },
                displayTitle = displayTitle,
                excludedChatIds = input.excluded,
            )
        }

    /**
     * A native folder's complete membership. A presentation that stays not ready through the bounded retries, an
     * unavailable reader or any other failure propagates, so the scope fails with Retry instead of staying pending
     * forever; a read for an owner that is no longer current is reported pending by the caller.
     */
    private suspend fun nativeChatIds(query: ChatFolderQuery): List<String> =
        resolveCompleteChatListIds {
            controller.completeChatListIds(ChatListSelectAllScope.of(query), owner)
        } ?: error("complete chat list unavailable")
}

/** Projected complete rows with the folder inputs they are evaluated against. */
private class ScopeInputs(
    val rows: List<ChatListItem>,
    val accountIdHex: String?,
    val home: FolderInput?,
    val filters: List<FolderInput>,
) {
    val active = rows.filterNot { it.group.archived }
    val archived = rows.filter { it.group.archived }

    /** Chats a participant folder cannot decide without a roster they do not have yet. */
    fun missingRosters(displayTitle: (ChatListItem) -> String): Set<String> {
        val lacking = rows.filter { it.memberSnapshot == null }.mapTo(HashSet()) { it.group.groupIdHex }
        return (listOfNotNull(home) + filters)
            .flatMapTo(HashSet()) { input ->
                input.rule.memberCandidateIds(
                    chatFolderSource(input.rule, active, archived),
                    input.manual,
                    input.excluded,
                    accountIdHex,
                    displayTitle,
                )
            }.filterTo(HashSet()) { it in lacking }
    }
}

/**
 * The chat-row sections over the complete scope, or null until they are first projected for this query, so the
 * caller keeps showing its window sections meanwhile. A newer scope for the same query keeps the previous sections
 * until it is projected. Projection runs off the main thread.
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
    // The query the sections answer; while a new scope for the same query re-projects, the previous sections stay.
    val query = listOf(scope?.fence, normalizedQuery, messageOnly, state.chatTypeFilters, state.chatFilters)
    val produced =
        produceState<ProjectedSections?>(null, token) {
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
            value = ProjectedSections(token, query, sections)
        }
    return produced.value?.takeIf { scope != null && (it.token === token || it.query == query) }?.sections
}

/** Sections projected for [token], and the [query] they answer. */
private class ProjectedSections(
    val token: Any,
    val query: List<Any?>,
    val sections: ChatListSearchSections<ChatListItem>,
)
