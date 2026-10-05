package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import java.util.UUID

/**
 * Short-lived presentation intent for a roster mutation that MDK has not
 * reconciled yet. The authoritative [ConversationController.members] and
 * [ConversationController.group] values are never changed by this overlay.
 */
internal sealed interface OptimisticGroupRosterMutation {
    data class Invite(
        val memberRefs: List<String>,
    ) : OptimisticGroupRosterMutation

    data class Remove(
        val memberIdHex: String,
    ) : OptimisticGroupRosterMutation

    data class SetAdmin(
        val memberIdHex: String,
        val admin: Boolean,
    ) : OptimisticGroupRosterMutation
}

internal suspend fun canonicalGroupInviteRefs(
    memberRefs: List<String>,
    resolveAccountIdHex: suspend (String) -> String?,
): List<String> {
    val canonicalRefs = mutableListOf<String>()
    val seenInputs = mutableSetOf<String>()
    val seenAccountIds = mutableSetOf<String>()
    memberRefs.forEach { rawRef ->
        val memberRef = rawRef.trim()
        if (memberRef.isEmpty() || !seenInputs.add(memberRef)) return@forEach
        val accountIdHex =
            resolveAccountIdHex(memberRef)
                ?: throw IllegalArgumentException("Invalid member reference")
        if (seenAccountIds.add(accountIdHex.lowercase())) canonicalRefs += accountIdHex
    }
    return canonicalRefs
}

internal fun projectedGroupMembers(
    authoritativeMembers: List<AppGroupMemberRecordFfi>,
    mutation: OptimisticGroupRosterMutation?,
): List<AppGroupMemberRecordFfi> =
    if (mutation is OptimisticGroupRosterMutation.Remove) {
        authoritativeMembers.filterNot {
            it.memberIdHex.equals(mutation.memberIdHex, ignoreCase = true)
        }
    } else {
        authoritativeMembers
    }

internal fun pendingGroupInviteRefs(
    authoritativeMembers: List<AppGroupMemberRecordFfi>,
    mutation: OptimisticGroupRosterMutation?,
): List<String> {
    val refs = (mutation as? OptimisticGroupRosterMutation.Invite)?.memberRefs.orEmpty()
    if (refs.isEmpty()) return emptyList()
    val memberIds = authoritativeMembers.map { it.memberIdHex.lowercase() }.toSet()
    return refs.filterNot { it.lowercase() in memberIds }
}

internal fun projectedGroupAdmin(
    authoritativeAdmin: Boolean,
    memberIdHex: String,
    mutation: OptimisticGroupRosterMutation?,
): Boolean =
    (mutation as? OptimisticGroupRosterMutation.SetAdmin)
        ?.takeIf { it.memberIdHex.equals(memberIdHex, ignoreCase = true) }
        ?.admin
        ?: authoritativeAdmin

/** Immutable local action identity; never an authoritative timeline row or permission grant. */
internal data class PendingGroupMembershipActivity(
    val id: String,
    val mutation: OptimisticGroupRosterMutation,
)

/** Projects against the latest authoritative roster and rejects completion from superseded attempts. */
internal class OptimisticGroupRosterMutationTracker(
    private val onMembershipStarted: (String) -> Unit = {},
    private val onMembershipSettled: (String) -> Unit = {},
) {
    var current by mutableStateOf<OptimisticGroupRosterMutation?>(null)
        private set

    var pendingMembershipActivity by mutableStateOf<PendingGroupMembershipActivity?>(null)
        private set

    private val mutations = StalenessGuard()

    /** Retires only this invitation/removal presentation; late results cannot retire a newer attempt. */
    fun settleMembershipActivity(id: String?) {
        if (id != null && pendingMembershipActivity?.id == id) {
            pendingMembershipActivity = null
            onMembershipSettled(id)
        }
    }

    /** Projects [mutation] until its own completion, without clearing a newer mutation. */
    suspend fun <T> track(
        mutation: OptimisticGroupRosterMutation,
        block: suspend () -> T,
    ): T {
        val token =
            mutations.advance {
                current = mutation
                pendingMembershipActivity =
                    if (mutation is OptimisticGroupRosterMutation.SetAdmin) {
                        null
                    } else {
                        PendingGroupMembershipActivity(UUID.randomUUID().toString(), mutation).also {
                            onMembershipStarted(it.id)
                        }
                    }
            }
        return try {
            block()
        } finally {
            mutations.runIfCurrent(token) {
                current = null
                settleMembershipActivity(pendingMembershipActivity?.id)
            }
        }
    }
}
