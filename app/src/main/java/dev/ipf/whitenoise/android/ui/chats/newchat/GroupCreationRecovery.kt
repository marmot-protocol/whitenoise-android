package dev.ipf.whitenoise.android.ui.chats.newchat

import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.core.RecipientSearch

/** A failed, unaccepted attempt owns only the captured selection, never native membership. */
internal data class GroupCreationRecovery(
    val attempt: Int,
    val submittedMembers: List<String>,
    val recipient: RecipientSearch.Candidate?,
) {
    /** A stale failure cannot edit a newer roster, attempt, account, or accepted group. */
    fun removableRecipient(
        currentAttempt: Int,
        members: List<RecipientSearch.Candidate>,
        owner: GroupCreationSession,
        canonicalId: String?,
    ): RecipientSearch.Candidate? =
        recipient?.takeIf {
            owner.isCurrent() && canonicalId == null && currentAttempt == attempt &&
                members.map { member -> member.accountIdHex } == submittedMembers
        }
}

/** Only typed identities supplied by MDK and present in the immutable submission permit removal. */
internal fun groupCreationRecovery(
    error: Throwable,
    attempt: Int,
    members: List<RecipientSearch.Candidate>,
): GroupCreationRecovery {
    val account =
        when (error) {
            is MarmotKitException.MissingKeyPackage -> error.account.trim()
            is MarmotKitException.MissingMemberInboxRoute -> error.account.trim()
            else -> null
        }
    return GroupCreationRecovery(
        attempt,
        members.map { it.accountIdHex },
        members.singleOrNull { account != null && it.accountIdHex == account },
    )
}
