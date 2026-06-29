package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun isDmRequiresExactlyTwoMembersAndNoName() {
        // Unnamed pair → DM.
        assertTrue(GroupProjector.isDm(memberCount = 2, name = ""))
        assertTrue(GroupProjector.isDm(memberCount = 2, name = "   "))
        // Named pair → group, not a DM.
        assertFalse(GroupProjector.isDm(memberCount = 2, name = "Project Marmot"))
        // Larger conversations are always groups, named or not.
        assertFalse(GroupProjector.isDm(memberCount = 3, name = ""))
        assertFalse(GroupProjector.isDm(memberCount = 5, name = "Team"))
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
    fun leaveActionClassifiesTheStandardMemberCase() {
        // Non-admin in a multi-member group → ordinary leave.
        assertEquals(
            LeaveAction.Standard,
            GroupProjector.leaveAction(group(admins = listOf("alice")), activeAccountIdHex = "carol", memberCount = 3),
        )
        // Admin in a group that still has another admin → ordinary leave; the
        // group keeps an admin after they go.
        assertEquals(
            LeaveAction.Standard,
            GroupProjector.leaveAction(group(admins = listOf("alice", "bob")), activeAccountIdHex = "alice", memberCount = 3),
        )
    }

    @Test
    fun leaveActionForcesAdminTransferWhenSoleAdminWithOtherMembers() {
        assertEquals(
            LeaveAction.SoleAdminMustTransfer,
            GroupProjector.leaveAction(group(admins = listOf("alice")), activeAccountIdHex = "alice", memberCount = 3),
        )
        // Hex casing can drift between the admin list and the active account id.
        assertEquals(
            LeaveAction.SoleAdminMustTransfer,
            GroupProjector.leaveAction(group(admins = listOf("ALICE")), activeAccountIdHex = "alice", memberCount = 3),
        )
    }

    @Test
    fun leaveActionDegradesToDeleteWhenSoleMember() {
        // Sole member who is also the only admin → leaving dissolves the group.
        // The sole-member branch wins over the sole-admin gate (no one orphaned).
        assertEquals(
            LeaveAction.SoleMemberDeletesGroup,
            GroupProjector.leaveAction(group(admins = listOf("alice")), activeAccountIdHex = "alice", memberCount = 1),
        )
        // Sole member who isn't an admin (degenerate, but classify defensively).
        assertEquals(
            LeaveAction.SoleMemberDeletesGroup,
            GroupProjector.leaveAction(group(admins = emptyList()), activeAccountIdHex = "alice", memberCount = 1),
        )
    }

    @Test
    fun revokingTheLastAdminWouldDepleteAdminsWhileOtherMembersRemain() {
        val soleAdmin = group(admins = listOf("alice"))
        // Demoting the only admin in a multi-member group empties the admin set.
        assertTrue(GroupProjector.revokeWouldDepleteAdmins(soleAdmin, member(memberId = "alice", account = null), memberCount = 3))
        // Hex casing drift between the admin list and the member id must not hide it.
        assertTrue(GroupProjector.revokeWouldDepleteAdmins(group(admins = listOf("ALICE")), member(memberId = "alice", account = null), memberCount = 3))
        // A non-admin member is never an admin-depletion risk.
        assertFalse(GroupProjector.revokeWouldDepleteAdmins(soleAdmin, member(memberId = "bob", account = null), memberCount = 3))
        // With a co-admin, demoting one leaves the other — no depletion.
        assertFalse(
            GroupProjector.revokeWouldDepleteAdmins(group(admins = listOf("alice", "bob")), member(memberId = "alice", account = null), memberCount = 3),
        )
        // A sole admin who is the only member can be demoted/leave freely.
        assertFalse(GroupProjector.revokeWouldDepleteAdmins(soleAdmin, member(memberId = "alice", account = null), memberCount = 1))
    }

    @Test
    fun transferAdminTargetMustBeAnotherNonAdminMemberAndCallerMustBeAdmin() {
        val group = group(admins = listOf("alice"))
        // Caller (alice) is admin; bob is a non-admin member → valid target.
        assertTrue(GroupProjector.canTransferAdminTo(group, member(memberId = "bob", account = null), activeAccountIdHex = "alice"))
        // The active account itself is never a transfer target.
        assertFalse(GroupProjector.canTransferAdminTo(group, member(memberId = "alice", account = null), activeAccountIdHex = "alice"))
        // An existing admin can't receive a transfer (already an admin).
        assertFalse(
            GroupProjector.canTransferAdminTo(group(admins = listOf("alice", "bob")), member(memberId = "bob", account = null), activeAccountIdHex = "alice"),
        )
        // A non-admin caller can't transfer admin at all.
        assertFalse(GroupProjector.canTransferAdminTo(group, member(memberId = "bob", account = null), activeAccountIdHex = "carol"))
    }

    @Test
    fun soleAdminWithOtherMembersIsTrapped() {
        // Alice is the only admin and other members remain → trapped.
        assertTrue(GroupProjector.isSoleAdminWithOtherMembers(group(admins = listOf("alice")), activeAccountIdHex = "alice", memberCount = 3))
        assertTrue(GroupProjector.isSoleAdminWithOtherMembers(group(admins = listOf("ALICE")), activeAccountIdHex = "alice", memberCount = 3))
        // With a co-admin she's free to leave/step down.
        assertFalse(GroupProjector.isSoleAdminWithOtherMembers(group(admins = listOf("alice", "bob")), activeAccountIdHex = "alice", memberCount = 3))
        // The only remaining member isn't trapped — leaving dissolves the group.
        assertFalse(GroupProjector.isSoleAdminWithOtherMembers(group(admins = listOf("alice")), activeAccountIdHex = "alice", memberCount = 1))
        // A non-admin is never the trapped sole admin.
        assertFalse(GroupProjector.isSoleAdminWithOtherMembers(group(admins = listOf("alice")), activeAccountIdHex = "carol", memberCount = 3))
        // The same admin listed twice with casing drift is still one admin, so
        // she stays trapped — a raw admins.size would read two and free her.
        assertTrue(
            GroupProjector.isSoleAdminWithOtherMembers(
                group(admins = listOf("alice", "ALICE")),
                activeAccountIdHex = "alice",
                memberCount = 3,
            ),
        )
        assertTrue(
            GroupProjector.revokeWouldDepleteAdmins(
                group(admins = listOf("alice", "ALICE")),
                member(memberId = "alice", account = null),
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
    fun dmPeerAvatarAccountResolvesPeerForOneToOne() {
        // #837: the conversation top bar must seed its avatar from the same
        // peer the chat-list row does. Any two-member conversation → the other
        // member. memberCount drives classification regardless of the group
        // name, so a *renamed* 1:1 still resolves to the peer's avatar by
        // default (#837 fix 3); the caller layers an explicit group avatar over
        // this when one exists, so a real group avatar still wins.
        assertEquals(
            "bob",
            GroupProjector.dmPeerAvatarAccount(
                pendingInviteAccount = null,
                otherMemberAccount = "bob",
                memberCount = 2,
            ),
        )
    }

    @Test
    fun dmPeerAvatarAccountPrefersInviterWhilePending() {
        // Mirrors displayTitle's "Invite from …" line: a pending invite shows
        // the inviter, even if otherMemberAccount differs.
        assertEquals(
            "welcomer",
            GroupProjector.dmPeerAvatarAccount(
                pendingInviteAccount = "welcomer",
                otherMemberAccount = "bob",
                memberCount = 2,
            ),
        )
        // A blank inviter is ignored and falls back to the peer.
        assertEquals(
            "bob",
            GroupProjector.dmPeerAvatarAccount(
                pendingInviteAccount = "  ",
                otherMemberAccount = "bob",
                memberCount = 2,
            ),
        )
    }

    @Test
    fun dmPeerAvatarAccountIsNullForLargerGroups() {
        // A real group has no single peer avatar: the surface falls back to the
        // group avatar / initials instead.
        assertNull(
            GroupProjector.dmPeerAvatarAccount(
                pendingInviteAccount = null,
                otherMemberAccount = "bob",
                memberCount = 3,
            ),
        )
    }

    @Test
    fun dmPeerAvatarAccountIsNullWithoutAPeer() {
        // No resolved peer and no inviter → nothing to seed from.
        assertNull(
            GroupProjector.dmPeerAvatarAccount(
                pendingInviteAccount = null,
                otherMemberAccount = null,
                memberCount = 2,
            ),
        )
        assertNull(
            GroupProjector.dmPeerAvatarAccount(
                pendingInviteAccount = null,
                otherMemberAccount = "  ",
                memberCount = 2,
            ),
        )
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

    @Test
    fun membersWithoutActiveAccountDropsSelfOnly() {
        // #545: on a successful leave the cached snapshot is rewritten with self
        // removed so the next ConversationController seeds seededSelfMember=false.
        val self = member(memberId = "alice", account = "alice", local = true)
        val other = member(memberId = "bob", account = "bob", local = false)

        val remaining = GroupProjector.membersWithoutActiveAccount(listOf(self, other), activeAccountIdHex = "alice")

        assertEquals(listOf(other), remaining)
        assertFalse(remaining.any { GroupProjector.isActiveAccountMember(it, activeAccountIdHex = "alice") })
    }

    @Test
    fun membersWithoutActiveAccountIgnoresHexCase() {
        val self = member(memberId = "ALICE", account = null, local = true)
        val other = member(memberId = "bob", account = "bob", local = false)

        val remaining = GroupProjector.membersWithoutActiveAccount(listOf(self, other), activeAccountIdHex = "alice")

        assertEquals(listOf(other), remaining)
    }

    @Test
    fun membersWithoutActiveAccountLeavesRosterUntouchedForBlankActiveId() {
        // No active account → nothing matches self, roster is returned as-is
        // (mirrors isActiveAccountMember's blank-id contract).
        val self = member(memberId = "alice", account = "alice", local = true)
        val other = member(memberId = "bob", account = "bob", local = false)
        val roster = listOf(self, other)

        assertEquals(roster, GroupProjector.membersWithoutActiveAccount(roster, activeAccountIdHex = null))
        assertEquals(roster, GroupProjector.membersWithoutActiveAccount(roster, activeAccountIdHex = ""))
    }

    @Test
    fun selfStillMemberReadsRosterWhenNotLeft() {
        // Latch clear → ordinary roster membership check.
        val self = member(memberId = "alice", account = "alice", local = true)
        val other = member(memberId = "bob", account = "bob", local = false)
        val roster = listOf(self, other)

        assertTrue(GroupProjector.isSelfStillMember(roster, activeAccountIdHex = "alice", selfLeft = false))
        assertFalse(GroupProjector.isSelfStillMember(listOf(other), activeAccountIdHex = "alice", selfLeft = false))
    }

    @Test
    fun selfStillMemberHonoursLocalSelfLeftLatch() {
        // #787: right after a self-leave the engine eviction may not have landed,
        // so a transient roster round-trip can still list self. The latch must
        // win so the composer stays blocked and the count excludes self.
        val self = member(memberId = "alice", account = "alice", local = true)
        val other = member(memberId = "bob", account = "bob", local = false)
        val rosterStillIncludingSelf = listOf(self, other)

        assertFalse(
            GroupProjector.isSelfStillMember(rosterStillIncludingSelf, activeAccountIdHex = "alice", selfLeft = true),
        )
    }

    @Test
    fun rosterHonoringSelfLeftStripsSelfOnlyWhenLatched() {
        // #787: applyGroupDetails commits this roster. With the latch set, a
        // pre-eviction details read that still lists self must not re-add self
        // (which would revive the member count and re-enable the composer).
        val self = member(memberId = "alice", account = "alice", local = true)
        val other = member(memberId = "bob", account = "bob", local = false)
        val rosterFromDetails = listOf(self, other)

        // Latch clear: details roster is taken as-is.
        assertEquals(
            rosterFromDetails,
            GroupProjector.rosterHonoringSelfLeft(rosterFromDetails, activeAccountIdHex = "alice", selfLeft = false),
        )
        // Latch set: self is stripped, leaving the rest of the roster intact.
        assertEquals(
            listOf(other),
            GroupProjector.rosterHonoringSelfLeft(rosterFromDetails, activeAccountIdHex = "alice", selfLeft = true),
        )
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
        avatarUrl = null,
        avatarDim = null,
        avatarThumbhash = null,
        encryptedMedia = encryptedMedia(),
        archived = false,
        pendingConfirmation = pending,
        welcomerAccountIdHex = welcomer,
        viaWelcomeMessageIdHex = null,
        disappearingMessageSecs = 0uL,
    )

    private fun encryptedMedia() =
        AppGroupEncryptedMediaComponentFfi(
            componentId = 0x8008u,
            component = "marmot.group.encrypted-media.v1",
            required = true,
            mediaFormat = "encrypted-media-v1",
            allowedLocatorKinds = listOf("blossom-v1"),
            defaultBlobEndpoints = listOf(AppBlobEndpointFfi(locatorKind = "blossom-v1", baseUrl = "https://blossom.primal.net")),
        )
}
