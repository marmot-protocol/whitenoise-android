package dev.ipf.whitenoise.android.state

import android.os.SystemClock
import dev.ipf.marmotkit.AppGroupMemberIdsFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.whitenoise.android.core.GroupProjector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

/**
 * Seed every current chat row's identifier-only roster before publishing
 * the first local frame. MDK answers each page from its command-ready local
 * projection without profile enrichment or relay work, so member-derived
 * folders, shared groups, and existing-DM detection do not wait for an
 * N-call `groupMembers` fan-out (#1534).
 *
 * The page contract is all-or-nothing. If any requested group is unknown
 * or quarantined, fall back to eight bounded per-group local reads and hold
 * the first frame for at most 500 ms. Reads that miss the cutoff keep
 * running under this bind's lifecycle and publish in coalesced batches.
 */
internal suspend fun ChatsController.seedInitialMemberIdProjection(
    account: String,
    epoch: Long,
) {
    val groupIds = chatRows.map { it.groupIdHex }.filterNot { it in terminalMemberFetches }
    if (groupIds.isEmpty()) return
    val expectedCacheEpoch = memberCacheEpoch
    val projections = loadInitialMemberIdProjections(account, groupIds)
    if (projections == null) {
        seedInitialMemberFallback(account, epoch, expectedCacheEpoch, groupIds)
        return
    }
    if (initialMemberProjectionIsCurrent(account, epoch, expectedCacheEpoch)) {
        val activeAccountIdHex = boundAccountIdHex() ?: appState.activeAccount?.accountIdHex
        // Await only on-device DM peer presentation so the first row
        // cannot flash a short identity or empty avatar. Relay freshness
        // remains in requestProfile below. The degraded fallback uses this
        // same barrier for every roster resolved before the cutoff.
        appState.warmProfilePresentationsBlocking(
            initialDirectPeerProfileIds(projections, activeAccountIdHex),
        )
        if (initialMemberProjectionIsCurrent(account, epoch, expectedCacheEpoch)) {
            applyInitialMemberIdProjections(projections, activeAccountIdHex)
        }
    }
}

internal suspend fun ChatsController.seedInitialMemberFallback(
    account: String,
    epoch: Long,
    cacheEpoch: Long,
    groupIds: List<String>,
) {
    val liveGroupIds = chatRows.mapTo(mutableSetOf()) { it.groupIdHex }
    val pending =
        groupIds
            .asSequence()
            .filter { it in liveGroupIds }
            .distinctBy { it.lowercase() }
            .filter { memberSnapshotNeedsFetch(it) }
            .filterNot { it in inFlightMemberFetches }
            .filterNot { memberFetchRetryJobsByGroup[it]?.isActive == true }
            .filterNot { it in terminalMemberFetches }
            .toList()
    if (pending.isEmpty()) return
    inFlightMemberFetches.addAll(pending)
    val cutoffStartedAtMs = SystemClock.elapsedRealtime()

    val fallback =
        loadFirstFrameMemberFallback(
            groupIds = pending,
            cutoffMillis = INITIAL_MEMBER_FALLBACK_CUTOFF_MS,
            maxConcurrent = INITIAL_MEMBER_FALLBACK_FANOUT,
        ) { groupIdHex ->
            // Share the controller-wide permit pool with ordinary and
            // retry reads. Otherwise a retry released by one failed
            // fallback could overlap eight still-running fallback reads.
            memberFetchGate.withPermit {
                memberSnapshotLoader(account, groupIdHex)
            }
        }
    val remainingProfileWarmBudgetMillis =
        (
            INITIAL_MEMBER_FALLBACK_CUTOFF_MS -
                (SystemClock.elapsedRealtime() - cutoffStartedAtMs)
        ).coerceAtLeast(0L)
    publishInitialMemberFallbackResults(
        results = fallback.firstFrameResults,
        account = account,
        epoch = epoch,
        cacheEpoch = cacheEpoch,
        scheduleRecomputeAfterPublish = false,
        profileWarmBudgetMillis = remainingProfileWarmBudgetMillis,
    )
    if (fallback.remainingCount == 0) return

    CoroutineScope(coroutineContext).launch {
        var remaining = fallback.remainingCount
        while (remaining > 0) {
            val completed = mutableListOf(fallback.remainingResults.receive())
            remaining -= 1
            while (remaining > 0) {
                val next = fallback.remainingResults.tryReceive().getOrNull() ?: break
                completed += next
                remaining -= 1
            }
            publishInitialMemberFallbackResults(
                results = completed,
                account = account,
                epoch = epoch,
                cacheEpoch = cacheEpoch,
                scheduleRecomputeAfterPublish = true,
            )
        }
    }
}

internal suspend fun ChatsController.publishInitialMemberFallbackResults(
    results: List<FirstFrameMemberFallbackResult<List<AppGroupMemberRecordFfi>>>,
    account: String,
    epoch: Long,
    cacheEpoch: Long,
    scheduleRecomputeAfterPublish: Boolean,
    profileWarmBudgetMillis: Long? = null,
) {
    when {
        results.isEmpty() -> Unit
        rejectStaleInitialMemberFallbackResults(results, account, epoch, cacheEpoch) -> Unit
        !warmInitialMemberFallbackProfiles(results, profileWarmBudgetMillis) -> {
            continueInitialMemberFallbackProfileWarm(results, account, epoch, cacheEpoch)
        }
        rejectStaleInitialMemberFallbackResults(results, account, epoch, cacheEpoch) -> Unit
        else -> {
            applyInitialMemberFallbackResults(results, epoch, cacheEpoch)
            scheduleRecomputeIfRequested(scheduleRecomputeAfterPublish)
        }
    }
}

internal fun ChatsController.rejectStaleInitialMemberFallbackResults(
    results: List<FirstFrameMemberFallbackResult<List<AppGroupMemberRecordFfi>>>,
    account: String,
    epoch: Long,
    cacheEpoch: Long,
): Boolean {
    val stale = !initialMemberProjectionIsCurrent(account, epoch, cacheEpoch)
    if (stale && isActiveBindEpoch(epoch)) {
        results.forEach { inFlightMemberFetches.remove(it.groupIdHex) }
        schedulePendingMemberFetches(results.map { it.groupIdHex })
    }
    return stale
}

internal suspend fun ChatsController.warmInitialMemberFallbackProfiles(
    results: List<FirstFrameMemberFallbackResult<List<AppGroupMemberRecordFfi>>>,
    budgetMillis: Long?,
): Boolean {
    val liveResults =
        results.filter { result ->
            chatRowsByGroup.containsKey(chatRowKey(result.groupIdHex))
        }
    val activeAccountIdHex = boundAccountIdHex() ?: appState.activeAccount?.accountIdHex
    val projections =
        liveResults.mapNotNull { result ->
            result.result.getOrNull()?.let { members ->
                AppGroupMemberIdsFfi(result.groupIdHex, members.map { it.memberIdHex }, adminIdsHex = emptyList())
            }
        }
    val peerProfileIds = initialDirectPeerProfileIds(projections, activeAccountIdHex)
    return when {
        peerProfileIds.isEmpty() -> true
        budgetMillis == null -> {
            appState.warmProfilePresentationsBlocking(peerProfileIds)
            true
        }
        budgetMillis == 0L -> false
        else ->
            withTimeoutOrNull(budgetMillis) {
                appState.warmProfilePresentationsBlocking(peerProfileIds)
                true
            } == true
    }
}

internal suspend fun ChatsController.continueInitialMemberFallbackProfileWarm(
    results: List<FirstFrameMemberFallbackResult<List<AppGroupMemberRecordFfi>>>,
    account: String,
    epoch: Long,
    cacheEpoch: Long,
) {
    // The 500 ms first-frame budget is authoritative. Keep these rosters
    // unresolved for that frame rather than publishing a DM peer without
    // its locally persisted name/avatar, then finish the same consumed
    // results under the bind lifecycle.
    CoroutineScope(coroutineContext).launch {
        publishInitialMemberFallbackResults(
            results = results,
            account = account,
            epoch = epoch,
            cacheEpoch = cacheEpoch,
            scheduleRecomputeAfterPublish = true,
        )
    }
}

internal fun ChatsController.applyInitialMemberFallbackResults(
    results: List<FirstFrameMemberFallbackResult<List<AppGroupMemberRecordFfi>>>,
    epoch: Long,
    cacheEpoch: Long,
) {
    results.forEach { completed ->
        val groupIdHex = completed.groupIdHex
        inFlightMemberFetches.remove(groupIdHex)
        if (!chatRowsByGroup.containsKey(chatRowKey(groupIdHex))) return@forEach
        completed.result.fold(
            onSuccess = { members ->
                applyFetchedMemberSnapshot(
                    groupIdHex = groupIdHex,
                    members = members,
                    epoch = epoch,
                    cacheEpoch = cacheEpoch,
                    scheduleRecomputeAfterPublish = false,
                )
            },
            onFailure = { throwable ->
                chatsDebug(throwable) {
                    "initial member fallback failed group=${groupIdHex.take(8)}: " +
                        (throwable.message ?: throwable.javaClass.simpleName)
                }
                // A terminal failure marks the group for this bind only, like the ordinary fetch path.
                if (isActiveBindEpoch(epoch)) {
                    markMemberSnapshotFetchFailed(groupIdHex, throwable)
                    scheduleMemberSnapshotRetry(groupIdHex, epoch)
                }
            },
        )
    }
}

internal fun ChatsController.scheduleRecomputeIfRequested(requested: Boolean) {
    if (requested) scheduleRecompute()
}

internal suspend fun ChatsController.loadInitialMemberIdProjections(
    account: String,
    groupIds: List<String>,
): List<AppGroupMemberIdsFfi>? =
    runCatchingCancellable {
        loadGroupMemberIdsPages(groupIds) { page ->
            appState.marmotIo(MarmotTraceSection.MEMBER_IDS_READ) { groupMemberIdsPage(account, page) }
        }
    }.onFailure { error ->
        chatsDebug(error) {
            "initial member-id projection failed account=${account.take(8)}: " +
                (error.message ?: error.javaClass.simpleName)
        }
    }.getOrNull()

internal fun ChatsController.initialMemberProjectionIsCurrent(
    account: String,
    epoch: Long,
    expectedCacheEpoch: Long,
): Boolean =
    initialMemberFallbackGenerationIsCurrent(
        expectedAccount = account,
        expectedBindEpoch = epoch,
        expectedCacheEpoch = expectedCacheEpoch,
        currentAccount = accountRef,
        currentBindEpoch = bindEpoch,
        currentCacheEpoch = memberCacheEpoch,
        lifecycleActive = isActiveBindEpoch(epoch),
    )

internal fun ChatsController.initialDirectPeerProfileIds(
    projections: List<AppGroupMemberIdsFfi>,
    activeAccountIdHex: String?,
): List<String> {
    val rowsByGroup = chatRows.associateBy { chatRowKey(it.groupIdHex) }
    return initialDirectPeerProfileIds(
        projections = projections,
        activeAccountIdHex = activeAccountIdHex,
    ) { groupIdHex, memberCount ->
        val row = rowsByGroup[chatRowKey(groupIdHex)]
        row != null && GroupProjector.isDm(row.conversationKind, memberCount, row.groupName)
    }
}

internal fun ChatsController.applyInitialMemberIdProjections(
    projections: List<AppGroupMemberIdsFfi>,
    activeAccountIdHex: String?,
    requestProfileRefresh: Boolean = true,
) {
    val updatedCache = memberCacheByGroup.toMutableMap()
    var updatedRemovedGroupIds = removedGroupIds
    projections.forEach { projection ->
        val groupIdHex = projection.groupIdHex
        val members = memberRecordsFromIds(projection.memberIdsHex, activeAccountIdHex)
        // Same readiness gate as the streaming path: an empty or
        // self-only-DM roster from the initial page can be a transient
        // catch-up result. Caching it would pin the row on the Unknown
        // fallback forever — memberSnapshotNeedsFetch never retries a
        // cached key — and drop the last-known presentation for nothing.
        // Skipped groups are refetched by the post-bind enrichment sweep,
        // which owns the full gate and retry machinery.
        if (!initialPageRosterReadyToCache(projection.groupIdHex, members, activeAccountIdHex)) {
            return@forEach
        }
        updatedCache[groupIdHex] = members
        updatedRemovedGroupIds =
            if (
                activeAccountIdHex != null &&
                members.none { GroupProjector.isActiveAccountMember(it, activeAccountIdHex) }
            ) {
                updatedRemovedGroupIds + groupIdHex
            } else {
                updatedRemovedGroupIds - groupIdHex
            }
        if (requestProfileRefresh) {
            members.map { it.memberIdHex }.forEach(appState::requestProfile)
        }
        presentationMembersByGroup = presentationMembersByGroup - groupIdHex
        cancelMemberSnapshotRetry(groupIdHex)
        memberFetchRetryBackoffTierByGroup.remove(groupIdHex)
        failedMemberFetches.remove(groupIdHex)
        selfOnlyDirectGraceRetryGroups.remove(groupIdHex)
    }
    memberCacheByGroup = updatedCache
    removedGroupIds = updatedRemovedGroupIds
    memberSnapshotsRevision += 1L
}

// The initial member-id page is gated with the same conversation context
// the streaming path derives: a self-only roster for a direct conversation
// is as transient as an empty one and must be retried, not cached.
internal fun ChatsController.initialPageRosterReadyToCache(
    groupIdHex: String,
    members: List<AppGroupMemberRecordFfi>,
    activeAccountIdHex: String?,
): Boolean =
    memberSnapshotReadyToCache(
        members = members,
        knownSelfRemoval = knownSelfRemovalFor(groupIdHex),
        directConversation = directConversationCandidateFor(groupIdHex, members),
        activeAccountIdHex = activeAccountIdHex,
        selfOnlyDirectGraceElapsed = groupIdHex in selfOnlyDirectGraceRetryGroups,
    )

/**
 * Whether this account's removal from the group is already known locally,
 * which makes an empty roster terminal rather than a hydration gap.
 */
internal fun ChatsController.knownSelfRemovalFor(groupIdHex: String): Boolean =
    groupIdHex in removedGroupIds ||
        chatRowsByGroup[chatRowKey(groupIdHex)]?.selfMembership?.isNonMember() == true ||
        groupRecordsById[groupIdHex]?.selfMembership?.isNonMember() == true

/** Supplies retained-row identity to the shared roster-readiness classification. */
internal fun ChatsController.directConversationCandidateFor(
    groupIdHex: String,
    members: List<AppGroupMemberRecordFfi>,
): Boolean {
    val row = chatRowsByGroup[chatRowKey(groupIdHex)]
    return isDirectConversationRosterCandidate(
        conversationKind = row?.conversationKind,
        members = members,
        name = row?.groupName ?: groupRecordsById[groupIdHex]?.name.orEmpty(),
    )
}

internal fun ChatsController.recordMemberDerivedLocalReadyIfComplete() {
    val account = accountRef ?: return
    if (chatRows.all { memberCacheByGroup.containsKey(it.groupIdHex) }) {
        appState.recordAccountSwitchMemberDerivedLocalReady(account, chatRows.size)
    }
}
