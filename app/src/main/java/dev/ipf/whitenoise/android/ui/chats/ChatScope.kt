package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.isNonMember

/** Top-level UI views; stored folders retain their existing rules and archived-source authority. */
internal enum class ChatScope { Chats, Left }

/** Left/removed evidence and native accepted leave intent are distinct from a disband-only terminal group. */
internal fun ChatListItem.hasEndedMembership(activeAccountIdHex: String?): Boolean {
    if (activeAccountIdHex.isNullOrBlank()) return false
    return when {
        group.selfMembership.isNonMember() || projection?.selfMembership?.isNonMember() == true -> true
        projection?.leaveRequestPending == true || removed -> true
        projection?.lifecycleState == GroupLifecycleStateFfi.DISBANDED || projection?.disbanding == true -> false
        else -> removedFromGroup(activeAccountIdHex)
    }
}

/** Selects only existing native rows, preserving order and references; archived folder rules take precedence. */
internal fun chatScopeSource(
    scope: ChatScope,
    activeItems: List<ChatListItem>,
    archivedItems: List<ChatListItem>,
    showArchived: Boolean,
    activeAccountIdHex: String?,
): List<ChatListItem> =
    when {
        showArchived -> archivedItems
        scope == ChatScope.Left ->
            activeItems.filter {
                !it.group.archived && it.hasEndedMembership(activeAccountIdHex)
            }
        else -> activeItems
    }
