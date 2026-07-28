package dev.ipf.whitenoise.android.ui.chats.newchat

import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.isNonMember

internal data class NewMessageDirectChatResolution(
    val item: ChatListItem?,
    val createRequired: Boolean,
)

/**
 * Local, synchronous resolver for the New Message tap path (#1701). Provenance
 * from the picker row is revalidated against the current chat-list projection
 * before falling back to [existingDirectChat] or create.
 */
internal fun resolveNewMessageDirectChat(
    npub: String,
    existingDmGroupIdHex: String?,
    chatListItems: List<ChatListItem>,
    activeAccountIdHex: String?,
    npubForHex: (String) -> String,
    existingDirectChat: (String) -> ChatListItem?,
): NewMessageDirectChatResolution {
    val equivalentTarget = { other: String -> npubForHex(other).equals(npub, ignoreCase = true) }
    val item =
        existingDirectChatFromProvenance(
            provenanceGroupIdHex = existingDmGroupIdHex,
            targetReference = npub,
            chatListItems = chatListItems,
            activeAccountIdHex = activeAccountIdHex,
            equivalentTarget = equivalentTarget,
        ) ?: existingDirectChat(npub)
    return NewMessageDirectChatResolution(item = item, createRequired = item == null)
}

/**
 * Open an existing implicit DM using picker provenance, after strict local
 * revalidation (#825, #1701). Returns null when provenance is absent, stale, or
 * no longer an unnamed two-person DM with [targetReference].
 */
internal fun existingDirectChatFromProvenance(
    provenanceGroupIdHex: String?,
    targetReference: String,
    chatListItems: List<ChatListItem>,
    activeAccountIdHex: String?,
    equivalentTarget: (other: String) -> Boolean,
): ChatListItem? {
    val provenance = provenanceGroupIdHex?.trim()?.takeIf { it.isNotEmpty() }
    val target = targetReference.trim().takeIf { it.isNotEmpty() }
    if (provenance == null || target == null) return null
    return chatListItems
        .firstOrNull { it.id.equals(provenance, ignoreCase = true) }
        ?.takeIf {
            isValidImplicitDmChatListItem(
                item = it,
                activeAccountIdHex = activeAccountIdHex,
                targetReference = target,
                equivalentTarget = equivalentTarget,
            )
        }
}

internal fun isValidImplicitDmChatListItem(
    item: ChatListItem,
    activeAccountIdHex: String?,
    targetReference: String,
    equivalentTarget: (other: String) -> Boolean,
): Boolean {
    val projection = item.projection
    val members = item.memberSnapshot?.members
    val counterpart =
        item.otherMemberAccount?.takeIf { it.isNotBlank() }
            ?: item.latest?.sender?.takeIf { it.isNotBlank() }
            ?: item.group.welcomerAccountIdHex?.takeIf { it.isNotBlank() }
    return when {
        item.group.pendingConfirmation ||
            item.removedFromGroup(activeAccountIdHex) ||
            projection?.leaveRequestPending == true ||
            projection?.selfMembership?.isNonMember() == true ||
            item.group.selfMembership.isNonMember() -> false
        members != null ->
            GroupProjector.isImplicitDmWith(
                members = members,
                name = item.group.name,
                activeAccountIdHex = activeAccountIdHex,
                targetIdHex = targetReference,
                equivalentTarget = equivalentTarget,
            )
        else ->
            projection?.conversationKind == ChatConversationKindFfi.DIRECT &&
                GroupProjector.isUnnamed(item.group.name) &&
                (
                    // The candidate's group id already binds this target to the
                    // row. If the async roster projection disappeared between
                    // composition and tap, the engine's current DIRECT kind is
                    // the authoritative local confirmation that it remains a DM.
                    counterpart == null ||
                        counterpart.equals(targetReference, ignoreCase = true) ||
                        equivalentTarget(counterpart)
                )
    }
}
