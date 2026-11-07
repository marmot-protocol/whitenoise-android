package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.ChatListWindowSnapshotFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.PresentedChatRowFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import java.util.Locale

/**
 * Rows worth checking against MDK before a window containing the list head removes them.
 * A retained/recovered viewport anchor can still have the true head loaded; only
 * [ChatListWindowSnapshotFfi.hasMoreBefore] means earlier rows were evicted. A bounded
 * top window may omit older rows only when they rank beyond its retained tail.
 */
internal fun missingActiveTopChatRows(
    previous: Collection<ChatListRowFfi>,
    incoming: List<PresentedChatRowFfi>,
    activeWindow: ChatListWindowSnapshotFfi?,
): List<ChatListRowFfi> {
    if (activeWindow == null || activeWindow.hasMoreBefore) {
        return emptyList()
    }
    val incomingIds = incoming.mapTo(HashSet()) { it.row.groupIdHex.lowercase() }
    return previous.filter { row ->
        row.groupIdHex.lowercase() !in incomingIds &&
            row.belongsInActiveChats() &&
            activeWindow.shouldContain(row)
    }
}

/**
 * Whether this previously shown active row left [window] by ranking outside its retained rows — ahead of a
 * shifted window's first row or past a bounded window's tail — rather than by a removal. Unknown windows and
 * rows the window should still hold are never treated as evicted.
 */
internal fun ChatListRowFfi.evictedFrom(window: ChatListWindowSnapshotFfi?): Boolean {
    val bounded = window ?: return false
    return belongsInActiveChats() && !bounded.shouldContain(this)
}

/** The keyed MDK row is authoritative for archive, leave and deletion transitions. */
internal fun ChatListRowFfi.belongsInActiveChats(): Boolean =
    !archived &&
        !pendingConfirmation &&
        selfMembership == SelfMembershipFfi.MEMBER &&
        !leaveRequestPending &&
        !disbanding &&
        lifecycleState != GroupLifecycleStateFfi.DISBANDED

/**
 * Whether [row] ranks inside this window's retained span in MDK's order. A window holding the true head has no
 * lower bound, and one holding the true tail has no upper bound. An empty window can only place a row when it
 * holds the head.
 */
internal fun ChatListWindowSnapshotFfi.shouldContain(row: ChatListRowFfi): Boolean {
    val first = rows.firstOrNull()?.row ?: return !hasMoreBefore
    val last = rows.last().row
    return (!hasMoreBefore || !row.ranksBefore(first)) && (!hasMoreAfter || row.ranksBefore(last))
}

/** MDK's chat-list page order: pins by position first, then newer activity, then ascending group ID. */
private fun ChatListRowFfi.ranksBefore(other: ChatListRowFfi): Boolean =
    when {
        pinned != other.pinned -> pinned
        pinned && pinnedPosition != other.pinnedPosition ->
            (pinnedPosition ?: UInt.MAX_VALUE) < (other.pinnedPosition ?: UInt.MAX_VALUE)
        activitySortAt != other.activitySortAt -> activitySortAt > other.activitySortAt
        else -> groupIdHex.lowercase(Locale.ROOT) < other.groupIdHex.lowercase(Locale.ROOT)
    }
