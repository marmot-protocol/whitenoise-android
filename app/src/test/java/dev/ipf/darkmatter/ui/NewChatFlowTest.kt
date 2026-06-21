package dev.ipf.darkmatter.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewChatFlowTest {
    @Test
    fun groupCreateRequiresNameButNoRecipients() {
        assertTrue(
            canSubmitNewChatSheet(
                directMessage = false,
                busy = false,
                pendingRecipient = "",
                groupName = "Friends",
            ),
        )
        assertFalse(
            canSubmitNewChatSheet(
                directMessage = false,
                busy = false,
                pendingRecipient = "npub1alice",
                groupName = "",
            ),
        )
    }

    @Test
    fun groupCreateStartsWithNoInvitedMembers() {
        assertEquals(
            emptyList<String>(),
            newChatMemberRefs(
                directMessage = false,
                normalizedPendingRecipients = listOf("npub1alice"),
            ),
        )
    }

    @Test
    fun directMessageStillRequiresAndKeepsOneRecipient() {
        assertFalse(
            canSubmitNewChatSheet(
                directMessage = true,
                busy = false,
                pendingRecipient = "",
                groupName = "",
            ),
        )
        assertTrue(
            canSubmitNewChatSheet(
                directMessage = true,
                busy = false,
                pendingRecipient = "npub1alice",
                groupName = "",
            ),
        )
        assertEquals(
            listOf("npub1alice"),
            newChatMemberRefs(
                directMessage = true,
                normalizedPendingRecipients = listOf("npub1alice", "npub1bob", "npub1alice"),
            ),
        )
    }

    @Test
    fun emptyGroupInviteCtaRequiresLoadedAdminSelfOnlyGroup() {
        assertTrue(
            canInviteFromEmptyGroup(
                isSelfMember = true,
                isSelfAdmin = true,
                membersLoaded = true,
                memberCount = 1,
            ),
        )
        assertFalse(
            canInviteFromEmptyGroup(
                isSelfMember = true,
                isSelfAdmin = true,
                membersLoaded = true,
                memberCount = 0,
            ),
        )
        assertFalse(
            canInviteFromEmptyGroup(
                isSelfMember = true,
                isSelfAdmin = true,
                membersLoaded = true,
                memberCount = 2,
            ),
        )
        assertFalse(
            canInviteFromEmptyGroup(
                isSelfMember = true,
                isSelfAdmin = false,
                membersLoaded = true,
                memberCount = 1,
            ),
        )
        assertFalse(
            canInviteFromEmptyGroup(
                isSelfMember = false,
                isSelfAdmin = true,
                membersLoaded = true,
                memberCount = 1,
            ),
        )
        assertFalse(
            canInviteFromEmptyGroup(
                isSelfMember = true,
                isSelfAdmin = true,
                membersLoaded = false,
                memberCount = 1,
            ),
        )
    }
}
