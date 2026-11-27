package dev.ipf.whitenoise.android.state

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.GroupDetailsFfi
import dev.ipf.marmotkit.GroupRosterFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.core.GroupProjector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Starts a roster read only while this controller still owns its account presentation. */
private fun ConversationController.beginMemberRosterRefresh(): Long? =
    synchronized(liveSubscriptionLock) {
        val resolvingElsewhere = inviteAcceptanceResolutionPending && inviteAuthorityReadInFlight
        val cannotRefresh = accountTeardownRequested || controllerCleared || resolvingElsewhere
        if (cannotRefresh) null else memberRosterRefreshGeneration.advance()
    }

/** Resolves conflicting list snapshots only while the opening controller still owns the read. */
private suspend fun ConversationController.resolveInitialInviteConfirmation(
    account: String,
    refreshGeneration: Long,
): Boolean {
    if (!inviteConfirmationUnresolved) return true
    val epoch = mutationPresentation.groupAuthorityEpoch
    val runtimeGeneration = appState.runtimeGeneration
    val canonical =
        try {
            inviteAuthorityReadInFlight = true
            liveSubscriptions.readInviteConfirmation(account, group.groupIdHex)
        } finally {
            inviteAuthorityReadInFlight = false
        }
    val currentRead = memberRosterRefreshGeneration.isCurrent(refreshGeneration)
    val ownsRead =
        ownsInviteAcceptanceResult &&
            epoch == mutationPresentation.groupAuthorityEpoch &&
            currentRead &&
            appState.runtimeGeneration == runtimeGeneration
    if (ownsRead) applyGroupState(canonical)
    // A newer canonical nonterminal update already resolved consent. Discard this
    // older snapshot, but let the same owner load a fresh roster for that group.
    val resolvedByUpdate = !inviteConfirmationUnresolved && group.acceptsInviteResults()
    return ownsCurrentMemberRead(refreshGeneration, runtimeGeneration) && (ownsRead || resolvedByUpdate)
}

// Publication holds the generation lock; never acquire liveSubscriptionLock here.
internal fun ConversationController.ownsCurrentMemberRead(
    generation: Long,
    runtimeGeneration: Int,
): Boolean =
    !controllerCleared &&
        !accountTeardownRequested &&
        memberRosterRefreshGeneration.isCurrent(generation) &&
        appState.runtimeGeneration == runtimeGeneration

/**
 * Publishes the latest authoritative roster while rejecting older refresh completions.
 * [automaticRetryAttempt] counts the bounded re-reads already spent by this chain (#2861).
 */
internal suspend fun ConversationController.refreshMembers(
    automaticRetryAttempt: Int = 0,
    prefetchedRoster: Deferred<Result<GroupRosterFfi>>? = null,
) {
    val account = conversationAccountRef ?: return
    val generation = beginMemberRosterRefresh() ?: return
    val runtimeGeneration = appState.runtimeGeneration
    val recoveringFromBlock = automaticRetryAttempt > 0 || rosterBlockReason != null
    if (inviteAcceptanceResolutionPending) inviteAcceptanceResolutionState = GroupRosterLoadState.LOADING
    memberRosterLoadTracker.transition(GroupRosterRefreshEvent.STARTED)
    try {
        runCatchingCancellable {
            readAndApplyMembers(account, generation, runtimeGeneration, prefetchedRoster)
        }.onSuccess {
            if (recoveringFromBlock && memberRosterState == GroupRosterLoadState.READY) {
                logRosterRead("recovered", kind = null, attempt = automaticRetryAttempt)
            }
        }.onFailure { failure ->
            if (ownsCurrentMemberRead(generation, runtimeGeneration)) {
                handleMemberReadFailure(account, generation, runtimeGeneration, failure, automaticRetryAttempt)
            }
        }
    } catch (cancel: CancellationException) {
        memberRosterRefreshGeneration.runIfCurrent(generation) {
            if (ownsCurrentMemberRead(generation, runtimeGeneration)) {
                memberRosterLoadTracker.restoreAfterCancellation()
                if (inviteAcceptanceResolutionPending) inviteAcceptanceResolutionState = GroupRosterLoadState.FAILED
            }
        }
        throw cancel
    }
}

/** Reads consent before members and never publishes a roster across a canonical group edge. */
private suspend fun ConversationController.readAndApplyMembers(
    account: String,
    generation: Long,
    runtimeGeneration: Int,
    prefetchedRoster: Deferred<Result<GroupRosterFfi>>?,
) {
    val resolvingConflict = inviteConfirmationUnresolved
    if (!resolveInitialInviteConfirmation(account, generation)) return
    val authorityEpoch = mutationPresentation.groupAuthorityEpoch
    // A prefetched roster can predate the consent check and must not settle that check.
    val roster =
        if (resolvingConflict) {
            groupRosterReader(account, group.groupIdHex)
        } else {
            prefetchedRoster?.await()?.getOrThrow() ?: groupRosterReader(account, group.groupIdHex)
        }
    memberRosterRefreshGeneration.runIfCurrent(generation) {
        if (ownsCurrentMemberRead(generation, runtimeGeneration)) {
            if (authorityEpoch != mutationPresentation.groupAuthorityEpoch) throw SupersededGroupRosterRead()
            val applied = applyGroupRoster(account, roster)
            if (applied != null) appState.applyLocalGroupDetails(account, applied.group, applied.members)
        }
    }
}

/**
 * Re-reads a retryable failure with bounded backoff while the read is still owned, then
 * exposes manual Retry. An account switch, newer read, disposal or runtime change wins.
 * A blocked transcript's backoff runs on [controllerScope], so it never holds the caller's
 * mutation lock or stalls the group-state stream; other retries keep the old inline re-read.
 */
private suspend fun ConversationController.handleMemberReadFailure(
    account: String,
    generation: Long,
    runtimeGeneration: Int,
    failure: Throwable,
    attempt: Int,
) {
    val kind = classifyGroupRosterReadFailure(failure)
    if (kind == GroupRosterReadFailureKind.SUPERSEDED && !group.acceptsInviteResults()) return
    val transcriptBlocked = !hasKnownTranscriptPresentation
    val retryDelay =
        groupRosterReadRetryDelayMs(kind, attempt, transcriptBlocked)
            .takeUnless { failure.isUseAfterEviction() }
    val backgroundBackoff = transcriptBlocked && kind != GroupRosterReadFailureKind.SUPERSEDED
    if (retryDelay != null) {
        logRosterRead("retry", kind, attempt)
        if (backgroundBackoff) {
            controllerScope.launch { retryMemberReadAfter(retryDelay, generation, runtimeGeneration, attempt) }
        } else {
            retryMemberReadAfter(retryDelay, generation, runtimeGeneration, attempt)
        }
    } else {
        settleMemberReadFailure(account, generation, runtimeGeneration, failure, attempt)
    }
}

/** Waits [delayMs], then re-reads only if the failed read is still the current owned read. */
private suspend fun ConversationController.retryMemberReadAfter(
    delayMs: Long,
    generation: Long,
    runtimeGeneration: Int,
    attempt: Int,
) {
    if (delayMs > 0L) delay(delayMs)
    if (ownsCurrentMemberRead(generation, runtimeGeneration)) {
        refreshMembers(automaticRetryAttempt = attempt + 1)
    }
}

/** Settles a failure atomically with its roster generation, without holding a lock over IO. */
private fun ConversationController.settleMemberReadFailure(
    account: String,
    generation: Long,
    runtimeGeneration: Int,
    failure: Throwable,
    attempt: Int,
) {
    memberRosterRefreshGeneration.runIfCurrent(generation) {
        if (!ownsCurrentMemberRead(generation, runtimeGeneration)) return@runIfCurrent
        if (failure.isUseAfterEviction()) {
            markActiveAccountRemovedFromMembers(account)
        } else {
            val kind = classifyGroupRosterReadFailure(failure)
            memberRosterLoadTracker.transition(GroupRosterRefreshEvent.FAILED)
            if (memberRosterState == GroupRosterLoadState.FAILED) {
                rosterBlockReason = GroupRosterBlockReason.ReadFailed(kind, attempts = attempt + 1)
            }
            if (inviteAcceptanceResolutionPending) inviteAcceptanceResolutionState = GroupRosterLoadState.FAILED
            logRosterRead("failed", kind, attempt)
            if (BuildConfig.DEBUG) Log.w("DMConversation", "refresh members failed", failure)
        }
    }
}

/** Logs a roster read branch with gate facts only, never account, group or member IDs. */
private fun ConversationController.logRosterRead(
    event: String,
    kind: GroupRosterReadFailureKind?,
    attempt: Int,
) {
    Log.w(
        "DMConversation",
        groupRosterReadDiagnostic(
            event = event,
            kind = kind,
            attempt = attempt,
            transcriptBlocked = !hasKnownTranscriptPresentation,
            initialSnapshot = initialMemberSnapshot != null,
            targetAccountActive = conversationAccountRef == appState.activeAccountRef,
        ),
    )
}

/** Applies the engine's eviction proof and invalidates any in-flight group mutation result. */
internal fun ConversationController.markActiveAccountRemovedFromMembers(account: String) {
    val activeAccountIdHex = conversationAccountIdHex ?: return
    mutationPresentation.authoritativeGroupApplied()
    inviteConfirmationAuthority.invalidate()
    observedBlockedLeave = false
    cancelledLeaveAwaitingRoster = false
    // Engine-confirmed removal (UseAfterEviction). Record the same
    // local-left marker the leaveGroup() success path sets so
    // a later authoritative roster result that races ahead of eviction can't re-add
    // self (issue #787).
    recordSelfLeft(settledDeparture = true)
    members = selfMembership.rosterHonoringSelfLeft(members, activeAccountIdHex)
    acceptedInvitePeerAccount = null
    membersLoaded = true
    membersVerified = true
    inviteAcceptanceAwaitingAuthority = null
    inviteConfirmationUnresolved = false
    memberRosterLoadTracker.transition(GroupRosterRefreshEvent.SUCCEEDED)
    rosterBlockReason = null
    // UseAfterEviction is the engine's authoritative signal that this
    // conversation can no longer accept composer writes. Invalidate an
    // in-flight immutable dictation target before any late provider result
    // can reach the draft persistence path.
    appState.conversationDictation.onTargetRemoved(account, group.groupIdHex)
    appState.cacheGroupMemberSnapshot(account, group.groupIdHex, members)
}

/** Latches the roster's read-only state and revokes transcript scopes when the departure settles. */
internal fun ConversationController.recordSelfLeft(settledDeparture: Boolean) {
    selfMembership.recordSelfLeft(accountStaysOnRoster = group.isDeviceRemovedCopy())
    if (settledDeparture) {
        boundAccountRef?.let { account ->
            appState.receivedVoiceTranscripts.updateGroupMembership(account, group.groupIdHex, departed = true)
        }
    }
}

private fun ConversationController.applyGroupDetails(
    account: String,
    details: GroupDetailsFfi,
    freshLeaveReadback: Boolean,
): AppliedGroupDetails? {
    val resolution = resolveAuthoritativeGroupRoster(details, conversationAccountIdHex)
    return applyResolvedGroupRoster(
        account = account,
        resolution = resolution,
        returnedRowCount = details.members.size,
        returnedMembership = details.group.selfMembership,
        settledDeparture = details.group.hasSettledDeparture(),
        freshLeaveReadback = freshLeaveReadback,
    )
}

private fun ConversationController.applyGroupRoster(
    account: String,
    roster: GroupRosterFfi,
): AppliedGroupDetails? {
    val resolution = resolveAuthoritativeGroupRoster(group, roster, conversationAccountIdHex)
    return applyResolvedGroupRoster(
        account = account,
        resolution = resolution,
        returnedRowCount = roster.members.size,
        returnedMembership = roster.selfMembership,
        // The lightweight roster can report LEFT before the leave request settles.
        settledDeparture = roster.selfMembership.isGroupEviction(),
    )
}

/** Applies a verified roster, or records the failed invariant that keeps the transcript gated. */
private fun ConversationController.applyResolvedGroupRoster(
    account: String,
    resolution: GroupRosterResolution,
    returnedRowCount: Int,
    returnedMembership: SelfMembershipFfi,
    settledDeparture: Boolean,
    freshLeaveReadback: Boolean = false,
): AppliedGroupDetails? {
    val previousRetention = group.disappearingMessageSecs
    val previousGroup = group
    resolution.invariant?.let { invariant ->
        memberRosterLoadTracker.transition(GroupRosterRefreshEvent.INCONSISTENT)
        rosterBlockReason = GroupRosterBlockReason.Inconsistent(invariant)
        logGroupRosterInvariant(
            resolution = resolution,
            invariant = invariant,
            returnedRowCount = returnedRowCount,
            returnedMembership = returnedMembership,
        )
        return null
    }
    val applied = resolution.applied
    val restoredAfterCancel = reconcileBlockedLeaveReadback(resolution, freshLeaveReadback)
    mutationPresentation.authoritativeGroupApplied()
    val reconciled =
        if (restoredAfterCancel) applied.group else reconcileTerminalSelfMembership(applied.group, previousGroup)
    group = inviteConfirmationAuthority.reconcile(reconciled, establishesAcceptance = false)
    if (previousRetention != group.disappearingMessageSecs) {
        publishTimelineFromIndexes()
    }
    // The reconciled group record carries the newest terminal self-membership
    // observed by any snapshot. When it reports a non-member (evicted or
    // voluntarily left), latch the self-left marker so the composer goes
    // read-only and the roster line below drops self — no longer relying
    // solely on the UseAfterEviction string-match round-trip.
    if (group.selfMembership.isNonMember()) {
        recordSelfLeft(settledDeparture = settledDeparture)
    }
    // Once a self-leave has been recorded locally, refuse to re-add self
    // from a details round-trip that still predates the engine eviction —
    // otherwise the full roster (self included) would restore the member
    // count and re-enable the composer right after a leave (issue #787).
    members = selfMembership.rosterHonoringSelfLeft(applied.members, conversationAccountIdHex)
    if (
        members.any { member ->
            member.memberIdHex.isNotBlank() &&
                !GroupProjector.isActiveAccountMember(member, conversationAccountIdHex)
        }
    ) {
        acceptedInvitePeerAccount = null
    }
    membersLoaded = true
    membersVerified = true
    inviteAcceptanceAwaitingAuthority = null
    if (!inviteAcceptanceResolutionPending) inviteAcceptanceResolutionState = GroupRosterLoadState.READY
    memberRosterLoadTracker.transition(GroupRosterRefreshEvent.SUCCEEDED)
    rosterBlockReason = null
    cacheAppliedGroupMembers(appState, account, group.groupIdHex, members)
    return AppliedGroupDetails(group = group, members = members)
}

/** Retires a provisional leave only after the current native roster proves the outcome. */
private fun ConversationController.reconcileBlockedLeaveReadback(
    resolution: GroupRosterResolution,
    freshLeaveReadback: Boolean,
): Boolean {
    val incoming = resolution.applied.group
    if (incoming.leaveBlocked) observedBlockedLeave = true
    val restored =
        freshLeaveReadback &&
            observedBlockedLeave &&
            resolution.containsLocalMember &&
            incoming.isJoinedAfterLeave() &&
            !group.selfMembership.isGroupEviction()
    val settled =
        incoming.selfMembership.isGroupEviction() ||
            incoming.disbanded ||
            (freshLeaveReadback && incoming.hasSettledDeparture() && !resolution.containsLocalMember)
    if (restored || settled) {
        observedBlockedLeave = false
        cancelledLeaveAwaitingRoster = false
    }
    if (restored) selfMembership.clearSelfLeft()
    return restored
}

private fun ConversationController.logGroupRosterInvariant(
    resolution: GroupRosterResolution,
    invariant: GroupRosterInvariant,
    returnedRowCount: Int,
    returnedMembership: SelfMembershipFfi,
) {
    fun normalizedIds(rows: List<AppGroupMemberRecordFfi>): Set<String> =
        rows
            .mapNotNull { row ->
                row.memberIdHex
                    .trim()
                    .takeIf(String::isNotEmpty)
                    ?.lowercase()
            }.toSet()

    val returnedIds = normalizedIds(resolution.applied.members)
    // Before the first successful details read, `members` is the chat-list /
    // shared snapshot seed. Compare sets here, but log counts only: member IDs
    // and the group ID are intentionally excluded from this diagnostic.
    val cachedIds = normalizedIds(members)
    val cachedContainsLocal =
        members.any { member ->
            conversationAccountIdHex?.let { accountId ->
                member.memberIdHex.equals(accountId, ignoreCase = true)
            } ?: member.local
        }
    Log.e(
        "DMConversation",
        "joined roster invariant" +
            " reason=${invariant.name.lowercase()}" +
            " rows=$returnedRowCount" +
            " unique=${resolution.uniqueMemberCount}" +
            " mls=${resolution.mlsMemberCount}" +
            " cached_unique=${cachedIds.size}" +
            " overlap=${returnedIds.intersect(cachedIds).size}" +
            " cached_only=${cachedIds.subtract(returnedIds).size}" +
            " returned_only=${returnedIds.subtract(cachedIds).size}" +
            " returned_self=${resolution.containsLocalMember}" +
            " cached_self=$cachedContainsLocal" +
            " current_membership=${group.selfMembership.name.lowercase()}" +
            " returned_membership=${returnedMembership.name.lowercase()}",
    )
}

/** Returns whether committed details were applied, invalidating any roster refresh launched earlier. */
internal fun ConversationController.applyMutationDetails(
    account: String,
    details: GroupDetailsFfi,
    freshLeaveReadback: Boolean = false,
): Boolean {
    memberRosterRefreshGeneration.advance()
    val applied = applyGroupDetails(account, details, freshLeaveReadback) ?: return false
    appState.applyLocalGroupDetails(account, applied.group, applied.members)
    return true
}
