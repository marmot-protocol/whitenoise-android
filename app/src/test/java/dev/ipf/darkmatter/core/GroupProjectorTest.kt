package dev.ipf.darkmatter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi

class GroupProjectorTest {
    @Test
    fun memberReferencePrefersResolvedAccountId() {
        val resolved = member(memberId = "credential-a", account = "alice")
        val unresolved = member(memberId = "credential-b", account = null)

        assertEquals("alice", GroupProjector.memberRef(resolved))
        assertEquals("credential-b", GroupProjector.memberRef(unresolved))
    }

    @Test
    fun adminStatusAcceptsAccountOrMemberCredentialIds() {
        val group = group(admins = listOf("alice", "credential-b"))

        assertTrue(GroupProjector.isAdmin(group, member(memberId = "credential-a", account = "alice")))
        assertTrue(GroupProjector.isAdmin(group, member(memberId = "credential-b", account = "bob")))
        assertFalse(GroupProjector.isAdmin(group, member(memberId = "credential-c", account = "carol")))
    }

    @Test
    fun lastAdminCannotLeaveGroup() {
        assertFalse(GroupProjector.canLeaveGroup(group(admins = listOf("alice")), activeAccountIdHex = "alice"))
        assertTrue(GroupProjector.canLeaveGroup(group(admins = listOf("alice", "bob")), activeAccountIdHex = "alice"))
        assertTrue(GroupProjector.canLeaveGroup(group(admins = listOf("alice")), activeAccountIdHex = "carol"))
    }

    @Test
    fun adminsRequireSelfDemotionBeforeLeaving() {
        assertTrue(
            GroupProjector.requiresSelfDemoteBeforeLeave(
                group(admins = listOf("alice", "bob")),
                activeAccountIdHex = "alice",
            ),
        )
        assertFalse(
            GroupProjector.requiresSelfDemoteBeforeLeave(
                group(admins = listOf("alice", "bob")),
                activeAccountIdHex = "carol",
            ),
        )
    }

    @Test
    fun unnamedChatTitleUsesOtherMemberDisplayName() {
        val unnamed = group(name = "")

        assertEquals(
            "Bob",
            GroupProjector.displayTitle(
                group = unnamed,
                otherMemberAccount = "bob",
                memberCount = 2,
                memberTitle = { "Bob" },
            ),
        )
    }

    @Test
    fun namedChatTitleUsesGroupName() {
        val named = group(name = "Product")

        assertEquals(
            "Product",
            GroupProjector.displayTitle(
                group = named,
                otherMemberAccount = "alice",
                memberCount = 2,
                memberTitle = { "Alice" },
            ),
        )
    }

    @Test
    fun unnamedChatTitleUsesGroupSizeBeforeMemberNameForLargerGroups() {
        val unnamed = group(name = "")

        assertEquals(
            "Group of 3 people",
            GroupProjector.displayTitle(
                group = unnamed,
                otherMemberAccount = "alice",
                memberCount = 3,
                memberTitle = { "Alice" },
            ),
        )
    }

    @Test
    fun unnamedTwoPersonChatTitleCanFallBackToOtherMemberNpub() {
        val unnamed = group(name = "")

        assertEquals(
            "npub1other",
            GroupProjector.displayTitle(
                group = unnamed,
                otherMemberAccount = "other",
                memberCount = 2,
                memberTitle = { "npub1other" },
            ),
        )
    }

    @Test
    fun transcriptSenderAvatarOnlyShowsForOtherMembersInLargerGroups() {
        assertFalse(GroupProjector.shouldShowTranscriptSenderAvatar(memberCount = 2, mine = false))
        assertFalse(GroupProjector.shouldShowTranscriptSenderAvatar(memberCount = 3, mine = true))
        assertTrue(GroupProjector.shouldShowTranscriptSenderAvatar(memberCount = 3, mine = false))
    }

    @Test
    fun otherMemberUsesMemberIdHexInsteadOfLocalAccountLabel() {
        val members = listOf(
            member(memberId = "alice", account = "Jeff", local = true),
            member(memberId = "bob", account = null, local = false),
        )

        assertEquals("bob", GroupProjector.otherMemberAccount(members, activeAccountIdHex = "alice"))
    }

    @Test
    fun otherMemberFallsBackToNonLocalMemberIdWhenActiveAccountIsMissing() {
        val members = listOf(
            member(memberId = "alice", account = "Jeff", local = true),
            member(memberId = "bob", account = null, local = false),
        )

        assertEquals("bob", GroupProjector.otherMemberAccount(members, activeAccountIdHex = null))
    }

    @Test
    fun otherMemberFallsBackToActiveAccountComparisonWhenLocalFlagIsUnavailable() {
        val members = listOf(
            member(memberId = "alice", account = null, local = false),
            member(memberId = "bob", account = null, local = false),
        )

        assertEquals("bob", GroupProjector.otherMemberAccount(members, activeAccountIdHex = "alice"))
    }

    private fun member(
        memberId: String,
        account: String?,
        local: Boolean = false,
    ) = AppGroupMemberRecordFfi(
        memberIdHex = memberId,
        account = account,
        local = local,
    )

    private fun group(
        admins: List<String> = emptyList(),
        name: String = "Test Group",
    ) = AppGroupRecordFfi(
        groupIdHex = "group",
        endpoint = "endpoint",
        name = name,
        description = "A group",
        admins = admins,
        relays = listOf("wss://relay.example"),
        nostrGroupIdHex = "nostr",
        archived = false,
    )
}
