package dev.ipf.whitenoise.android.state

import androidx.annotation.StringRes
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.GroupDetailsFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.GroupProjector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Resolve membership under the native commit lock before selecting a departure branch. */
internal suspend fun ChatsController.departCapturedChat(
    attempt: ChatListDepartureAttempt,
    deleteAfterLeave: Boolean,
): Boolean =
    appState.withGroupCommitLock(attempt.account, attempt.groupId) {
        if (!attempt.isCurrent()) return@withGroupCommitLock false
        val row = appState.marmotIo { chatListRow(attempt.account, attempt.groupId) }
        if (!attempt.isCurrent()) return@withGroupCommitLock false
        when {
            row == null -> departWithoutChatRow(attempt, deleteAfterLeave)
            row.leaveRequestPending -> {
                foldChatRow(row)
                appState.presentTransient(
                    R.string.toast_leave_not_confirmed_history_kept,
                    kind = TransientNoticeKind.Information,
                )
                false
            }
            row.selfMembership.isNonMember() -> {
                foldChatRow(row)
                attempt.cleanupOnly = true
                attempt.onStage(ChatDepartureStage.LEFT)
                true
            }
            else -> departJoinedChat(attempt, deleteAfterLeave)
        }
    }

/** A wiped app projection does not prove that the native MLS group has been left. */
internal suspend fun ChatsController.departWithoutChatRow(
    attempt: ChatListDepartureAttempt,
    deleteAfterLeave: Boolean,
): Boolean {
    val details = chatDepartureGroupDetails(attempt.account, attempt.groupId)
    if (!attempt.isCurrent()) return false
    return if (details == null) {
        attempt.cleanupOnly = true
        true
    } else {
        departJoinedChat(attempt, deleteAfterLeave, details)
    }
}

/** Only an exact native UnknownGroup proves absence; transport and hydration failures preserve history. */
internal suspend fun ChatsController.chatDepartureGroupDetails(
    account: String,
    groupIdHex: String,
): GroupDetailsFfi? =
    try {
        appState.marmotIo { groupDetails(account, groupIdHex) }
    } catch (failure: MarmotKitException.UnknownGroup) {
        if (!failure.groupIdHex.equals(groupIdHex, ignoreCase = true)) throw failure
        null
    }

/** Read roles at the commit boundary; Android never invents a roster after a failed native read. */
internal suspend fun ChatsController.departJoinedChat(
    attempt: ChatListDepartureAttempt,
    deleteAfterLeave: Boolean,
    preparedDetails: GroupDetailsFfi? = null,
): Boolean {
    val details = preparedDetails ?: appState.marmotIo { groupDetails(attempt.account, attempt.groupId) }
    if (!attempt.isCurrent()) return false
    val resolution = resolveAuthoritativeGroupRoster(details, attempt.activeAccountId)
    check(details.group.groupIdHex.equals(attempt.groupId, ignoreCase = true) && resolution.invariant == null) {
        "Authoritative group roster unavailable"
    }
    return departResolvedChat(attempt, deleteAfterLeave, resolution)
}

/**
 * Leaves a chat whose authoritative roster [resolution] has been read. A durable leave request already pending
 * stays provisional, so it keeps the history and reports that the leave is not confirmed yet.
 */
internal suspend fun ChatsController.departResolvedChat(
    attempt: ChatListDepartureAttempt,
    deleteAfterLeave: Boolean,
    resolution: GroupRosterResolution,
): Boolean {
    val group = resolution.applied.group
    if (group.leaveRequestPending) {
        appState.presentTransient(
            R.string.toast_leave_not_confirmed_history_kept,
            kind = TransientNoticeKind.Information,
        )
        return false
    }
    attempt.members = resolution.applied.members
    val count = resolution.uniqueMemberCount
    val sole = GroupProjector.shouldDissolveAsSoleMember(attempt.members, attempt.activeAccountId)
    return when {
        !chatListDepartureAllowed(group, attempt.activeAccountId, count, sole) -> false
        sole -> {
            removeSoleMemberChat(attempt, deleteAfterLeave)
            true
        }
        GroupProjector.isAdminRef(group, attempt.activeAccountId) ->
            appState.leaveAsAdmin(attempt.account, attempt.groupId) { demoteAndLeaveChat(attempt) }
        else -> appState.leaveWithAdmission(attempt.account, attempt.groupId)
    }
}

/**
 * Steps down as admin, asking first if MDK holds that back, then leaves, even if the chat list goes away. The
 * question stays cancellable, so a departure cancelled while the user decides changes nothing; once the step-down
 * starts, it and the leave after it run to completion together.
 */
internal suspend fun ChatsController.demoteAndLeaveChat(attempt: ChatListDepartureAttempt) {
    appState.runGroupChange(
        attempt.account,
        attempt.groupId,
        actionLabel = R.string.group_action_step_down_and_leave,
    ) {
        withContext(NonCancellable) {
            val demotion =
                appState.marmotIo(MarmotTraceSection.SELF_DEMOTE_ADMIN) {
                    selfDemoteAdminDetailed(attempt.account, attempt.groupId)
                }
            attempt.demoted = true
            if (attempt.isCurrent()) {
                attempt.onStage(ChatDepartureStage.ADMIN_DEMOTED)
                appState.applyLocalGroupUpdate(demotion.details.group)
            }
            appState.marmotIo { leaveGroup(attempt.account, attempt.groupId) }
        }
    }
}

internal fun ChatsController.chatListDepartureAllowed(
    group: AppGroupRecordFfi,
    activeAccountIdHex: String?,
    memberCount: Int,
    soleMember: Boolean,
): Boolean {
    val allowed = soleMember || GroupProjector.canLeaveGroup(group, activeAccountIdHex, memberCount)
    if (!allowed) {
        appState.present(R.string.toast_make_another_admin_before_leaving, R.string.toast_group_needs_admin)
    }
    return allowed
}

internal suspend fun ChatsController.removeSoleMemberChat(
    attempt: ChatListDepartureAttempt,
    deleteAfterLeave: Boolean,
) {
    if (!deleteAfterLeave) {
        appState.deleteGroupLocalWithClientCleanup(attempt.account, attempt.groupId)
        return
    }
    // Forget retires native MLS state. The existing durable platform journal makes trailing cleanup recoverable.
    var deferred = false
    val deleted =
        deleteGroupLocalFromChatList(
            attempt.groupId,
            notify = false,
            forgetProtocol = true,
            observer =
                LocalChatDeleteObserver(
                    onNativeCommitted = { attempt.onStage(ChatDepartureStage.CLEANUP_PENDING) },
                    onCleanupDeferred = { deferred = true },
                ),
        )
    attempt.soleMemberResult = deleted && !deferred
    if (attempt.soleMemberResult == true) attempt.onStage(ChatDepartureStage.COMPLETED)
}

/** Reject stale presentation and destructive session teardown before another departure step. */
internal fun ChatsController.chatListDepartureIsCurrent(
    account: String,
    epoch: Long,
    runtime: Int,
): Boolean = accountRef == account && isActiveBindEpoch(epoch) && departureSessionAvailable(account, runtime)

internal fun ChatsController.departureSessionAvailable(
    account: String,
    runtime: Int,
): Boolean {
    val ownsAccount = appState.activeAccountRef == account && appState.runtimeGeneration == runtime
    return ownsAccount && !appState.signOutInProgress && !appState.wipeInProgress
}

/** Confirm native departure before admitting cleanup; uncertainty always retains history. */
internal suspend fun ChatsController.finishChatListDeparture(
    attempt: ChatListDepartureAttempt,
    deleteAfterLeave: Boolean,
): Boolean {
    val confirmed =
        !deleteAfterLeave || confirmChatListDeparture(attempt.account, attempt.groupId, attempt.isCurrent)
    if (!confirmed) return false
    attempt.onStage(ChatDepartureStage.LEFT)
    recordChatListDeparture(attempt.account, attempt.groupId, attempt.activeAccountId, attempt.members)
    return if (deleteAfterLeave) {
        completeDepartedChatCleanup(attempt.groupId, attempt.onStage)
    } else {
        appState.presentTransient(R.string.toast_left_chat)
        attempt.onStage(ChatDepartureStage.COMPLETED)
        true
    }
}

/** Native commit and trailing Android cleanup are distinct; deferred cleanup is not full completion. */
internal suspend fun ChatsController.completeDepartedChatCleanup(
    groupIdHex: String,
    onStage: (ChatDepartureStage) -> Unit,
): Boolean {
    onStage(ChatDepartureStage.CLEANUP_PENDING)
    var deferred = false
    val deleted =
        deleteGroupLocalFromChatList(
            groupIdHex,
            wording = LocalRemovalWording.Delete.copy(failureTitle = R.string.toast_left_chat_delete_failed),
            observer = LocalChatDeleteObserver(onCleanupDeferred = { deferred = true }),
        )
    return (deleted && !deferred).also { if (it) onStage(ChatDepartureStage.COMPLETED) }
}

/** A durable leave request remains provisional even after MDK projects LEFT. */
internal suspend fun ChatsController.confirmChatListDeparture(
    account: String,
    groupIdHex: String,
    isCurrent: () -> Boolean,
): Boolean {
    // An evicted MLS session can reject roster reads; use the durable native row.
    val departureRow =
        runCatchingCancellable {
            appState.marmotIo { chatListRow(account, groupIdHex) }
        }.getOrNull()
    if (!isCurrent()) return false
    departureRow?.let { foldChatRow(it) }
    val confirmed =
        departureRow != null && !departureRow.leaveRequestPending && departureRow.selfMembership.isNonMember()
    if (!confirmed) {
        appState.presentTransient(
            R.string.toast_leave_not_confirmed_history_kept,
            kind = TransientNoticeKind.Information,
        )
    }
    return confirmed
}

/** Retire presentation snapshots only after the native departure settles on the current bind. */
internal fun ChatsController.recordChatListDeparture(
    account: String,
    groupIdHex: String,
    activeAccountIdHex: String?,
    members: List<AppGroupMemberRecordFfi>,
) {
    appState.removeComposerExpansionForGroup(account, groupIdHex)
    // A removed device's account stays in the group through its other device, so its roster keeps the account.
    val accountStaysOnRoster =
        chatRowsByGroup[chatRowKey(groupIdHex)]?.selfMembership == SelfMembershipFfi.DEVICE_REMOVED
    if (!accountStaysOnRoster) appState.removeActiveAccountFromGroupMemberSnapshot(account, groupIdHex)
    if (activeAccountIdHex != null) {
        val departedMembers =
            if (accountStaysOnRoster) {
                members
            } else {
                GroupProjector.membersWithoutActiveAccount(members, activeAccountIdHex)
            }
        memberCacheByGroup = memberCacheByGroup + (groupIdHex to departedMembers)
        removedGroupIds = removedGroupIds + groupIdHex
        recompute()
    }
}

/** Recheck the captured native capability before the row can be hidden or local deletion admitted. */
internal suspend fun ChatsController.requireLocalDeleteEligibility(
    account: String,
    groupIdHex: String,
    required: Boolean,
    isCurrent: () -> Boolean,
) {
    if (!required) return
    val row =
        kotlinx.coroutines.withTimeoutOrNull(CHAT_LIST_BATCH_STEP_TIMEOUT_MS) {
            liveSubscriptions.presentedRowByGroup?.invoke(account, groupIdHex)
        }
    if (!isCurrent()) throw CancellationException("Account replaced")
    check(
        row != null &&
            row.row.groupIdHex.equals(groupIdHex, ignoreCase = true) &&
            row.actions.canDeleteLocal,
    ) { "Native local deletion is unavailable" }
}

/** Settles a committed removal, confirming it with [success] only once its Android cleanup also finished. */
internal fun ChatsController.completeChatLocalRemoval(
    account: String,
    groupIdHex: String,
    cleanupComplete: Boolean,
    @StringRes success: Int?,
    observer: LocalChatDeleteObserver,
) {
    appState.dismissLocalDeleteFailure(account, groupIdHex)
    removeChatRow(groupIdHex, removal = MembershipRemoval.LocalDeletion)
    finishRemovedChatRowClientState(groupIdHex)
    if (!cleanupComplete) observer.onCleanupDeferred()
    if (success != null && cleanupComplete) appState.presentTransient(success)
}

internal fun ChatsController.requireChatListDepartureCurrent(
    account: String,
    epoch: Long,
    runtime: Int,
) {
    if (!chatListDepartureIsCurrent(account, epoch, runtime)) throw CancellationException("Account replaced")
}

/**
 * Flip the chat-list row for [groupIdHex] to its left state after a leave
 * initiated from another surface (the conversation Details screen), where
 * the engine pushes no chat-list update for a self-leave so the row would
 * otherwise stay active until the next bind (issue #767).
 *
 * Mirrors the row-state updates the chat-list [leaveGroup] makes itself:
 * fold the group into [removedGroupIds] (the authoritative removal marker
 * [ChatListItem.removedFromGroup] honours regardless of the cached roster)
 * and, when a roster is already cached, drop self from it so the snapshot
 * agrees, then [recompute] so the row re-projects immediately. No-op when
 * no account is bound or the group isn't on this controller's chat list.
 */
internal fun ChatsController.markGroupLeft(groupIdHex: String) {
    if (accountRef == null) return
    if (chatRows.none { it.groupIdHex == groupIdHex }) return
    val activeAccountIdHex = appState.activeAccount?.accountIdHex
    memberCacheByGroup[groupIdHex]?.let { cached ->
        if (activeAccountIdHex != null) {
            memberCacheByGroup =
                memberCacheByGroup +
                (groupIdHex to GroupProjector.membersWithoutActiveAccount(cached, activeAccountIdHex))
        }
    }
    removedGroupIds = removedGroupIds + groupIdHex
    recompute()
}
