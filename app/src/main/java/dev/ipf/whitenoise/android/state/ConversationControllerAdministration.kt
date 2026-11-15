package dev.ipf.whitenoise.android.state

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.GroupDetailsFfi
import dev.ipf.marmotkit.GroupManagementStateFfi
import dev.ipf.marmotkit.GroupMutationResultFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.diagnostics.DmCreationAttempt
import dev.ipf.whitenoise.android.diagnostics.DmCreationPhase
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal fun ConversationController.canAdministerMembersFromAuthoritativeRoster(): Boolean =
    memberRosterState == GroupRosterLoadState.READY &&
        !isDm &&
        isSelfMember &&
        isSelfAdmin &&
        !group.pendingConfirmation &&
        !group.disbanding &&
        !group.disbanded

internal fun ConversationController.authoritativeAdministrationTarget(targetMemberIdHex: String): AppGroupMemberRecordFfi? {
    if (!canAdministerMembersFromAuthoritativeRoster() || targetMemberIdHex.isBlank()) return null
    return members.singleOrNull { member ->
        member.memberIdHex.equals(targetMemberIdHex, ignoreCase = true)
    }
}

internal fun ConversationController.presentRosterChanged(
    @StringRes title: Int,
) {
    lastMutationError =
        ErrorPresentation(
            message = AppText.Plain(copy.groupRosterChanged),
            report = "White Noise error report\noperation=GROUP_ROSTER_UPDATE\nerror=STALE_STATE",
        )
    appState.present(title, R.string.toast_group_roster_changed)
}

internal suspend fun ConversationController.resolveCanonicalInviteRefs(
    memberRefs: List<String>,
    diagnostic: DmCreationAttempt? = null,
): List<String>? =
    try {
        canonicalGroupInviteRefs(memberRefs, appState::resolveAccountIdHex)
    } catch (throwable: Throwable) {
        diagnostic?.failed(DmCreationPhase.RECIPIENTS, throwable)
        throwable.rethrowIfCancellation()
        recordMutationFailure(R.string.toast_couldnt_add_members, "GROUP_INVITE_RESOLVE", throwable)
        null
    }

internal fun ConversationController.presentAdminCommitOutcome(
    outcome: GroupAdministrationCommitOutcome,
    adminAdded: Boolean,
): Boolean =
    when (outcome) {
        GroupAdministrationCommitOutcome.COMMITTED -> {
            presentConversationTransient(
                if (adminAdded) R.string.toast_admin_added else R.string.toast_admin_removed,
            )
            true
        }
        GroupAdministrationCommitOutcome.ROSTER_CHANGED -> {
            presentRosterChanged(R.string.toast_couldnt_update_admin)
            false
        }
        GroupAdministrationCommitOutcome.KEEP_ONE_ADMIN -> {
            appState.present(
                R.string.toast_keep_one_admin,
                R.string.toast_promote_before_removing_admin,
            )
            false
        }
        GroupAdministrationCommitOutcome.NO_CHANGE -> false
    }

/** Keep error copy independent of native invitation and reconciliation ownership. */
internal fun ConversationController.presentInviteFailure(
    throwable: Throwable,
    refs: List<String>,
): Boolean {
    val rawMessage = throwable.message.orEmpty()
    return if (isDuplicateSignatureKeyError(rawMessage)) {
        // MLS rejected the add commit because the proposed member
        // already holds a seat (or their signing key collides with
        // an existing member's). The UI pre-checks membership, but a
        // race or key collision can still land here — surface plain
        // language with the resolved name instead of the raw
        // CreateCommitError(ProposalValidationError(...)) enum (#899).
        val name = duplicateSignatureKeyDisplayName(refs, appState::displayName)
        val friendly = copy.couldntAddMemberDuplicate(name)
        recordMutationFailure(
            R.string.toast_couldnt_add_members,
            "GROUP_INVITE_DUPLICATE_MEMBER",
            throwable,
            AppText.Plain(friendly),
        )
        false
    } else {
        recordMutationFailure(
            R.string.toast_couldnt_add_members,
            "GROUP_INVITE_MEMBER",
            throwable,
            inviteFailureDetail(
                throwable,
                appState::displayName,
                if (throwable is MarmotKitException.InvalidKeyPackageEvent) {
                    refs.singleOrNull()?.let(appState::displayName)
                } else {
                    null
                },
            ),
        )
        false
    }
}

/** A newer details read requires a fresh native snapshot after this commit, not its older result. */
internal suspend fun ConversationController.publishInitialAdminInviteResult(
    account: String,
    result: dev.ipf.marmotkit.GroupMutationResultFfi,
    authorityEpoch: Long,
    runtimeGeneration: Int,
) {
    if (appState.runtimeGeneration != runtimeGeneration || controllerCleared || isAccountTeardownRequested()) return
    if (mutationPresentation.groupAuthorityEpoch == authorityEpoch) {
        applyManagementSnapshot(account, result.details, result.managementState)
    } else {
        refreshManagementState()
    }
}

/** These typed key, routing, lookup and admission rejections happen before the native membership commit. */
private fun ConversationController.inviteReplyNeedsReconciliation(throwable: Throwable): Boolean =
    when (throwable) {
        is MarmotKitException.MissingKeyPackage,
        is MarmotKitException.MissingMemberInboxRoute,
        is MarmotKitException.InvalidKeyPackageEvent,
        is MarmotKitException.DirectoryLookupIncomplete,
        is MarmotKitException.GroupCatchingUp,
        is MarmotKitException.GroupCommitUndelivered,
        -> false
        else -> true
    }

/** Only an uncertain first invitation for absent recipients may reconcile by reading membership. */
internal fun ConversationController.canReconcileInviteReply(
    nativeInvoked: Boolean,
    absentBefore: Boolean,
    failure: Throwable,
): Boolean = nativeInvoked && absentBefore && inviteReplyNeedsReconciliation(failure)

/** Read actual membership after an uncertain reply, without issuing a second invitation. */
internal suspend fun ConversationController.reconcileInitialAdminInvite(
    refs: List<String>,
    admins: List<String>,
    absentBefore: Boolean,
    nativeInvoked: Boolean,
    failure: Throwable,
): Boolean {
    val eligible = canReconcileInviteReply(nativeInvoked, absentBefore, failure)
    if (!eligible) return false
    val beforeRead = mutationPresentation.groupAuthorityEpoch
    val applied = refreshManagementState()
    val observedMembers =
        membersVerified && refs.all { ref -> members.any { it.memberIdHex.equals(ref, ignoreCase = true) } }
    val observedAdmins = admins.all { ref -> group.admins.any { it.equals(ref, ignoreCase = true) } }
    return applied && mutationPresentation.groupAuthorityEpoch != beforeRead && observedMembers && observedAdmins
}

/** Native capabilities stay unknown when local terminal reconciliation changes their returned group. */
internal fun ConversationController.applyManagementSnapshot(
    account: String,
    details: GroupDetailsFfi,
    management: GroupManagementStateFfi,
): Boolean =
    androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
        val applied = applyMutationDetails(account, details, freshLeaveReadback = true)
        if (applied) {
            managementState = management.takeIf { group == details.group }
        }
        applied && managementState != null
    }

/** Called inside the existing commit lock; a failed read never reuses the previous permission frontier. */
internal suspend fun ConversationController.refreshRemovalAuthority(
    refresh: Boolean,
    ownsPresentation: () -> Boolean,
): Boolean {
    if (!refresh) return true
    val epoch = mutationPresentation.groupAuthorityEpoch
    val applied = refreshManagementState()
    return applied && mutationPresentation.groupAuthorityEpoch != epoch && ownsPresentation()
}

/**
 * Why ordinary outbound work is refused right now, or null when it may proceed: a blocked leave, or a group that is
 * disbanding or disbanded, the terminal states [canSendMessages] also refuses. Shared by every send path that does
 * not pass through the composer gate.
 */
@StringRes
internal fun ConversationController.outboundRefusal(): Int? =
    when {
        showsBlockedLeave -> blockedLeaveHint()
        group.disbanded -> R.string.conversation_disbanded_notice
        group.disbanding -> R.string.conversation_disbanding_notice
        else -> null
    }

/** Matches the blocked-leave notice: a cancelled or cleared block that only awaits readback asks for Refresh. */
@StringRes
internal fun ConversationController.blockedLeaveHint(): Int =
    when {
        blockedLeaveNeedsReadback -> R.string.group_leave_refresh_hint
        else -> R.string.group_leave_blocked_hint
    }

/** Check the action owner again under the native group lock before canceling or retrying readback. */
internal suspend fun ConversationController.cancelNativeBlockedLeave(
    account: String,
    groupId: String,
): Boolean =
    appState.withGroupCommitLock(account, groupId) {
        if (!ownsBlockedLeaveAction(account, groupId) || !canCancelBlockedLeave) return@withGroupCommitLock false
        if (!blockedLeaveNeedsReadback) {
            appState.marmotIo { cancelLeave(account, groupId) }
            memberRosterRefreshGeneration.advance()
            managementStateLifetime.advance()
            cancelledLeaveAwaitingRoster = observedBlockedLeave
        }
        true
    }

/** Reject work from a retired conversation, account or runtime. */
internal fun ConversationController.ownsBlockedLeaveAction(
    account: String,
    groupId: String,
): Boolean =
    acceptsConversationActionOwner(account, groupId) &&
        appState.activeAccountRef == account &&
        appState.runtimeGeneration == conversationRuntimeGeneration

/** Finish the already-granted transfer even if its caller is cancelled. */
internal suspend fun ConversationController.completeAdminTransfer(
    account: String,
    target: String,
) {
    withContext(NonCancellable) {
        val demoteResult =
            try {
                appState.runGroupChange(
                    account,
                    group.groupIdHex,
                    actionLabel = R.string.transfer_admin,
                    affectedMemberIds = listOf(target),
                ) {
                    appState.marmotIo(MarmotTraceSection.SELF_DEMOTE_ADMIN) {
                        selfDemoteAdminDetailed(account, group.groupIdHex)
                    }
                }
            } catch (declined: GroupChangeDeclinedException) {
                // Waiting leaves both admins: report the partial transfer, not a quiet cancel.
                throw declined.refusal
            }
        applyMutationDetails(account, demoteResult.details)
    }
}
