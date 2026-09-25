package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ChatListAnchorOutcomeFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.ChatListWindowSnapshotFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.PresentedChatRowFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import java.util.Locale

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
internal fun ChatListRowFfi.belongsInActiveChats(): Boolean =
    !archived &&
        !pendingConfirmation &&
        selfMembership == SelfMembershipFfi.MEMBER &&
        !leaveRequestPending &&
        !disbanding &&
        lifecycleState != GroupLifecycleStateFfi.DISBANDED

/** Match MDK's pin, activity, and group-ID page order when deciding whether a row can fall off. */
internal fun ChatListWindowSnapshotFfi.shouldContain(row: ChatListRowFfi): Boolean {
    val last = rows.lastOrNull()?.row
    return when {
        hasMoreBefore || anchor != ChatListAnchorOutcomeFfi.Top -> false
        !hasMoreAfter || last == null -> true
        row.pinned != last.pinned -> row.pinned
        row.pinned && last.pinned && row.pinnedPosition != last.pinnedPosition ->
            (row.pinnedPosition ?: UInt.MAX_VALUE) < (last.pinnedPosition ?: UInt.MAX_VALUE)
        row.activitySortAt != last.activitySortAt -> row.activitySortAt > last.activitySortAt
        else -> row.groupIdHex.lowercase(Locale.ROOT) < last.groupIdHex.lowercase(Locale.ROOT)
    }
}
