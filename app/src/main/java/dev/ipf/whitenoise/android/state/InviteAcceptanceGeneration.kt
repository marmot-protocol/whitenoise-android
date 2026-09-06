package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.SelfMembershipFfi

/** Immutable identity of the invitation generation rendered by a Join action. */
internal data class InviteAcceptanceGeneration(
    val groupIdHex: String,
    val welcomeMessageIdHex: String?,
)

/** Captured owner and projections required to settle one native Join attempt. */
internal data class InviteAcceptanceAttempt(
    val account: String,
    val generation: InviteAcceptanceGeneration,
    val previousGroup: AppGroupRecordFfi,
    val optimisticGroup: AppGroupRecordFfi,
    val authorityEpoch: Long,
)

/** Captures the exact group and Welcome generation represented by [group]. */
internal fun inviteGeneration(group: AppGroupRecordFfi): InviteAcceptanceGeneration =
    InviteAcceptanceGeneration(
        groupIdHex = group.groupIdHex,
        welcomeMessageIdHex = group.viaWelcomeMessageIdHex,
    )

/**
 * Accept only the still-pending generation that produced the clicked Join
 * action. A queued click from an older composition must not act on a later
 * re-invite for the same group.
 */
internal fun canAcceptRenderedInvite(
    group: AppGroupRecordFfi,
    rendered: InviteAcceptanceGeneration,
): Boolean =
    group.pendingConfirmation &&
        group.selfMembership == SelfMembershipFfi.MEMBER &&
        inviteGeneration(group) == rendered

/**
 * A successful native result belongs to [generation] only when it confirms
 * that exact Welcome. This prevents a late result from replacing a newer
 * authoritative generation with an accepted projection for the old one.
 */
internal fun acceptedInviteMatchesGeneration(
    accepted: AppGroupRecordFfi,
    generation: InviteAcceptanceGeneration,
): Boolean =
    !accepted.pendingConfirmation &&
        accepted.selfMembership == SelfMembershipFfi.MEMBER &&
        inviteGeneration(accepted) == generation

/** Immediate invite-bar projection while the local MDK confirmation is pending. */
internal fun optimisticAcceptedInvite(group: AppGroupRecordFfi): AppGroupRecordFfi =
    group.copy(
        archived = false,
        pendingConfirmation = false,
    )

/** Roll back only if no newer authoritative group projection replaced our optimistic value. */
internal fun rollbackOptimisticAcceptedInvite(
    current: AppGroupRecordFfi,
    optimistic: AppGroupRecordFfi,
    previous: AppGroupRecordFfi,
): AppGroupRecordFfi = if (current == optimistic) previous else current

/** Whether a canonical pending Welcome is a distinct generation that can re-add a terminal member. */
internal fun isDistinctWelcomeReinvite(
    previous: AppGroupRecordFfi,
    update: AppGroupRecordFfi,
): Boolean {
    val previousWelcome = previous.viaWelcomeMessageIdHex
    val updatedWelcome = update.viaWelcomeMessageIdHex
    return previous.selfMembership.isNonMember() &&
        update.selfMembership == SelfMembershipFfi.MEMBER &&
        update.pendingConfirmation &&
        !previousWelcome.isNullOrBlank() &&
        !updatedWelcome.isNullOrBlank() &&
        updatedWelcome != previousWelcome
}

/**
 * Reconcile independently delivered group snapshots without allowing a known
 * terminal membership to regress to MEMBER. The sole exception is a distinct,
 * nonblank canonical Welcome generation: pinned MDK treats that as a genuine
 * re-invite, whereas replaying the same or an absent Welcome is stale (#1248).
 */
internal fun reconcileTerminalSelfMembership(
    update: AppGroupRecordFfi,
    previous: AppGroupRecordFfi,
): AppGroupRecordFfi {
    val allowsReinvite = isDistinctWelcomeReinvite(previous, update)
    val selfMembership =
        update.selfMembership.takeIf { it.isNonMember() }
            ?: previous.selfMembership.takeIf { it.isNonMember() && !allowsReinvite }
            ?: update.selfMembership
    return update.copy(
        selfMembership = selfMembership,
        pendingConfirmation = update.pendingConfirmation && !selfMembership.isNonMember(),
    )
}

/**
 * Whether the engine refusal belongs to the unresolved generation. Same-
 * generation subscription replays stay retired while a distinct Welcome is
 * immediately allowed to surface as a new action.
 */
internal fun InviteAcceptanceGeneration.matches(group: AppGroupRecordFfi): Boolean = this == inviteGeneration(group)
