package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ChatListAnchorOutcomeFfi
import dev.ipf.marmotkit.ChatListViewFfi
import dev.ipf.marmotkit.ChatListWindowSnapshotFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatListReplacementGuardTest {
    private val pinnedDm = chatRow("pinned-dm").copy(pinned = true, pinnedPosition = 0u)
    private val group = chatRow("active-group").copy(activitySortAt = 90uL)
    private val tail = chatRow("tail").copy(activitySortAt = 20uL)

    @Test
    fun completeTopReplacementChecksMissingPinnedDmAndGroup() {
        val incoming = listOf(presentedRow(tail.groupIdHex))
        val candidates = missingActiveTopChatRows(listOf(pinnedDm, group), incoming, topWindow(incoming))

        assertEquals(listOf("pinned-dm", "active-group"), candidates.map { it.groupIdHex })
    }

    @Test
    fun boundedPageChecksOnlyRowsThatStillRankAheadOfItsTail() {
        val older = chatRow("older-group").copy(activitySortAt = 10uL)
        val incoming = listOf(presentedRow(tail.groupIdHex).copy(row = tail))
        val candidates =
            missingActiveTopChatRows(
                listOf(pinnedDm, group, older),
                incoming,
                topWindow(incoming, hasMoreAfter = true),
            )

        assertEquals(listOf("pinned-dm", "active-group"), candidates.map { it.groupIdHex })
        assertFalse(topWindow(incoming, hasMoreAfter = true).shouldContain(older))
    }

    @Test
    fun shiftedWindowDoesNotTreatNormalPagingAsChatLoss() {
        val incoming = listOf(presentedRow(tail.groupIdHex))
        val shifted = topWindow(incoming, hasMoreBefore = true)

        assertTrue(missingActiveTopChatRows(listOf(pinnedDm, group), incoming, shifted).isEmpty())
    }

    @Test
    fun authoritativeArchiveAndDepartureAreRealRemovals() {
        assertFalse(pinnedDm.copy(archived = true).belongsInActiveChats())
        assertFalse(group.copy(selfMembership = SelfMembershipFfi.LEFT).belongsInActiveChats())
        assertFalse(group.copy(selfMembership = SelfMembershipFfi.REMOVED).belongsInActiveChats())
        assertTrue(group.belongsInActiveChats())
    }

    private fun topWindow(
        rows: List<dev.ipf.marmotkit.PresentedChatRowFfi>,
        hasMoreBefore: Boolean = false,
        hasMoreAfter: Boolean = false,
    ) = ChatListWindowSnapshotFfi(
        subscriptionGeneration = "test",
        sequence = 1uL,
        view = ChatListViewFfi.CHATS,
        rows = rows,
        hasMoreBefore = hasMoreBefore,
        hasMoreAfter = hasMoreAfter,
        anchor = ChatListAnchorOutcomeFfi.Top,
    )
}
