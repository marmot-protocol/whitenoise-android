package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.state.GroupMemberSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Native membership evidence chooses Left; ordinary/archived sources retain identity/order and raw unread truth. */
class ChatScopeProjectionTest {
    @Test fun leftContainsNativeLeftRemovedAndAcceptedLeaveButNotDisbandAlone() {
        val active =
            listOf(
                leftScopeRow("member"),
                leftScopeRow("left", SelfMembershipFfi.LEFT),
                leftScopeRow("removed", SelfMembershipFfi.REMOVED),
                leftScopeRow("pending", leavePending = true),
                leftScopeRow("disband", disbanded = true),
                leftScopeRow("removed-disband", SelfMembershipFfi.REMOVED, disbanded = true),
            )
        val archived = listOf(leftScopeRow("archived-left", SelfMembershipFfi.LEFT, archived = true))
        val result = chatScopeSource(ChatScope.Left, active, archived, false, ChatRowPortFixtures.ACCOUNT_HEX)
        assertEquals(listOf("left", "removed", "pending", "removed-disband"), result.map { it.id })
        assertSame(active[1], result[0])
        assertTrue(active[4].removedFromGroup(ChatRowPortFixtures.ACCOUNT_HEX))
        assertFalse(active[4].hasEndedMembership(ChatRowPortFixtures.ACCOUNT_HEX))
    }

    @Test fun chatsAndArchivedFolderKeepTheirNativeSourceAndOrder() {
        val active = listOf(leftScopeRow("left", SelfMembershipFfi.LEFT), leftScopeRow("member"))
        val archived = listOf(leftScopeRow("archive", SelfMembershipFfi.LEFT, archived = true))
        assertSame(active, chatScopeSource(ChatScope.Chats, active, archived, false, ChatRowPortFixtures.ACCOUNT_HEX))
        assertSame(archived, chatScopeSource(ChatScope.Left, active, archived, true, ChatRowPortFixtures.ACCOUNT_HEX))
        assertEquals(
            emptyList<String>(),
            chatScopeSource(
                ChatScope.Left,
                archived,
                emptyList(),
                false,
                ChatRowPortFixtures.ACCOUNT_HEX,
            ).map { it.id },
        )
    }

    @Test fun knownRemovalAndLoadedRosterAreEvidenceButMissingRosterAndMissingAccountAreNot() {
        val unknown = leftScopeRow("unknown")
        val empty = unknown.copy(memberSnapshot = GroupMemberSnapshot(emptyList()))
        val removed = empty.copy(removed = true)
        val otherRoster =
            unknown.copy(
                memberSnapshot =
                    GroupMemberSnapshot(
                        listOf(AppGroupMemberRecordFfi("b".repeat(64), null, false)),
                    ),
            )
        assertFalse(unknown.hasEndedMembership(ChatRowPortFixtures.ACCOUNT_HEX))
        assertFalse(empty.hasEndedMembership(ChatRowPortFixtures.ACCOUNT_HEX))
        assertTrue(removed.hasEndedMembership(ChatRowPortFixtures.ACCOUNT_HEX))
        assertTrue(otherRoster.hasEndedMembership(ChatRowPortFixtures.ACCOUNT_HEX))
        assertFalse(removed.hasEndedMembership(null))
        assertFalse(removed.hasEndedMembership(" "))
    }

    @Test fun groupAndProjectionMembershipEachRemainAuthoritative() {
        val groupLeft = leftScopeRow("left", SelfMembershipFfi.LEFT)
        val row =
            groupLeft.copy(
                projection = checkNotNull(groupLeft.projection).copy(selfMembership = SelfMembershipFfi.MEMBER),
            )
        assertTrue(row.hasEndedMembership(ChatRowPortFixtures.ACCOUNT_HEX))
        val member = leftScopeRow("member")
        val projectedLeft =
            member.copy(
                projection =
                    checkNotNull(member.projection).copy(
                        selfMembership = SelfMembershipFfi.LEFT,
                        unreadCount = 9uL,
                        hasUnread = true,
                    ),
            )
        assertTrue(projectedLeft.hasEndedMembership(ChatRowPortFixtures.ACCOUNT_HEX))
        assertEquals(9uL, projectedLeft.unreadCount)
        assertEquals(0uL, projectedLeft.effectiveUnreadCount(ChatRowPortFixtures.ACCOUNT_HEX))
    }
}
