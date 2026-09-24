package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ChatListAnchorOutcomeFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.ChatListWindowSnapshotFfi
import dev.ipf.marmotkit.PresentedChatRowFfi
import dev.ipf.marmotkit.SelfMembershipFfi

/** Ends the current window set so the controller's bounded reconnect path obtains a new authoritative frame. */
internal class IncompleteChatListReplacement : IllegalStateException("incomplete active chat-list replacement")

/**
 * Rows worth checking against MDK before a top-of-list replacement removes them.
 * A shifted or paged window may legitimately omit older rows; only rows that
 * still rank inside its retained top window are candidates in that case.
 */
internal fun missingActiveTopChatRows(
    previous: Collection<ChatListRowFfi>,
    incoming: List<PresentedChatRowFfi>,
    activeWindow: ChatListWindowSnapshotFfi?,
): List<ChatListRowFfi> {
    if (activeWindow == null || activeWindow.hasMoreBefore || activeWindow.anchor != ChatListAnchorOutcomeFfi.Top) {
        return emptyList()
    }
    val incomingIds = incoming.mapTo(HashSet()) { it.row.groupIdHex.lowercase() }
    return previous.filter { row ->
        row.groupIdHex.lowercase() !in incomingIds &&
            row.belongsInActiveChats() &&
            activeWindow.shouldContain(row)
    }
}

/** The keyed MDK row is authoritative for archive, leave and deletion transitions. */
internal fun ChatListRowFfi.belongsInActiveChats(): Boolean = !archived && selfMembership == SelfMembershipFfi.MEMBER

/** Conservative ranking: ties are left to MDK, while a strictly earlier row cannot fall off the top page. */
internal fun ChatListWindowSnapshotFfi.shouldContain(row: ChatListRowFfi): Boolean {
    if (hasMoreBefore || anchor != ChatListAnchorOutcomeFfi.Top) return false
    if (!hasMoreAfter) return true
    val last = rows.lastOrNull()?.row ?: return true
    if (row.pendingConfirmation != last.pendingConfirmation) return row.pendingConfirmation
    if (row.pinned != last.pinned) return row.pinned
    if (row.pinned && last.pinned) {
        val position = row.pinnedPosition ?: UInt.MAX_VALUE
        val lastPosition = last.pinnedPosition ?: UInt.MAX_VALUE
        return position < lastPosition
    }
    return row.activitySortAt > last.activitySortAt
}
