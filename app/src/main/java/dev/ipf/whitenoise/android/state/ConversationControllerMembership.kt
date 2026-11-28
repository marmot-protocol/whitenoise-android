package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.diagnostics.CreationOperation
import dev.ipf.whitenoise.android.diagnostics.DmCreationFailure
import dev.ipf.whitenoise.android.diagnostics.DmCreationInteraction
import dev.ipf.whitenoise.android.diagnostics.DmCreationOutcome
import dev.ipf.whitenoise.android.diagnostics.DmCreationPhase
import dev.ipf.whitenoise.android.diagnostics.invitationFailed
import dev.ipf.whitenoise.android.diagnostics.projectionFinished
import dev.ipf.whitenoise.android.diagnostics.traceInvitation

/**
 * Invites the captured recipients and their initial admin subset in one native membership commit.
 * [initialAdminRefs] must be a subset of [memberRefs]; its default preserves the older all-admin
 * [addAsAdmin] option while ordinary invitations default to members. Authority is rechecked inside
 * the group commit lock. An uncertain reply may be confirmed by a fresh authoritative read, but
 * never triggers another invitation or a separate promotion commit.
 */
@Suppress(
    "LongMethod", // One captured role selection, native commit and authoritative reconciliation.
    "TooGenericExceptionCaught",
)
suspend fun ConversationController.inviteMembers(
    memberRefs: List<String>,
    addAsAdmin: Boolean = false,
    initialAdminRefs: List<String> = if (addAsAdmin) memberRefs else emptyList(),
): Boolean {
    val diagnostic = DmCreationInteraction(operation = CreationOperation.GROUP_INVITE).nextAttempt()
    return diagnostic.traceInvitation {
        diagnostic.record(DmCreationPhase.ROSTER, DmCreationOutcome.START)
        withMutationLockResult(false) {
            lastMutationError = null
            if (!canAdministerMembersFromAuthoritativeRoster()) {
                diagnostic.record(DmCreationPhase.ROSTER, DmCreationOutcome.FAILURE, DmCreationFailure.UNKNOWN)
                return@withMutationLockResult false
            }
            diagnostic.record(DmCreationPhase.ROSTER, DmCreationOutcome.SUCCESS)
            val account = conversationAccountRef ?: return@withMutationLockResult false
            diagnostic.record(DmCreationPhase.RECIPIENTS, DmCreationOutcome.START)
            // The admin selection normally repeats references already resolved for this invitation.
            val resolved = mutableMapOf<String, String?>()
            val resolve: suspend (String) -> String? = { ref ->
                resolved.getOrPut(ref) { appState.resolveAccountIdHex(ref) }
            }
            val refs =
                resolveCanonicalInviteRefs(memberRefs, diagnostic, resolve) ?: return@withMutationLockResult false
            if (refs.isEmpty()) {
                diagnostic.record(DmCreationPhase.RECIPIENTS, DmCreationOutcome.SUCCESS)
                return@withMutationLockResult false
            }
            optimisticGroupRosterMutation.track(OptimisticGroupRosterMutation.Invite(refs)) {
                val adminTargets =
                    resolveCanonicalInviteRefs(initialAdminRefs, diagnostic, resolve) ?: return@track false
                diagnostic.record(DmCreationPhase.RECIPIENTS, DmCreationOutcome.SUCCESS)
                val activityId = pendingMembershipActivity?.id
                var nativeInvoked = false
                var nativeAccepted = false
                var absentBefore = false
                var skippedMembers: AppText? = null
                val runtimeGeneration = appState.runtimeGeneration
                try {
                    check(adminTargets.all { admin -> refs.any { it.equals(admin, ignoreCase = true) } }) {
                        "Initial admin selection contains an unselected recipient"
                    }
                    val outcome =
                        appState.withGroupCommitLock(account, group.groupIdHex) {
                            if (!canAdministerMembersFromAuthoritativeRoster()) {
                                return@withGroupCommitLock GroupAdministrationCommitOutcome.ROSTER_CHANGED
                            }
                            val authorityEpoch = mutationPresentation.groupAuthorityEpoch
                            diagnostic.record(DmCreationPhase.CREATE, DmCreationOutcome.START)
                            absentBefore =
                                refs.none { ref -> members.any { it.memberIdHex.equals(ref, ignoreCase = true) } }
                            nativeInvoked = true
                            val inviteResult =
                                initialAdminInviteWriter(account, group.groupIdHex, refs, adminTargets)
                            inviteResult.requireAcceptedInvite()
                            nativeAccepted = true
                            skippedMembers =
                                inviteResult.invite.memberOutcomes.skippedGroupMembersDetail(appState::displayName)
                            diagnostic.record(DmCreationPhase.CREATE, DmCreationOutcome.SUCCESS)
                            diagnostic.record(DmCreationPhase.PROJECTION, DmCreationOutcome.START)
                            publishInitialAdminInviteResult(
                                account,
                                inviteResult,
                                authorityEpoch,
                                runtimeGeneration,
                            )
                            diagnostic.projectionFinished(
                                appState.runtimeGeneration == runtimeGeneration &&
                                    !controllerCleared &&
                                    !isAccountTeardownRequested(),
                            )
                            optimisticGroupRosterMutation.settleMembershipActivity(activityId)
                            GroupAdministrationCommitOutcome.COMMITTED
                        }
                    if (outcome == GroupAdministrationCommitOutcome.ROSTER_CHANGED) {
                        diagnostic.record(
                            DmCreationPhase.ROSTER,
                            DmCreationOutcome.FAILURE,
                            DmCreationFailure.UNKNOWN,
                        )
                        presentRosterChanged(R.string.toast_couldnt_add_members)
                        return@track false
                    }
                    presentInviteAccepted(skippedMembers)
                    true
                } catch (throwable: Throwable) {
                    diagnostic.invitationFailed(throwable, nativeInvoked, nativeAccepted)
                    throwable.rethrowIfCancellation()
                    val reconcilable = canReconcileInviteReply(nativeInvoked, absentBefore, throwable)
                    if (reconcilable) {
                        diagnostic.record(DmCreationPhase.RECONCILIATION, DmCreationOutcome.START)
                        val absent = reconcileInitialAdminInvite(refs, adminTargets)
                        if (absent != null) {
                            diagnostic.record(DmCreationPhase.RECONCILIATION, DmCreationOutcome.SUCCESS)
                            presentInviteAccepted(absent.missingGroupMembersDetail(appState::displayName))
                            return@track true
                        }
                        diagnostic.record(
                            DmCreationPhase.RECONCILIATION,
                            DmCreationOutcome.FAILURE,
                            DmCreationFailure.UNKNOWN,
                        )
                    }
                    presentInviteFailure(throwable, refs)
                }
            }
        }
    }
}

/** Removes [member] from the group, asking first if MDK holds the change back. True once they are gone. */
@Suppress("TooGenericExceptionCaught")
suspend fun ConversationController.removeMember(
    member: AppGroupMemberRecordFfi,
    refreshAuthorityBeforeCommit: Boolean = false,
): Boolean =
    withMutationLockResult(false) {
        lastMutationError = null
        val account = conversationAccountRef ?: return@withMutationLockResult false
        val runtimeAtAdmission = appState.runtimeGeneration

        fun ownsRemovalPresentation(): Boolean =
            !refreshAuthorityBeforeCommit ||
                (appState.activeAccountRef == account && appState.runtimeGeneration == runtimeAtAdmission)
        // remove_members signs a roster update for a Nostr pubkey, so use the
        // stable member id. memberRef may be a local account label.
        val target = member.memberIdHex
        optimisticGroupRosterMutation.track(OptimisticGroupRosterMutation.Remove(target)) {
            try {
                val outcome =
                    appState.withGroupCommitLock(account, group.groupIdHex) {
                        if (!refreshRemovalAuthority(refreshAuthorityBeforeCommit, ::ownsRemovalPresentation)) {
                            return@withGroupCommitLock GroupAdministrationCommitOutcome.ROSTER_CHANGED
                        }
                        authoritativeAdministrationTarget(target)
                            ?: return@withGroupCommitLock GroupAdministrationCommitOutcome.ROSTER_CHANGED
                        val result =
                            appState.runGroupChange(
                                account,
                                group.groupIdHex,
                                actionLabel = R.string.remove_member,
                                affectedMemberIds = listOf(target),
                            ) {
                                appState.marmotIo(MarmotTraceSection.REMOVE_MEMBERS) {
                                    removeMembersDetailed(account, group.groupIdHex, listOf(target))
                                }
                            }
                        applyMutationDetails(account, result.details)
                        GroupAdministrationCommitOutcome.COMMITTED
                    }
                if (outcome == GroupAdministrationCommitOutcome.ROSTER_CHANGED) {
                    if (ownsRemovalPresentation()) presentRosterChanged(R.string.toast_couldnt_remove_member)
                    return@track false
                }
                if (ownsRemovalPresentation()) presentConversationTransient(R.string.toast_member_removed)
                true
            } catch (throwable: Throwable) {
                throwable.rethrowIfCancellation()
                // The commit may have persisted before a follow-on publish
                // failed. One authoritative details read is enough here; an MLS
                // replay probe would duplicate bridge work and does not affect
                // whether this specific target remains in the local roster.
                refreshMembers()
                if (members.none { it.memberIdHex.equals(target, ignoreCase = true) }) {
                    if (ownsRemovalPresentation()) presentConversationTransient(R.string.toast_member_removed)
                    true
                } else {
                    if (ownsRemovalPresentation()) {
                        recordMutationFailure(
                            R.string.toast_couldnt_remove_member,
                            "GROUP_REMOVE_MEMBER",
                            throwable,
                        )
                    }
                    false
                }
            }
        }
    }

/** Promotes or demotes [member], asking first if MDK holds the change back. True once committed. */
suspend fun ConversationController.setMemberAdmin(
    member: AppGroupMemberRecordFfi,
    admin: Boolean,
): Boolean =
    withMutationLockResult(false) {
        lastMutationError = null
        val account = conversationAccountRef ?: return@withMutationLockResult false
        // promote_admin / demote_admin sign the new admin list onto the MLS
        // group, so they require a Nostr pubkey hex — not a local-account
        // label. memberRef can return either; memberIdHex is always the hex.
        val target = member.memberIdHex
        if (!admin && isAdmin(member) && GroupProjector.revokeWouldDepleteAdmins(group, member, memberCount)) {
            appState.present(R.string.toast_keep_one_admin, R.string.toast_promote_before_removing_admin)
            return@withMutationLockResult false
        }
        optimisticGroupRosterMutation.track(OptimisticGroupRosterMutation.SetAdmin(target, admin)) {
            runCatchingCancellable {
                val outcome =
                    appState.withGroupCommitLock(account, group.groupIdHex) {
                        val currentMember =
                            authoritativeAdministrationTarget(target)
                                ?: return@withGroupCommitLock GroupAdministrationCommitOutcome.ROSTER_CHANGED
                        if (GroupProjector.isAdmin(group, currentMember) == admin) {
                            return@withGroupCommitLock GroupAdministrationCommitOutcome.NO_CHANGE
                        }
                        if (!admin && GroupProjector.revokeWouldDepleteAdmins(group, currentMember, memberCount)) {
                            return@withGroupCommitLock GroupAdministrationCommitOutcome.KEEP_ONE_ADMIN
                        }
                        if (admin) {
                            val result =
                                appState.runGroupChange(
                                    account,
                                    group.groupIdHex,
                                    actionLabel = R.string.make_admin,
                                    affectedMemberIds = listOf(target),
                                ) {
                                    appState.marmotIo(MarmotTraceSection.PROMOTE_ADMIN) {
                                        promoteAdminDetailed(account, group.groupIdHex, target)
                                    }
                                }
                            applyMutationDetails(account, result.details)
                        } else {
                            val result =
                                appState.runGroupChange(
                                    account,
                                    group.groupIdHex,
                                    actionLabel = R.string.remove_admin,
                                    affectedMemberIds = listOf(target),
                                ) {
                                    appState.marmotIo(MarmotTraceSection.DEMOTE_ADMIN) {
                                        demoteAdminDetailed(account, group.groupIdHex, target)
                                    }
                                }
                            applyMutationDetails(account, result.details)
                        }
                        GroupAdministrationCommitOutcome.COMMITTED
                    }
                presentAdminCommitOutcome(outcome, adminAdded = admin)
            }.onFailure {
                recordMutationFailure(R.string.toast_couldnt_update_admin, "GROUP_MEMBER_ADMIN_UPDATE", it)
            }.getOrDefault(false)
        }
    }

/** Steps this account down as admin while another admin remains, asking first if MDK holds it back. */
suspend fun ConversationController.stepDownAsAdmin(): Boolean =
    withMutationLockResult(false) {
        lastMutationError = null
        val account = conversationAccountRef ?: return@withMutationLockResult false
        val activeAccountIdHex = conversationAccountIdHex ?: return@withMutationLockResult false
        if (!GroupProjector.isAdminRef(group, activeAccountIdHex)) return@withMutationLockResult false
        if (group.admins.distinctBy { it.lowercase() }.size <= 1) {
            appState.present(R.string.toast_keep_one_admin, R.string.toast_promote_before_removing_admin)
            return@withMutationLockResult false
        }
        runCatchingCancellable {
            val outcome =
                appState.withGroupCommitLock(account, group.groupIdHex) {
                    if (!canAdministerMembersFromAuthoritativeRoster()) {
                        return@withGroupCommitLock GroupAdministrationCommitOutcome.ROSTER_CHANGED
                    }
                    if (group.admins.distinctBy { it.lowercase() }.size <= 1) {
                        return@withGroupCommitLock GroupAdministrationCommitOutcome.KEEP_ONE_ADMIN
                    }
                    val result =
                        appState.runGroupChange(
                            account,
                            group.groupIdHex,
                            actionLabel = R.string.step_down_as_admin,
                        ) {
                            appState.marmotIo(MarmotTraceSection.SELF_DEMOTE_ADMIN) {
                                selfDemoteAdminDetailed(account, group.groupIdHex)
                            }
                        }
                    applyMutationDetails(account, result.details)
                    GroupAdministrationCommitOutcome.COMMITTED
                }
            presentAdminCommitOutcome(outcome, adminAdded = false)
        }.onFailure {
            recordMutationFailure(R.string.toast_couldnt_update_admin, "GROUP_SELF_DEMOTE", it)
        }.getOrDefault(false)
    }

/**
 * Transfer admin to [member]: grant them admin, then step down ourselves.
 *
 * Ordering is enforced — grant first, self-demote second — so the group is
 * never momentarily left with no admin (which the engine would reject and
 * which would strand the sole-admin transfer the Leave-group flow depends
 * on, issue #417). If the grant succeeds but the self-demote fails, the
 * target keeps the admin rights they were just given and we surface a
 * partial-failure toast naming the mid-state rather than the generic
 * "couldn't update admin" copy. The caller (active account) must be an
 * admin; the target must not already be one.
 */
@Suppress("MaxLineLength")
suspend fun ConversationController.transferAdmin(member: AppGroupMemberRecordFfi): Boolean =
    withMutationLockResult(false) {
        lastMutationError = null
        val account = conversationAccountRef ?: return@withMutationLockResult false
        val activeAccountIdHex = conversationAccountIdHex
        // promote_admin / self_demote_admin sign the new admin list onto
        // the MLS group, so the grant target needs a Nostr pubkey hex, not
        // a local-account label. memberIdHex is always the hex.
        val target = member.memberIdHex
        if (!GroupProjector.canTransferAdminTo(group, member, activeAccountIdHex)) {
            // Already an admin, the active account isn't an admin, or the
            // target is the active account itself. Nothing to transfer.
            appState.present(R.string.toast_couldnt_update_admin, R.string.toast_cant_transfer_admin, copyable = true)
            return@withMutationLockResult false
        }
        // Tracks whether the grant landed before the self-demote attempt so
        // a self-demote failure reports the partial state honestly.
        var grantedBeforeDemote = false
        runCatchingCancellable {
            val transferCommitted =
                appState.withGroupCommitLock(account, group.groupIdHex) {
                    val currentMember =
                        authoritativeAdministrationTarget(target)
                            ?: return@withGroupCommitLock false
                    if (!GroupProjector.canTransferAdminTo(group, currentMember, activeAccountIdHex)) {
                        return@withGroupCommitLock false
                    }
                    val promoteResult =
                        appState.runGroupChange(
                            account,
                            group.groupIdHex,
                            actionLabel = R.string.transfer_admin,
                            affectedMemberIds = listOf(target),
                        ) {
                            appState.marmotIo(MarmotTraceSection.PROMOTE_ADMIN) {
                                promoteAdminDetailed(account, group.groupIdHex, target)
                            }
                        }
                    grantedBeforeDemote = true
                    applyMutationDetails(account, promoteResult.details)
                    // The grant has already landed on the MLS group. If the scope is
                    // cancelled now, skipping the demote would strand both accounts as
                    // admin without telling the caller (runCatchingCancellable propagates
                    // cancellation past the partial-state branch). Run it to completion.
                    completeAdminTransfer(account, target)
                    true
                }
            if (!transferCommitted) {
                appState.present(R.string.toast_couldnt_update_admin, R.string.toast_cant_transfer_admin, copyable = true)
                return@runCatchingCancellable false
            }
            presentConversationTransient(R.string.toast_admin_transferred)
            true
        }.onFailure {
            if (grantedBeforeDemote) {
                // Target is now an admin but we couldn't step down. Tell the user
                // so they can retry the step-down (or revoke the grant).
                recordMutationFailure(
                    R.string.toast_granted_but_couldnt_step_down,
                    "GROUP_ADMIN_TRANSFER_STEP_DOWN",
                    it,
                )
            } else {
                recordMutationFailure(R.string.toast_couldnt_update_admin, "GROUP_ADMIN_TRANSFER", it)
            }
        }.getOrDefault(false)
    }
