package dev.ipf.whitenoise.android.ui.chats.newchat

import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.state.AppliedGroupDetails
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.isNonMember

internal data class NewMessageDirectChatResolution(
    val item: ChatListItem?,
    val createRequired: Boolean,
)

/**
 * Local resolver for the New Message tap path (#1701). Provenance from the
 * picker row is revalidated against the current chat-list backing row and an
 * authoritative local roster read before it is opened or a fresh DM is
 * created. Picks without provenance retain the [existingDirectChat] fallback.
 */
internal suspend fun resolveNewMessageDirectChat(
    npub: String,
    existingDmGroupIdHex: String?,
    provenanceDirectChat: suspend (String?, String) -> NewMessageDirectChatResolution,
    existingDirectChat: suspend (String) -> NewMessageDirectChatResolution,
): NewMessageDirectChatResolution {
    val provenance = existingDmGroupIdHex?.trim()?.takeIf { it.isNotEmpty() }
    if (provenance != null) {
        val resolution = provenanceDirectChat(provenance, npub)
        if (resolution.item != null || !resolution.createRequired) return resolution
    }
    return existingDirectChat(npub)
}

/**
 * Scans current direct-chat candidates for the target. A stale/non-match
 * only rejects that group; any unavailable authoritative read fails closed if
 * no other candidate can be opened.
 */
internal suspend fun resolveExistingDirectChatCandidates(
    candidateGroupIds: List<String>,
    resolveCandidate: suspend (String) -> NewMessageDirectChatResolution,
): NewMessageDirectChatResolution {
    var unavailable = false
    candidateGroupIds.forEach { groupIdHex ->
        val resolution = resolveCandidate(groupIdHex)
        if (resolution.item != null) return resolution
        if (!resolution.createRequired) unavailable = true
    }
    return NewMessageDirectChatResolution(item = null, createRequired = !unavailable)
}

/**
 * Revalidates picker provenance against the controller's current backing row
 * and authoritative local group details (#825, #1701). It only allows creation
 * after both sources remain available and show that the provenance no longer
 * identifies an unnamed two-person DM with [targetReference].
 */
internal suspend fun existingDirectChatFromProvenance(
    provenanceGroupIdHex: String?,
    targetReference: String,
    activeAccountIdHex: String?,
    equivalentTarget: (other: String) -> Boolean,
    chatItemForGroup: (String) -> ChatListItem?,
    authoritativeGroupDetails: suspend (String) -> AppliedGroupDetails?,
    accountStillBound: () -> Boolean = { true },
): NewMessageDirectChatResolution {
    val provenance = provenanceGroupIdHex?.trim()?.takeIf { it.isNotEmpty() }
    val target = targetReference.trim().takeIf { it.isNotEmpty() }
    val initialItem = provenance?.let(chatItemForGroup)
    val details =
        if (provenance != null && target != null && initialItem != null) {
            authoritativeGroupDetails(provenance)
        } else {
            null
        }
    val currentItem =
        if (details != null && accountStillBound()) {
            provenance?.let(chatItemForGroup)
        } else {
            null
        }
    val identifiersMatch =
        provenance != null &&
            currentItem?.id?.equals(provenance, ignoreCase = true) == true &&
            details?.group?.groupIdHex?.equals(provenance, ignoreCase = true) == true
    val authoritativeMatch =
        identifiersMatch &&
            target != null &&
            details.matchesImplicitDmTarget(activeAccountIdHex, target, equivalentTarget)
    val item =
        currentItem?.takeIf {
            authoritativeMatch && isCurrentImplicitDmProjection(it, activeAccountIdHex)
        }
    val createRequired = identifiersMatch && !authoritativeMatch
    return NewMessageDirectChatResolution(item = item, createRequired = createRequired)
}

private fun AppliedGroupDetails.matchesImplicitDmTarget(
    activeAccountIdHex: String?,
    target: String,
    equivalentTarget: (other: String) -> Boolean,
): Boolean =
    !group.pendingConfirmation &&
        !group.leaveRequestPending &&
        !group.selfMembership.isNonMember() &&
        GroupProjector.isImplicitDmWith(
            members = members,
            name = group.name,
            activeAccountIdHex = activeAccountIdHex,
            targetIdHex = target,
            equivalentTarget = equivalentTarget,
        )

private fun isCurrentImplicitDmProjection(
    item: ChatListItem,
    activeAccountIdHex: String?,
): Boolean {
    val projection = item.projection
    return !item.group.pendingConfirmation &&
        !item.removedFromGroup(activeAccountIdHex) &&
        projection?.leaveRequestPending != true &&
        projection?.selfMembership?.isNonMember() != true &&
        !item.group.selfMembership.isNonMember() &&
        GroupProjector.isUnnamed(item.group.name)
}
