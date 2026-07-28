package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.SelfMembershipFfi
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
    fun projectedConversationKindOverridesTheHeuristic() {
        // A decided engine kind wins even when the heuristic disagrees.
        assertTrue(GroupProjector.isDm(ChatConversationKindFfi.DIRECT, memberCount = 5, name = "Named"))
        assertFalse(GroupProjector.isDm(ChatConversationKindFfi.GROUP, memberCount = 2, name = ""))
        // UNKNOWN and unprojected rows fall back to name/headcount.
        assertTrue(GroupProjector.isDm(ChatConversationKindFfi.UNKNOWN, memberCount = 2, name = ""))
        assertFalse(GroupProjector.isDm(null, memberCount = 3, name = ""))
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
    fun isDmTreatsNameOfOnlyDefaultIgnorableCharsAsUnnamed() {
        // A "name" of only zero-width / bidi default-ignorable chars is invisible
        // and sanitizes away entirely, so displayTitle already treats such a pair
        // as an unnamed DM. isDm must agree: name.isBlank() returns false for
        // these non-whitespace chars, so classification would diverge from title.
        assertTrue(GroupProjector.isDm(memberCount = 2, name = "\u200B\u200E\uFEFF"))
    }

    @Test
    fun implicitDmMatchesUnnamedPairWhoseNameIsOnlyDefaultIgnorableChars() {
        // Start-DM dedup (#825) must reuse an unnamed DM even when its stored name
        // is only invisible default-ignorable chars — the same pair displayTitle
        // renders as unnamed. A raw isBlank() check would misread it as a renamed
        // group and open a duplicate DM.
        val self = member(memberId = "alice", account = "alice", local = true)
        val bob = member(memberId = "bob", account = "bob", local = false)

        assertTrue(
            GroupProjector.isImplicitDmWith(
                members = listOf(self, bob),
                name = "\u200B\u200E\uFEFF",
                activeAccountIdHex = "alice",
                targetIdHex = "bob",
                equivalentTarget = { false },
            ),
        )
    }

    @Test
    fun implicitDmMatchesActiveUnnamedPairWithTarget() {
        // #825 happy path: an unnamed two-person conversation with self + Bob,
        // both present, resolves as the DM with Bob (hex or npub target form).
        val self = member(memberId = "alice", account = "alice", local = true)
        val bob = member(memberId = "bob", account = "bob", local = false)

        assertTrue(
            GroupProjector.isImplicitDmWith(
                members = listOf(self, bob),
                name = "",
                activeAccountIdHex = "alice",
                targetIdHex = "bob",
                equivalentTarget = { false },
            ),
        )
        // Hex compare is case-insensitive, like the rest of the roster checks.
        assertTrue(
            GroupProjector.isImplicitDmWith(
                members = listOf(self, bob),
                name = "",
                activeAccountIdHex = "ALICE",
                targetIdHex = "BOB",
                equivalentTarget = { false },
            ),
        )
        // Target pasted as an npub the caller maps back to the peer's hex.
        assertTrue(
            GroupProjector.isImplicitDmWith(
                members = listOf(self, bob),
                name = "",
                activeAccountIdHex = "alice",
                targetIdHex = "npub1bob",
                equivalentTarget = { other -> other == "bob" },
            ),
        )
    }

    @Test
    fun implicitDmRejectsRenamedTwoPersonConversation() {
        // #825 bug 1: a renamed pair is a group, not a DM — Start-DM must open a
        // fresh DM rather than land in "Project planning".
        val self = member(memberId = "alice", account = "alice", local = true)
        val bob = member(memberId = "bob", account = "bob", local = false)

        assertFalse(
            GroupProjector.isImplicitDmWith(
                members = listOf(self, bob),
                name = "Project planning",
                activeAccountIdHex = "alice",
                targetIdHex = "bob",
                equivalentTarget = { false },
            ),
        )
    }

    @Test
    fun implicitDmRejectsConversationTargetWasRemovedFrom() {
        // #825 bug 2: after Bob is removed the live roster is {Alice} only, so a
        // "DM with Bob" lookup must not match — even unnamed.
        val self = member(memberId = "alice", account = "alice", local = true)

        assertFalse(
            GroupProjector.isImplicitDmWith(
                members = listOf(self),
                name = "",
                activeAccountIdHex = "alice",
                targetIdHex = "bob",
                equivalentTarget = { false },
            ),
        )
        // Renamed *and* removed — still no match.
        assertFalse(
            GroupProjector.isImplicitDmWith(
                members = listOf(self),
                name = "Project planning",
                activeAccountIdHex = "alice",
                targetIdHex = "bob",
                equivalentTarget = { false },
            ),
        )
    }

    @Test
    fun implicitDmRequiresActiveAccountStillPresent() {
        // The current user must still be a member; a pair of two other people
        // (e.g. a roster read before self was evicted) is not "my DM with Bob".
        val bob = member(memberId = "bob", account = "bob", local = false)
        val carol = member(memberId = "carol", account = "carol", local = false)

        assertFalse(
            GroupProjector.isImplicitDmWith(
                members = listOf(bob, carol),
                name = "",
                activeAccountIdHex = "alice",
                targetIdHex = "bob",
                equivalentTarget = { false },
            ),
        )
    }

    @Test
    fun implicitDmRejectsPairWithADifferentPerson() {
        // Unnamed pair with self, but the other member is Carol, not the Bob
        // target — no match, so no wrong-conversation reuse.
        val self = member(memberId = "alice", account = "alice", local = true)
        val carol = member(memberId = "carol", account = "carol", local = false)

        assertFalse(
            GroupProjector.isImplicitDmWith(
                members = listOf(self, carol),
                name = "",
                activeAccountIdHex = "alice",
                targetIdHex = "bob",
                equivalentTarget = { other -> other == "npub1bob" },
            ),
        )
    }

    @Test
    fun implicitDmRejectsBlankTarget() {
        val self = member(memberId = "alice", account = "alice", local = true)
        val bob = member(memberId = "bob", account = "bob", local = false)

        assertFalse(
            GroupProjector.isImplicitDmWith(
                members = listOf(self, bob),
                name = "",
                activeAccountIdHex = "alice",
                targetIdHex = "   ",
                equivalentTarget = { true },
            ),
        )
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
        assertTrue(GroupProjector.canLeaveGroup(group(admins = listOf("alice")), activeAccountIdHex = "alice", memberCount = 0))
        // ...and they must not be asked to self-demote the last admin first.
        assertFalse(GroupProjector.requiresSelfDemoteBeforeLeave(group(admins = listOf("alice")), activeAccountIdHex = "alice", memberCount = 1))
        assertFalse(GroupProjector.requiresSelfDemoteBeforeLeave(group(admins = listOf("alice")), activeAccountIdHex = "alice", memberCount = 0))
    }

    @Test
    fun shouldDissolveAsSoleMemberUsesAuthoritativeRosterOnly() {
        val self = member(memberId = "credential-a", account = "alice", local = true)
        val other = member(memberId = "bob", account = "bob", local = false)

        assertTrue(
            GroupProjector.shouldDissolveAsSoleMember(
                roster = listOf(self),
                activeAccountIdHex = "alice",
            ),
        )
        assertFalse(
            GroupProjector.shouldDissolveAsSoleMember(
                roster = listOf(self, other),
                activeAccountIdHex = "alice",
            ),
        )
    }

    @Test
    fun shouldDissolveAsSoleMemberRejectsMissingEmptyOrWrongRoster() {
        assertFalse(
            GroupProjector.shouldDissolveAsSoleMember(
                roster = null,
                activeAccountIdHex = "alice",
            ),
        )
        assertFalse(
            GroupProjector.shouldDissolveAsSoleMember(
                roster = emptyList(),
                activeAccountIdHex = "alice",
            ),
        )
        assertFalse(
            GroupProjector.shouldDissolveAsSoleMember(
                roster = listOf(member(memberId = "bob", account = "bob", local = false)),
                activeAccountIdHex = "alice",
            ),
        )
    }

    @Test
    fun selfSoleMemberIsTrueWhenLiveRosterHoldsOnlyTheActiveAccount() {
        // #811: when the live roster is just you, Leave/Delete must convert to
        // local cleanup — there is no one to coordinate an MLS commit with — so
        // callers key off this instead of an MLS leave. Admin status is
        // irrelevant: a sole member orphans no one.
        val selfAdmin = member(memberId = "alice", account = "alice", local = true)
        assertTrue(GroupProjector.isSelfSoleMember(listOf(selfAdmin), activeAccountIdHex = "alice"))
        // Hex casing can drift between the roster id and the active account id.
        assertTrue(
            GroupProjector.isSelfSoleMember(
                listOf(member(memberId = "ALICE", account = null, local = true)),
                activeAccountIdHex = "alice",
            ),
        )
        // MLS seat id can differ from the Nostr account id carried in `account`
        // (#1171): sole-member dissolve must still recognize self.
        assertTrue(
            GroupProjector.isSelfSoleMember(
                listOf(member(memberId = "credential-a", account = "alice", local = true)),
                activeAccountIdHex = "alice",
            ),
        )
    }

    @Test
    fun selfSoleMemberIsFalseWhenOthersRemainOrSelfIsAbsent() {
        val self = member(memberId = "alice", account = "alice", local = true)
        val other = member(memberId = "bob", account = "bob", local = false)
        // Another member is still present → a real MLS leave, not local cleanup.
        assertFalse(GroupProjector.isSelfSoleMember(listOf(self, other), activeAccountIdHex = "alice"))
        // A lone member who is not the active account isn't self-sole.
        assertFalse(GroupProjector.isSelfSoleMember(listOf(other), activeAccountIdHex = "alice"))
        // No active account → nothing matches self.
        assertFalse(GroupProjector.isSelfSoleMember(listOf(self), activeAccountIdHex = null))
        assertFalse(GroupProjector.isSelfSoleMember(listOf(self), activeAccountIdHex = ""))
        // An empty roster (unloaded / already gone) is not a sole-member leave.
        assertFalse(GroupProjector.isSelfSoleMember(emptyList(), activeAccountIdHex = "alice"))
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
    fun namedChatTitleStripsBidiOverrideCharacters() {
        // #980: the group name is peer-supplied; a title must never carry bidi
        // override/isolate controls that could visually reverse surrounding UI
        // text. Same policy as the transcript export and the #681 rename rows.
        val named = group(name = "\u202Egnp.exe\u202C safe")

        assertEquals(
            "gnp.exe safe",
            GroupProjector.displayTitle(
                group = named,
                otherMemberAccount = "alice",
                memberCount = 2,
                memberTitle = { "Alice" },
            ),
        )
    }

    @Test
    fun nameOfOnlyZeroWidthCharactersFallsThroughToUnnamedProjection() {
        // A "name" of zero-width/default-ignorable chars is invisible; treating
        // it as a real name would render a blank title. It must fall through to
        // the same fallbacks an unnamed chat uses.
        val invisible = group(name = "\u200B\u200E\uFEFF")

        assertEquals(
            "Bob",
            GroupProjector.displayTitle(
                group = invisible,
                otherMemberAccount = "bob",
                memberCount = 2,
                memberTitle = { "Bob" },
            ),
        )
        assertEquals(
            "Group of 4 people",
            GroupProjector.displayTitle(
                group = invisible,
                otherMemberAccount = "bob",
                memberCount = 4,
                memberTitle = { "Bob" },
            ),
        )
    }

    @Test
    fun namedChatTitleIsCappedAt80CodePoints() {
        val named = group(name = "x".repeat(200))

        val title =
            GroupProjector.displayTitle(
                group = named,
                otherMemberAccount = null,
                memberCount = 3,
                memberTitle = { it },
            )

        assertEquals("x".repeat(80), title)
    }

    @Test
    fun namedChatTitleFoldsFullwidthHomoglyphs() {
        // NFKC compatibility folding, mirroring ProfileSanitizer.displayName:
        // fullwidth look-alikes must not impersonate another group's title.
        val named = group(name = "ＡＢＣ")

        assertEquals(
            "ABC",
            GroupProjector.displayTitle(
                group = named,
                otherMemberAccount = null,
                memberCount = 3,
                memberTitle = { it },
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
    fun avatarAccountResolvesPeerForUnnamedTwoMemberDm() {
        // The conversation top bar and the chat-list row must agree on the peer
        // whose picture stands in for a DM (#837).
        assertEquals(
            "bob",
            GroupProjector.avatarAccount(group(name = ""), otherMemberAccount = "bob", memberCount = 2),
        )
    }

    @Test
    fun avatarAccountResolvesPeerWhenNameIsOnlyDefaultIgnorableChars() {
        // #837: the avatar rule must agree with displayTitle, which treats a name
        // of only invisible default-ignorable chars as unnamed. group.name.isBlank()
        // is false for these non-whitespace chars, so the DM peer would be dropped
        // and the top bar / chat-list row would disagree on the DM's avatar.
        assertEquals(
            "bob",
            GroupProjector.avatarAccount(group(name = "\u200B\u200E\uFEFF"), otherMemberAccount = "bob", memberCount = 2),
        )
    }

    @Test
    fun avatarAccountIsNullForNamedTwoMemberGroup() {
        // A named two-member conversation is a group; its own avatar wins, so no
        // peer account is resolved.
        assertEquals(
            null,
            GroupProjector.avatarAccount(group(name = "Project"), otherMemberAccount = "bob", memberCount = 2),
        )
    }

    @Test
    fun avatarAccountIsNullForMultiMemberGroup() {
        assertEquals(
            null,
            GroupProjector.avatarAccount(group(name = ""), otherMemberAccount = "bob", memberCount = 3),
        )
    }

    @Test
    fun avatarAccountUsesWelcomerForPendingInviteBeforeRosterLoads() {
        // On a pending invite the roster isn't loaded yet, so the inviter is the
        // peer whose avatar the top bar should show.
        assertEquals(
            "alice",
            GroupProjector.avatarAccount(
                group(name = "", pending = true, welcomer = "alice"),
                otherMemberAccount = null,
                memberCount = 0,
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
    fun activeAccountMemberMatchesResolvedAccountWhenSeatIdDiffers() {
        val self = member(memberId = "credential-a", account = "alice", local = true)

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

    @Test
    fun uniqueMemberCountCollapsesHexCasingDrift() {
        val members =
            listOf(
                member("ABCDEF", account = null),
                member("abcdef", account = null),
                member("123456", account = null),
            )

        assertEquals(2, GroupProjector.uniqueMemberCount(members))
    }

    @Test
    fun uniqueMemberCountFallsBackToAccountAndKeepsAnonymousMembersDistinct() {
        val members =
            listOf(
                member("", account = "npub-a"),
                member("", account = "NPUB-A"),
                member("", account = null),
                member("", account = null),
            )

        assertEquals(3, GroupProjector.uniqueMemberCount(members))
    }

    @Test
    fun duplicatedRosterStillClassifiesAsImplicitDm() {
        // The exact drift uniqueAdminCount guards against: one identity listed
        // twice with different hex casing must not turn a DM into a group.
        val members =
            listOf(
                member("ALICE00", account = null),
                member("alice00", account = null),
                member("bob0000", account = null),
            )

        assertTrue(
            GroupProjector.isImplicitDmWith(
                members = members,
                name = "",
                activeAccountIdHex = "alice00",
                targetIdHex = "bob0000",
                equivalentTarget = { false },
            ),
        )
    }

    @Test
    fun duplicatedSelfRosterStillCountsAsSoleMember() {
        val members =
            listOf(
                member("ALICE00", account = null),
                member("alice00", account = null),
            )

        assertTrue(GroupProjector.isSelfSoleMember(members, activeAccountIdHex = "alice00"))
        assertFalse(GroupProjector.isSelfSoleMember(members, activeAccountIdHex = "bob0000"))
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
        selfMembership = SelfMembershipFfi.MEMBER,
        groupIdHex = "group",
        protocolProfile = dev.ipf.marmotkit.AppProtocolProfileFfi.LEGACY,
        profilePresent = false,
        endpoint = "endpoint",
        name = name,
        description = "A group",
        admins = admins,
        relays = listOf("wss://relay.example"),
        nostrGroupIdHex = "nostr",
        avatarUrl = null,
        avatarDim = null,
        avatarThumbhash = null,
        imageHashHex = null,
        encryptedMedia = encryptedMedia(),
        archived = false,
        pendingConfirmation = pending,
        unrecoverable = false,
        welcomerAccountIdHex = welcomer,
        viaWelcomeMessageIdHex = null,
        disappearingMessageSecs = 0uL,
        leaveRequestPending = false,
        leaveRequestedAtMs = null,
        disbanding = false,
        disbanded = false,
        disbandRequest = null,
    )

    private fun encryptedMedia() =
        AppGroupEncryptedMediaComponentFfi(
            componentId = 0x8008u,
            component = "marmot.group.encrypted-media.v1",
            required = true,
            version = dev.ipf.marmotkit.EncryptedMediaVersionFfi.V1,
            mediaFormat = "encrypted-media-v1",
            allowedLocatorKinds = listOf("blossom-v1"),
            defaultBlobEndpoints = listOf(AppBlobEndpointFfi(locatorKind = "blossom-v1", baseUrl = "https://blossom.primal.net")),
        )
}
