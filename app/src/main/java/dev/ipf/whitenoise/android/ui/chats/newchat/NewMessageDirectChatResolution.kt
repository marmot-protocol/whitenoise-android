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
    existingDirectChat: (String) -> ChatListItem?,
): NewMessageDirectChatResolution {
    val provenance = existingDmGroupIdHex?.trim()?.takeIf { it.isNotEmpty() }
    if (provenance != null) {
        // A provenance result is authoritative. Do not reopen the same stale
        // group through the cache-dependent fallback that this path exists to
        // avoid, and do not create if the local source-of-truth read failed.
        return provenanceDirectChat(provenance, npub)
    }
    val item = existingDirectChat(npub)
    return NewMessageDirectChatResolution(item = item, createRequired = item == null)
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
    if (provenance == null || target == null || chatItemForGroup(provenance) == null) {
        return NewMessageDirectChatResolution(item = null, createRequired = false)
    }
    val details =
        authoritativeGroupDetails(provenance)
            ?: return NewMessageDirectChatResolution(item = null, createRequired = false)
    if (!accountStillBound()) {
        return NewMessageDirectChatResolution(item = null, createRequired = false)
    }
    val currentItem =
        chatItemForGroup(provenance)
            ?: return NewMessageDirectChatResolution(item = null, createRequired = false)
    val authoritativeGroup = details.group
    if (
        !currentItem.id.equals(provenance, ignoreCase = true) ||
        !authoritativeGroup.groupIdHex.equals(provenance, ignoreCase = true)
    ) {
        return NewMessageDirectChatResolution(item = null, createRequired = false)
    }
    val authoritativeMatch =
        !authoritativeGroup.pendingConfirmation &&
            !authoritativeGroup.leaveRequestPending &&
            !authoritativeGroup.selfMembership.isNonMember() &&
            GroupProjector.isImplicitDmWith(
                members = details.members,
                name = authoritativeGroup.name,
                activeAccountIdHex = activeAccountIdHex,
                targetIdHex = target,
                equivalentTarget = equivalentTarget,
            )
    if (!authoritativeMatch) {
        return NewMessageDirectChatResolution(item = null, createRequired = true)
    }
    val item = currentItem.takeIf { isCurrentImplicitDmProjection(it, activeAccountIdHex) }
    return NewMessageDirectChatResolution(item = item, createRequired = false)
}

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
