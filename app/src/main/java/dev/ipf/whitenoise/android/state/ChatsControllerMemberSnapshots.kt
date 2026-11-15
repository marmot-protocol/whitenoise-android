package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.PresentedChatRowFfi
import dev.ipf.whitenoise.android.core.GroupProjector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.coroutineContext

/** Invalidates authoritative roster state while retaining display-only continuity. */
internal fun ChatsController.invalidateMemberCacheForGroup(groupIdHex: String) {
    val hasRetryScheduled = memberFetchRetryJobsByGroup[groupIdHex]?.isActive == true
    val hasSnapshot =
        memberCacheByGroup.containsKey(groupIdHex) ||
            presentationMembersByGroup.containsKey(groupIdHex)
    val hasPendingFetch = groupIdHex in inFlightMemberFetches || hasRetryScheduled
    if (!hasSnapshot && !hasPendingFetch) return
    cancelMemberSnapshotRetry(groupIdHex)
    failedMemberFetches.remove(groupIdHex)
    selfOnlyDirectGraceRetryGroups.remove(groupIdHex)
    memberCacheByGroup[groupIdHex]?.let { members ->
        val activeAccountIdHex = boundAccountIdHex() ?: appState.activeAccount?.accountIdHex
        presentationMembersByGroup =
            presentationMembersByGroup +
            (groupIdHex to chatListMemberPresentation(members, activeAccountIdHex))
        memberCacheByGroup = memberCacheByGroup - groupIdHex
    }
    memberCacheLifetime.advance()
}

internal fun ChatsController.memberSnapshotNeedsFetch(groupIdHex: String): Boolean = !memberCacheByGroup.containsKey(groupIdHex)

/**
 * Hydrates the open picker's off-window rows through the existing bounded local roster reader.
 * These rows never enter the native window. Cancellation and both controller lifetimes guard publication.
 */
internal suspend fun ChatsController.resolveForwardTargetMembers(presented: List<PresentedChatRowFfi>) {
    val account = accountRef ?: return
    val epoch = bindEpoch
    do {
        val cacheEpoch = memberCacheEpoch
        val pending =
            presented.distinctBy { it.row.groupIdHex }.filter {
                memberSnapshotNeedsFetch(it.row.groupIdHex) && it.row.groupIdHex !in inFlightMemberFetches
            }
        val ids = pending.map { it.row.groupIdHex }
        inFlightMemberFetches.addAll(ids)
        try {
            if (pending.isNotEmpty()) loadForwardTargetMembers(account, pending, epoch, cacheEpoch)
        } finally {
            if (isActiveBindEpoch(epoch)) inFlightMemberFetches.removeAll(ids.toSet())
        }
        // A live roster invalidation supersedes this read; retry under the same picker-owned coroutine.
    } while (isActiveBindEpoch(epoch) && !memberCacheLifetime.isCurrent(cacheEpoch))
}

/** Prefers identifier-only local pages; failure falls back to the existing shared permit pool. */
internal suspend fun ChatsController.loadForwardTargetMembers(
    account: String,
    pending: List<PresentedChatRowFfi>,
    epoch: Long,
    cacheEpoch: Long,
) {
    val projections = loadInitialMemberIdProjections(account, pending.map { it.row.groupIdHex })
    if (projections != null) {
        val byGroup = projections.associateBy { it.groupIdHex }
        pending.forEach { presentedRow ->
            val projection = byGroup[presentedRow.row.groupIdHex] ?: return@forEach
            applyForwardTargetMembers(
                presentedRow.row,
                memberRecordsFromIds(projection.memberIdsHex, boundAccountIdHex()),
                epoch,
                cacheEpoch,
            )
        }
    } else {
        coroutineScope {
            pending
                .map { presentedRow ->
                    async {
                        memberFetchGate.withPermit {
                            val current = isActiveBindEpoch(epoch) && memberCacheLifetime.isCurrent(cacheEpoch)
                            if (!current) return@withPermit
                            val row = presentedRow.row
                            if (!memberSnapshotNeedsFetch(row.groupIdHex)) return@withPermit
                            val members =
                                runCatchingCancellable { memberSnapshotLoader(account, row.groupIdHex) }.getOrNull()
                                    ?: return@withPermit
                            applyForwardTargetMembers(row, members, epoch, cacheEpoch)
                        }
                    }
                }.awaitAll()
        }
    }
}

/** Uses the native snapshot's conversation kind when the retained window has no context for a roster. */
internal fun ChatsController.applyForwardTargetMembers(
    row: ChatListRowFfi,
    members: List<AppGroupMemberRecordFfi>,
    epoch: Long,
    cacheEpoch: Long,
) {
    applyFetchedMemberSnapshot(
        groupIdHex = row.groupIdHex,
        members = members,
        epoch = epoch,
        cacheEpoch = cacheEpoch,
        directConversationOverride =
            isDirectConversationRosterCandidate(row.conversationKind, members, row.groupName),
        requestProfileRefresh = false,
    )
}

internal fun ChatsController.retryMemberSnapshots(groupIds: Iterable<String>) {
    val targets = groupIds.distinct().toList()
    targets.forEach(::cancelMemberSnapshotRetry)
    if (failedMemberFetches.removeAll(targets.toSet())) memberSnapshotsRevision += 1L
    schedulePendingMemberFetches(targets)
}

/** Starts bounded roster reads stamped with both binding and cache lifetimes. */
internal fun ChatsController.schedulePendingMemberFetches(groupIds: Iterable<String> = chatRows.map { it.groupIdHex }) {
    val account = accountRef ?: return
    val epoch = bindEpoch
    val cacheEpoch = memberCacheEpoch
    val liveGroupIds = chatRows.mapTo(mutableSetOf()) { it.groupIdHex }
    val pending =
        groupIds
            .asSequence()
            .distinct()
            .filter { it in liveGroupIds }
            .filter { memberSnapshotNeedsFetch(it) }
            .filterNot { it in inFlightMemberFetches }
            .filterNot { memberFetchRetryJobsByGroup[it]?.isActive == true }
            .filterNot { it in terminalMemberFetches }
            .toList()
    if (pending.isEmpty()) return
    if (failedMemberFetches.removeAll(pending.toSet())) memberSnapshotsRevision += 1L
    inFlightMemberFetches.addAll(pending)
    pending.forEach { groupIdHex ->
        recomputeScope.launch {
            try {
                memberFetchGate.withPermit {
                    if (!isActiveBindEpoch(epoch)) return@withPermit
                    val members = memberSnapshotLoader(account, groupIdHex)
                    applyFetchedMemberSnapshot(
                        groupIdHex = groupIdHex,
                        members = members,
                        epoch = epoch,
                        cacheEpoch = cacheEpoch,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (throwable: Throwable) {
                chatsDebug(throwable) {
                    "member fetch failed group=${groupIdHex.take(8)}: " +
                        (throwable.message ?: throwable.javaClass.simpleName)
                }
                if (isActiveBindEpoch(epoch)) {
                    markMemberSnapshotFetchFailed(groupIdHex, throwable)
                    scheduleMemberSnapshotRetry(groupIdHex, epoch)
                }
            } finally {
                // Only mutate the in-flight set if this job still
                // belongs to the current bind. A later bind() has
                // already cleared the set; removing again would be
                // a no-op but obscures the invariant.
                if (isActiveBindEpoch(epoch)) {
                    inFlightMemberFetches.remove(groupIdHex)
                    if (
                        !memberCacheLifetime.isCurrent(cacheEpoch) &&
                        memberSnapshotNeedsFetch(groupIdHex)
                    ) {
                        // The chat list can be hidden behind an open
                        // conversation while the system-share picker is
                        // visible. Retry the invalidated targeted fetch
                        // directly: recompute() deliberately skips member
                        // fan-out while hidden, and the picker's effect key
                        // is unchanged until a roster lands.
                        schedulePendingMemberFetches(listOf(groupIdHex))
                    }
                }
            }
        }
    }
}

/** Publishes a roster only while both its binding and invalidation tokens remain current. */
internal fun ChatsController.applyFetchedMemberSnapshot(
    groupIdHex: String,
    members: List<AppGroupMemberRecordFfi>,
    epoch: Long,
    cacheEpoch: Long,
    scheduleRecomputeAfterPublish: Boolean = true,
    directConversationOverride: Boolean? = null,
    requestProfileRefresh: Boolean = true,
) {
    if (!isActiveBindEpoch(epoch) || !memberCacheLifetime.isCurrent(cacheEpoch)) return
    val activeAccountIdHex = boundAccountIdHex() ?: appState.activeAccount?.accountIdHex
    val knownSelfRemoval = knownSelfRemovalFor(groupIdHex)
    val directConversationCandidate =
        directConversationOverride ?: directConversationCandidateFor(
            groupIdHex,
            members,
        )
    val selfOnlyDirectRoster =
        isSelfOnlyDirectRoster(
            members = members,
            directConversation = directConversationCandidate,
            activeAccountIdHex = activeAccountIdHex,
        )
    if (
        !memberSnapshotReadyToCache(
            members = members,
            knownSelfRemoval = knownSelfRemoval,
            directConversation = directConversationCandidate,
            activeAccountIdHex = activeAccountIdHex,
            selfOnlyDirectGraceElapsed = groupIdHex in selfOnlyDirectGraceRetryGroups,
        )
    ) {
        if (selfOnlyDirectRoster) selfOnlyDirectGraceRetryGroups.add(groupIdHex)
        markMemberSnapshotFetchFailed(groupIdHex)
        scheduleMemberSnapshotRetry(groupIdHex, epoch)
        return
    }
    if (requestProfileRefresh) {
        members.map { it.memberIdHex }.filter { it.isNotBlank() }.forEach(appState::requestProfile)
    }
    memberCacheByGroup = memberCacheByGroup + (groupIdHex to members)
    failedMemberFetches.remove(groupIdHex)
    presentationMembersByGroup = presentationMembersByGroup - groupIdHex
    cancelMemberSnapshotRetry(groupIdHex)
    memberFetchRetryBackoffTierByGroup.remove(groupIdHex)
    selfOnlyDirectGraceRetryGroups.remove(groupIdHex)
    memberSnapshotsRevision += 1L
    recordMemberDerivedLocalReadyIfComplete()
    // A loaded roster that omits self is known removal evidence (admin
    // eviction / self-leave the engine has already applied). Marking it
    // makes an empty self-only roster suppress the badge too, where the
    // snapshot path alone reads empty as ambiguous.
    if (activeAccountIdHex != null &&
        members.none { GroupProjector.isActiveAccountMember(it, activeAccountIdHex) }
    ) {
        removedGroupIds = removedGroupIds + groupIdHex
    }
    // Coalesce: a burst of member-fetch completions on account open/switch
    // would otherwise drive N un-debounced full recomputes. Defer into one.
    scheduleRecomputeIfRequested(scheduleRecomputeAfterPublish)
}

internal fun ChatsController.markMemberSnapshotFetchFailed(
    groupIdHex: String,
    throwable: Throwable? = null,
) {
    if (throwable != null && isTerminalMemberFetchFailure(throwable)) terminalMemberFetches += groupIdHex
    if (failedMemberFetches.add(groupIdHex)) memberSnapshotsRevision += 1L
}

internal fun ChatsController.scheduleMemberSnapshotRetry(
    groupIdHex: String,
    epoch: Long,
) {
    memberFetchRetryJobsByGroup[groupIdHex]?.let { existing ->
        if (existing.isActive) return
        memberFetchRetryJobsByGroup.remove(groupIdHex)
    }
    val backoffTier = memberFetchRetryBackoffTierByGroup.getOrDefault(groupIdHex, 0)
    val shouldRetry =
        isActiveBindEpoch(epoch) &&
            groupIdHex !in terminalMemberFetches &&
            memberSnapshotNeedsFetch(groupIdHex) &&
            chatRowsByGroup.containsKey(chatRowKey(groupIdHex))
    if (!shouldRetry) return
    memberFetchRetryBackoffTierByGroup[groupIdHex] =
        if (backoffTier >= MEMBER_FETCH_MAX_BACKOFF_TIER) {
            MEMBER_FETCH_MAX_BACKOFF_TIER
        } else {
            backoffTier + 1
        }
    val retryJob =
        recomputeScope.launch(start = CoroutineStart.LAZY) {
            val currentJob = coroutineContext[Job]
            val shouldFetch =
                try {
                    delay(memberSnapshotRetryDelay(backoffTier))
                    isActiveBindEpoch(epoch) &&
                        memberSnapshotNeedsFetch(groupIdHex) &&
                        chatRowsByGroup.containsKey(chatRowKey(groupIdHex))
                } finally {
                    if (memberFetchRetryJobsByGroup[groupIdHex] === currentJob) {
                        memberFetchRetryJobsByGroup.remove(groupIdHex)
                    }
                }
            if (shouldFetch) schedulePendingMemberFetches(listOf(groupIdHex))
        }
    memberFetchRetryJobsByGroup[groupIdHex] = retryJob
    retryJob.start()
}
