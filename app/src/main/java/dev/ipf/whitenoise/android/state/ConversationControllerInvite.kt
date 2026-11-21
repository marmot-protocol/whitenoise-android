package dev.ipf.whitenoise.android.state

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.GroupProjector
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Resolves the native Join call while every retry remains fenced to the
 * same rendered Welcome and controller owner.
 */
internal suspend fun ConversationController.resolveInviteAcceptance(attempt: InviteAcceptanceAttempt): AppGroupRecordFfi? =
    runCatching {
        retryIdempotentRuntimeMutation(
            onTransientFailure = { attemptNumber ->
                Log.w(
                    "DMConversation",
                    "invite acceptance temporarily unavailable; retrying " +
                        "failedAttempt=$attemptNumber",
                )
            },
        ) {
            if (ownsInviteAttempt(attempt)) {
                inviteAcceptor(attempt.account, attempt.generation.groupIdHex)
            } else {
                null
            }
        }
    }.getOrElse { failure ->
        if (failure is MarmotKitException.GroupInviteNotPending) {
            if (ownsInviteAttempt(attempt)) {
                inviteConfirmationAuthority.finish()
                group = group.copy(pendingConfirmation = false)
                inviteAcceptanceAwaitingAuthority = attempt.generation
                appState.applyLocalGroupUpdate(group, attempt.account)
                refreshMembers()
            }
            return@getOrElse null
        }
        val currentGroup = group
        group = inviteConfirmationAuthority.rollback(currentGroup)
        val ownsPresentation = ownsInviteAcceptanceResult
        if (ownsPresentation && group != currentGroup) appState.applyLocalGroupUpdate(group, attempt.account)
        failure.rethrowIfCancellation()
        if (ownsPresentation) {
            appState.presentFailure(R.string.toast_couldnt_accept_invite, "GROUP_INVITE_ACCEPT", failure)
        }
        null
    }

/** Keeps metadata refreshed during Join while committing confirmation for its current Welcome. */
internal fun ConversationController.applyAcceptedInviteGroup(
    acceptedGroup: AppGroupRecordFfi,
    authorityEpochBefore: Long,
) {
    val confirmed =
        if (mutationPresentation.groupAuthorityEpoch == authorityEpochBefore) {
            acceptedGroup
        } else {
            group.copy(pendingConfirmation = false)
        }
    group = inviteConfirmationAuthority.reconcile(confirmed)
    inviteConfirmationAuthority.finish()
}

/** True while the open profile and Welcome still own the decline that was rendered. */
internal fun ConversationController.ownsRenderedDecline(invitation: InviteAcceptanceGeneration): Boolean =
    ownsRetainedForegroundPresentation &&
        canAcceptRenderedInvite(group, invitation)

/**
 * Declines the current pending Welcome, stepping down first only when [stepDownConsent] names this invitation.
 * Without that consent an admin invitee, or a decline MDK refuses for an admin, raises the step-down question.
 */
internal suspend fun ConversationController.declineInviteWithConsent(
    stepDownConsent: InviteAcceptanceGeneration?,
    rendered: InviteAcceptanceGeneration? = null,
): Boolean =
    withMutationLockResult(false) {
        if (inviteAcceptanceResolutionPending) return@withMutationLockResult false
        val account = conversationAccountRef ?: return@withMutationLockResult false
        val invitation = rendered ?: inviteGeneration(group)
        if (rendered != null && !ownsRenderedDecline(rendered)) return@withMutationLockResult false
        val stepDownAgreed = stepDownConsent != null
        if (requestDeclineConsentIfNeeded(invitation, stepDownConsent)) return@withMutationLockResult false
        val stillPending = {
            mutationPresentation.ownsCurrentPresentation() &&
                (rendered == null || ownsRenderedDecline(rendered)) &&
                !inviteAcceptanceResolutionPending &&
                group.pendingConfirmation &&
                inviteGeneration(group) == invitation
        }
        runCatching {
            when (declinePendingInvite(account, invitation.groupIdHex, stepDownAgreed, stillPending)) {
                InviteDeclineOutcome.DECLINED -> Unit
                InviteDeclineOutcome.STALE -> return@runCatching false
                InviteDeclineOutcome.NEEDS_STEP_DOWN_CONSENT -> {
                    inviteDeclineStepDownRequest = invitation
                    return@runCatching false
                }
            }
            appState.dismissConversationNotifications(account, invitation.groupIdHex)
            if (mutationPresentation.ownsCurrentPresentation() && inviteGeneration(group) == invitation) {
                group = group.copy(pendingConfirmation = false, archived = true)
                appState.applyLocalGroupUpdate(group, account)
                presentConversationTransient(R.string.toast_invite_declined)
            }
            true
        }.getOrElse { failure ->
            failure.rethrowIfCancellation()
            presentInviteDeclineFailure(failure, invitation)
            false
        }
    }

/**
 * Stops Decline for [invitation] when it must not go ahead yet, and returns whether it stopped. Consent counts
 * only for the invitation it named and is used up here, under the mutation lock. A sole admin cannot step down,
 * so asking would promise what MDK refuses: they get the existing guidance at once. Any other admin who has not
 * agreed yet gets the step-down question. MDK's own refusals stay the fallback when this copy of the roles is
 * stale.
 */
private fun ConversationController.requestDeclineConsentIfNeeded(
    invitation: InviteAcceptanceGeneration,
    stepDownConsent: InviteAcceptanceGeneration?,
): Boolean {
    inviteDeclineStepDownRequest = null
    val stop =
        when {
            stepDownConsent != null -> stepDownConsent != invitation
            isSoleAdminInvitee() -> {
                appState.present(R.string.toast_make_another_admin_before_leaving, R.string.toast_group_needs_admin)
                true
            }
            isSelfAdmin -> {
                inviteDeclineStepDownRequest = invitation
                true
            }
            else -> false
        }
    return stop
}

/**
 * Whether this account is the invitation's only admin, from MDK's admin list, which a pending invitation has
 * before its roster loads.
 */
private fun ConversationController.isSoleAdminInvitee(): Boolean = isSelfAdmin && GroupProjector.uniqueAdminCount(group) <= 1

/** Explains a failed decline, giving a sole admin the same guidance as Leave instead of a generic failure. */
private fun ConversationController.presentInviteDeclineFailure(
    failure: Throwable,
    invitation: InviteAcceptanceGeneration,
) {
    if (
        failure is MarmotKitException.WouldRemoveLastAdmin &&
        failure.groupIdHex.equals(invitation.groupIdHex, ignoreCase = true)
    ) {
        appState.present(R.string.toast_make_another_admin_before_leaving, R.string.toast_group_needs_admin)
    } else {
        appState.presentFailure(R.string.toast_couldnt_decline_invite, "GROUP_INVITE_DECLINE", failure)
    }
}

/**
 * Serializes decline with commits and keeps an admitted step-down and its decline together. MDK refusing an
 * admin's decline steps down only when [stepDownAgreed], otherwise the caller asks the user first.
 */
private suspend fun ConversationController.declinePendingInvite(
    account: String,
    groupIdHex: String,
    stepDownAgreed: Boolean,
    stillPending: () -> Boolean,
): InviteDeclineOutcome =
    appState.withGroupCommitLock(account, groupIdHex) {
        if (!stillPending()) return@withGroupCommitLock InviteDeclineOutcome.STALE
        try {
            appState.marmotIo { declineGroupInvite(account, groupIdHex) }
        } catch (refusal: MarmotKitException.AdminCannotSelfRemove) {
            if (!refusal.groupIdHex.equals(groupIdHex, ignoreCase = true)) throw refusal
            if (!stepDownAgreed) return@withGroupCommitLock InviteDeclineOutcome.NEEDS_STEP_DOWN_CONSENT
            if (!stillPending()) return@withGroupCommitLock InviteDeclineOutcome.STALE
            val declineResult =
                appState.runGroupChange(
                    account,
                    groupIdHex,
                    stillWanted = stillPending,
                    actionLabel = R.string.decline,
                ) {
                    withContext(NonCancellable) {
                        val demotion =
                            appState.marmotIo(MarmotTraceSection.SELF_DEMOTE_ADMIN) {
                                selfDemoteAdminDetailed(account, groupIdHex)
                            }
                        applyMutationDetails(account, demotion.details)
                        // Keep a failed decline from retrying a step-down that already succeeded.
                        runCatchingCancellable { appState.marmotIo { declineGroupInvite(account, groupIdHex) } }
                    }
                }
            declineResult.getOrThrow()
        }
        InviteDeclineOutcome.DECLINED
    }
