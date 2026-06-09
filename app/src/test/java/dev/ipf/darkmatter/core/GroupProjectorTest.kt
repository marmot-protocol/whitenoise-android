package dev.ipf.darkmatter.core

import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun lastAdminCannotLeaveGroupWhileOtherMembersRemain() {
        assertFalse(GroupProjector.canLeaveGroup(group(admins = listOf("alice")), activeAccountIdHex = "alice", memberCount = 3))
        assertFalse(GroupProjector.canLeaveGroup(group(admins = listOf("ALICE")), activeAccountIdHex = "alice", memberCount = 3))
        assertTrue(GroupProjector.canLeaveGroup(group(admins = listOf("alice", "bob")), activeAccountIdHex = "alice", memberCount = 3))
        assertTrue(GroupProjector.canLeaveGroup(group(admins = listOf("alice")), activeAccountIdHex = "carol", memberCount = 3))
    }

    @Test
    fun soleAdminCanLeaveWhenTheyAreTheOnlyMember() {
        // Regression for #46: a sole admin who is also the last remaining member
        // was stuck in their own group. There is no one to orphan, so leaving
        // (dissolving the group) must be allowed...
        assertTrue(GroupProjector.canLeaveGroup(group(admins = listOf("alice")), activeAccountIdHex = "alice", memberCount = 1))
        // ...and they must not be asked to self-demote the last admin first.
        assertFalse(GroupProjector.requiresSelfDemoteBeforeLeave(group(admins = listOf("alice")), activeAccountIdHex = "alice", memberCount = 1))
    }

    @Test
    fun adminsRequireSelfDemotionBeforeLeaving() {
        assertTrue(
            GroupProjector.requiresSelfDemoteBeforeLeave(
                group(admins = listOf("alice", "bob")),
                activeAccountIdHex = "alice",
                memberCount = 3,
            ),
        )
        assertTrue(
            GroupProjector.requiresSelfDemoteBeforeLeave(
                group(admins = listOf("ALICE", "bob")),
                activeAccountIdHex = "alice",
                memberCount = 3,
            ),
        )
        assertFalse(
            GroupProjector.requiresSelfDemoteBeforeLeave(
                group(admins = listOf("alice", "bob")),
                activeAccountIdHex = "carol",
                memberCount = 3,
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
    fun pendingInviteTitleUsesWelcomerBeforeRosterLoads() {
        val invite = group(name = "", pending = true, welcomer = "alice")

        assertEquals(
            "Invite from Alice",
            GroupProjector.displayTitle(
                group = invite,
                otherMemberAccount = null,
                memberCount = 0,
                memberTitle = { "Alice" },
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
        val members =
            listOf(
                member(memberId = "alice", account = "Jeff", local = true),
                member(memberId = "bob", account = null, local = false),
            )

        assertEquals("bob", GroupProjector.otherMemberAccount(members, activeAccountIdHex = "alice"))
    }

    @Test
    fun otherMemberFallsBackToNonLocalMemberIdWhenActiveAccountIsMissing() {
        val members =
            listOf(
                member(memberId = "alice", account = "Jeff", local = true),
                member(memberId = "bob", account = null, local = false),
            )

        assertEquals("bob", GroupProjector.otherMemberAccount(members, activeAccountIdHex = null))
    }

    @Test
    fun otherMemberFallsBackToActiveAccountComparisonWhenLocalFlagIsUnavailable() {
        val members =
            listOf(
                member(memberId = "alice", account = null, local = false),
                member(memberId = "bob", account = null, local = false),
            )

        assertEquals("bob", GroupProjector.otherMemberAccount(members, activeAccountIdHex = "alice"))
    }

    @Test
    fun activeAccountMemberMatchesByMemberIdHex() {
        val self = member(memberId = "alice", account = "alice", local = true)

        assertTrue(GroupProjector.isActiveAccountMember(self, activeAccountIdHex = "alice"))
    }

    @Test
    fun activeAccountMemberIgnoresHexCase() {
        val self = member(memberId = "ALICE", account = null, local = true)

        assertTrue(GroupProjector.isActiveAccountMember(self, activeAccountIdHex = "alice"))
    }

    @Test
    fun activeAccountMemberRejectsOtherLocalAccountOnSameDevice() {
        // Multi-account install: "bob" is another local account on this device
        // (local=true), but the active account is "alice". The row is NOT self
        // for menu-gating purposes.
        val otherLocal = member(memberId = "bob", account = "bob", local = true)

        assertFalse(GroupProjector.isActiveAccountMember(otherLocal, activeAccountIdHex = "alice"))
    }

    @Test
    fun activeAccountMemberRejectsRemoteMember() {
        val remote = member(memberId = "carol", account = null, local = false)

        assertFalse(GroupProjector.isActiveAccountMember(remote, activeAccountIdHex = "alice"))
    }

    @Test
    fun activeAccountMemberReturnsFalseWhenNoActiveAccount() {
        val self = member(memberId = "alice", account = null, local = true)

        assertFalse(GroupProjector.isActiveAccountMember(self, activeAccountIdHex = null))
        assertFalse(GroupProjector.isActiveAccountMember(self, activeAccountIdHex = ""))
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
        pending: Boolean = false,
        welcomer: String? = null,
    ) = AppGroupRecordFfi(
        groupIdHex = "group",
        endpoint = "endpoint",
        name = name,
        description = "A group",
        admins = admins,
        relays = listOf("wss://relay.example"),
        nostrGroupIdHex = "nostr",
        archived = false,
        pendingConfirmation = pending,
        welcomerAccountIdHex = welcomer,
        viaWelcomeMessageIdHex = null,
    )
}
