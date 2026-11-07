package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListAnchorOutcomeFfi
import dev.ipf.marmotkit.ChatListViewFfi
import dev.ipf.marmotkit.ChatListWindowSnapshotFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatListReplacementGuardTest {
    private val pinnedDm =
        chatRow("pinned-dm").copy(
            pinned = true,
            pinnedPosition = 0u,
            conversationKind = ChatConversationKindFfi.DIRECT,
        )
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
    fun pendingConfirmationDoesNotOverrideMdkActivityOrder() {
        val olderInvitation = chatRow("older-invitation").copy(activitySortAt = 10uL, pendingConfirmation = true)
        val incoming = listOf(presentedRow(tail.groupIdHex).copy(row = tail))

        assertFalse(topWindow(incoming, hasMoreAfter = true).shouldContain(olderInvitation))
        // A blocked pending invitation is hidden by MDK's page query but the keyed row
        // lookup still returns it, so it cannot be treated as a missing active chat.
        assertFalse(olderInvitation.belongsInActiveChats())
    }

    @Test
    fun equalActivityUsesGroupIdAscendingAsMdkPageTieBreaker() {
        val last = chatRow("bb").copy(activitySortAt = 20uL)
        val incoming = listOf(presentedRow(last.groupIdHex).copy(row = last))
        val window = topWindow(incoming, hasMoreAfter = true)

        assertTrue(window.shouldContain(chatRow("aa").copy(activitySortAt = 20uL)))
        assertFalse(window.shouldContain(chatRow("cc").copy(activitySortAt = 20uL)))
    }

    @Test
    fun shiftedWindowDoesNotTreatNormalPagingAsChatLoss() {
        val incoming = listOf(presentedRow(tail.groupIdHex))
        val shifted = topWindow(incoming, hasMoreBefore = true)

        assertTrue(missingActiveTopChatRows(listOf(pinnedDm, group), incoming, shifted).isEmpty())
    }

    /**
     * #3586: a full window that admits an off-window chat drops its tail by eviction, which keeps an earlier full
     * read complete, while a row the window should still hold, or an unknown window, stays an ambiguous removal.
     */
    @Test
    fun aRowPushedPastAFullWindowsTailIsEvictedNotRemoved() {
        val evicted = chatRow("evicted").copy(activitySortAt = 10uL)
        val incoming = listOf(presentedRow(tail.groupIdHex).copy(row = tail))
        val full = topWindow(incoming, hasMoreAfter = true)

        assertTrue(evicted.evictedFrom(full))
        assertFalse("a row ranking ahead of the tail left for another reason", group.evictedFrom(full))
        assertFalse("a complete window holds every active row", evicted.evictedFrom(topWindow(incoming)))
        assertFalse("an archived row is a real removal", evicted.copy(archived = true).evictedFrom(full))
        assertFalse("no window means no proof", evicted.evictedFrom(null))
    }

    /**
     * #3586: a window scrolled away from the head evicts only rows ranking ahead of its first row or past its tail,
     * so a chat that vanishes from inside its span, such as one deleted on another device, is an ambiguous removal.
     */
    @Test
    fun aShiftedWindowEvictsOnlyRowsOutsideItsRetainedSpan() {
        val newer = chatRow("newer").copy(activitySortAt = 80uL)
        val first = chatRow("first").copy(activitySortAt = 60uL)
        val inside = chatRow("inside").copy(activitySortAt = 50uL)
        val last = chatRow("last").copy(activitySortAt = 40uL)
        val older = chatRow("older").copy(activitySortAt = 10uL)
        val incoming = listOf(first, last).map { presentedRow(it.groupIdHex).copy(row = it) }
        val middle = topWindow(incoming, hasMoreBefore = true, hasMoreAfter = true)

        assertTrue("a newer row scrolled off the head", newer.evictedFrom(middle))
        assertTrue("a pinned row scrolled off the head", pinnedDm.evictedFrom(middle))
        assertTrue("an older row pushed past the tail", older.evictedFrom(middle))
        assertFalse("a row the span should still hold left for another reason", inside.evictedFrom(middle))
        assertTrue("tie ahead of the head", chatRow("a").copy(activitySortAt = 60uL).evictedFrom(middle))
        assertFalse("tie inside the span", chatRow("a").copy(activitySortAt = 40uL).evictedFrom(middle))
        assertTrue("tie past the tail", chatRow("z").copy(activitySortAt = 40uL).evictedFrom(middle))
        assertFalse("an archived row is a real removal", newer.copy(archived = true).evictedFrom(middle))

        val throughTail = middle.copy(hasMoreAfter = false)
        assertTrue("the head side still evicts", newer.evictedFrom(throughTail))
        assertFalse("a window holding the true tail holds every older row", older.evictedFrom(throughTail))
    }

    /** A viewport anchor does not imply earlier rows were evicted from the retained window. */
    @Test
    fun anchoredCompleteWindowStillChecksMissingPinnedAndActiveChats() {
        val incoming = listOf(presentedRow(tail.groupIdHex).copy(row = tail))
        val anchors =
            listOf(
                ChatListAnchorOutcomeFfi.Retained(tail.groupIdHex, 0u),
                ChatListAnchorOutcomeFfi.Recovered(tail.groupIdHex, 0u),
                ChatListAnchorOutcomeFfi.Reset,
            )
        anchors.forEach { anchor ->
            val window = topWindow(incoming).copy(anchor = anchor)
            assertEquals(
                "a complete window with $anchor still contains the true head",
                listOf(pinnedDm.groupIdHex, group.groupIdHex),
                missingActiveTopChatRows(listOf(pinnedDm, group), incoming, window).map { it.groupIdHex },
            )
        }
    }

    /** Anchored bounded windows may legitimately evict rows below their retained tail. */
    @Test
    fun anchoredTopWindowKeepsTheNativeTailBoundary() {
        val incoming = listOf(presentedRow(tail.groupIdHex).copy(row = tail))
        val window =
            topWindow(incoming, hasMoreAfter = true).copy(
                anchor = ChatListAnchorOutcomeFfi.Retained(tail.groupIdHex, 0u),
            )
        val older = chatRow("older-group").copy(activitySortAt = 10uL)

        assertEquals(
            listOf(pinnedDm.groupIdHex, group.groupIdHex),
            missingActiveTopChatRows(listOf(pinnedDm, group, older), incoming, window).map { it.groupIdHex },
        )
        assertFalse(window.copy(hasMoreBefore = true).shouldContain(pinnedDm))
    }

    @Test
    fun authoritativeArchiveAndDepartureAreRealRemovals() {
        assertFalse(pinnedDm.copy(archived = true).belongsInActiveChats())
        assertFalse(group.copy(selfMembership = SelfMembershipFfi.LEFT).belongsInActiveChats())
        assertFalse(group.copy(selfMembership = SelfMembershipFfi.REMOVED).belongsInActiveChats())
        assertFalse(group.copy(leaveRequestPending = true).belongsInActiveChats())
        assertFalse(group.copy(disbanding = true).belongsInActiveChats())
        assertFalse(group.copy(lifecycleState = GroupLifecycleStateFfi.DISBANDED).belongsInActiveChats())
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
