package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.R

internal val WhiteNoiseAppState.profileGroupPickerRevision: Long
    get() = chatsController?.memberSnapshotsRevision ?: 0L

internal val WhiteNoiseAppState.groupPresentationChatsController: ChatsController?
    get() = chatsController

/** Add to Groups candidates for [accountIdHex], with chats beyond the window from [accountWide]. */
internal fun WhiteNoiseAppState.profileAddableGroupsState(
    accountIdHex: String,
    accountWide: ProfileAccountWideGroups = ProfileAccountWideGroups(),
): ProfileGroupPickerState =
    chatsController?.profileAddableGroupsState(accountIdHex, activeAccount?.accountIdHex, accountWide)
        ?: ProfileGroupPickerState.empty()

/** Promote candidates for [accountIdHex], with chats beyond the window from [accountWide]. */
internal fun WhiteNoiseAppState.profilePromotableGroupsState(
    accountIdHex: String,
    accountWide: ProfileAccountWideGroups = ProfileAccountWideGroups(),
): ProfileGroupPickerState =
    chatsController?.profilePromotableGroupsState(accountIdHex, activeAccount?.accountIdHex, accountWide)
        ?: ProfileGroupPickerState.empty()

/** A non-started administration controller uses the existing authoritative mutation contract.
 * It owns no timeline subscriptions; accepted mutations are retained by launchMutation.
 */
internal suspend fun WhiteNoiseAppState.profileMembershipController(
    account: String,
    item: ChatListItem,
): ConversationController? {
    if (activeAccountRef != account || item.isDm()) return null
    val controller =
        ConversationController(
            appState = this,
            initialGroup = item.group,
            accountRefOverride = account,
        )
    var retained = false
    return try {
        runCatchingCancellable { controller.refreshManagementState() }.getOrThrow()
        controller.takeIf { activeAccountRef == account && it.managementState != null }?.also { retained = true }
    } finally {
        if (!retained) controller.onCleared()
    }
}

/** Makes [targetRef] an admin of [groupIdHex], asking first if MDK holds the change back. True once committed. */
suspend fun WhiteNoiseAppState.promoteProfileInGroup(
    targetRef: String,
    groupIdHex: String,
): Boolean {
    val target = targetRef.trim()
    val groupId = groupIdHex.trim()
    val account = activeAccountRef
    if (target.isEmpty() || groupId.isEmpty() || account == null) return false
    return runCatchingCancellable {
        // The lock is released only while the user decides, and held again for the acknowledgement and retry.
        withGroupCommitLock(account, groupId) {
            runGroupChange(
                account,
                groupId,
                actionLabel = R.string.make_admin,
                affectedMemberIds = listOf(target),
            ) {
                val result =
                    marmotIo(MarmotTraceSection.PROMOTE_ADMIN) {
                        promoteAdminDetailed(account, groupId, target)
                    }
                chatsController?.applyProfileGroupDetails(account, result.details)
            }
        }
        presentConversationTransient(
            accountRef = account,
            groupIdHex = groupId,
            titleRes = R.string.toast_admin_added,
        )
        true
    }.onFailure { error ->
        presentFailure(R.string.toast_couldnt_update_admin, "PROFILE_GROUP_ADMIN_UPDATE", error)
    }.getOrDefault(false)
}

/**
 * Add one viewed profile to one or more selected groups. The profile sheet
 * does the eligibility filtering (admin-only, not already a member); this
 * method keeps the commit-producing calls serialized per group and surfaces
 * a single result toast after the fan-out completes. Returns true only when
 * every attempted group accepted the invite, so partial failures can keep the
 * picker open with the failed groups still selected for retry. Choosing Wait on
 * one group's change question skips only that group and is reported as part of
 * the result; cancellation still stops the fan-out.
 */
@Suppress("LongMethod", "ReturnCount") // The fan-out and aggregate result share one failure accumulator.
suspend fun WhiteNoiseAppState.inviteProfileToGroups(
    targetRef: String,
    targetGroupIds: List<String>,
): Boolean {
    val ref = targetRef.trim().takeIf { it.isNotEmpty() } ?: return false
    val targets =
        targetGroupIds
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
    if (targets.isEmpty()) return false
    val account = activeAccountRef ?: return false
    val runtime = runtimeGeneration
    val stillCurrent = { activeAccountRef == account && runtimeGeneration == runtime }
    var failures = 0
    var waited = 0
    var firstFailure: AppText? = null
    var firstFailureThrowable: Throwable? = null
    for (groupIdHex in targets) {
        // A switch mid-fan-out ends it: the rest belonged to the previous account.
        if (!stillCurrent()) return false
        val attempt = inviteProfileToGroup(account, groupIdHex, ref)
        if (attempt == null) {
            waited += 1
            continue
        }
        attempt.onFailure { error ->
            failures += 1
            if (firstFailure == null) {
                firstFailureThrowable = error
                firstFailure = profileGroupInviteFailureDetail(error, ref)
            }
        }
    }
    if (!stillCurrent()) return false
    val outcome =
        ProfileGroupInviteOutcome(
            attempted = targets.size,
            failures = failures,
            firstFailure = firstFailure,
            waited = waited,
        )
    presentProfileGroupInvite(outcome, firstFailureThrowable)
    return outcome.completedSuccessfully
}

/** Shows the one toast that sums up a profile fan-out, with [failure]'s report when it is copyable. */
private fun WhiteNoiseAppState.presentProfileGroupInvite(
    outcome: ProfileGroupInviteOutcome,
    failure: Throwable?,
) {
    profileGroupInviteToast(outcome)?.let { toast ->
        if (toast.copyable && failure != null) {
            presentFailure(
                titleRes = toast.messageRes,
                operationCode = "PROFILE_GROUP_INVITE",
                throwable = failure,
                detail = toast.detail ?: AppText.Resource(R.string.error_try_again),
            )
        } else if (!toast.copyable) {
            presentTransient(toast.messageRes, toast.detail)
        } else if (toast.detail == null) {
            present(toast.messageRes, copyable = toast.copyable)
        } else {
            present(toast.messageRes, toast.detail, copyable = toast.copyable)
        }
    }
}

/**
 * Invites [ref] to one group of a profile fan-out. Returns null when the user chose to wait on that group's
 * change question, so only that group stays uninvited; cancellation still propagates and stops the fan-out.
 */
private suspend fun WhiteNoiseAppState.inviteProfileToGroup(
    account: String,
    groupIdHex: String,
    ref: String,
): Result<Unit>? =
    try {
        runCatchingCancellable {
            withGroupCommitLock(account, groupIdHex) {
                runGroupChange(
                    account,
                    groupIdHex,
                    invite = true,
                    actionLabel = R.string.group_action_add_members,
                    affectedMemberIds = listOf(ref),
                ) {
                    val result =
                        marmotIo(MarmotTraceSection.INVITE_MEMBERS) {
                            inviteMembersDetailed(account, groupIdHex, listOf(ref)).requireAcceptedInvite()
                        }
                    chatsController?.applyProfileGroupDetails(account, result.details)
                }
            }
        }.map { }
    } catch (_: GroupChangeDeclinedException) {
        null
    }

/** The detail the fan-out's toast gives for its first failed group, naming [ref] when it is about them. */
private fun WhiteNoiseAppState.profileGroupInviteFailureDetail(
    error: Throwable,
    ref: String,
): AppText =
    if (isDuplicateSignatureKeyError(error.readableMessage())) {
        AppText.Resource(R.string.toast_couldnt_add_member_duplicate_detail, listOf(displayName(ref)))
    } else {
        inviteFailureDetail(
            error,
            ::displayName,
            if (error is MarmotKitException.InvalidKeyPackageEvent) displayName(ref) else null,
        )
    }
