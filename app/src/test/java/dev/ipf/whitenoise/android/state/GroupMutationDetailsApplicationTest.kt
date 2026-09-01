package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.GroupDetailsFfi
import dev.ipf.marmotkit.GroupMemberDetailsFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.core.GroupProjector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupMutationDetailsApplicationTest {
    @Test
    fun publicAvatarReplacesEncryptedImageInLocalGroupState() {
        val original = group(admins = emptyList()).copy(imageHashHex = "ab".repeat(32))

        val updated = groupWithPublicAvatar(original, "https://blossom.example/avatar.jpg")

        assertEquals("https://blossom.example/avatar.jpg", updated.avatarUrl)
        assertNull(updated.imageHashHex)
    }

    @Test
    fun clearingPublicAvatarDoesNotInventAnEncryptedImageRemoval() {
        val encryptedHash = "ab".repeat(32)
        val original = group(admins = emptyList()).copy(imageHashHex = encryptedHash)

        val updated = groupWithPublicAvatar(original, null)

        assertNull(updated.avatarUrl)
        assertEquals(encryptedHash, updated.imageHashHex)
    }

    @Test
    fun publicAvatarPartialSuccessRetainsResidualEncryptedImageForLaterCleanup() {
        val encryptedHash = "ab".repeat(32)
        val original = group(admins = emptyList()).copy(imageHashHex = encryptedHash)

        val updated =
            groupWithPublicAvatar(
                group = original,
                avatarUrl = "https://blossom.example/avatar.jpg",
                encryptedImageCleared = false,
            )

        assertEquals("https://blossom.example/avatar.jpg", updated.avatarUrl)
        assertEquals(encryptedHash, updated.imageHashHex)
    }

    @Test
    fun noSeedRefreshFailureTransitionsToFailedInsteadOfReady() {
        assertEquals(
            GroupRosterLoadState.FAILED,
            reduceGroupRosterLoadState(
                current = GroupRosterLoadState.LOADING,
                event = GroupRosterRefreshEvent.FAILED,
            ),
        )
    }

    @Test
    fun verifiedRosterSurvivesATransientRefreshFailure() {
        val tracker = GroupRosterLoadTracker(GroupRosterLoadState.READY)

        tracker.transition(GroupRosterRefreshEvent.STARTED)
        tracker.transition(GroupRosterRefreshEvent.FAILED)

        assertEquals(GroupRosterLoadState.READY, tracker.state)
    }

    @Test
    fun cancelledRetryRestoresItsPreviousRetryableState() {
        val tracker = GroupRosterLoadTracker(GroupRosterLoadState.FAILED)
        tracker.transition(GroupRosterRefreshEvent.STARTED)
        tracker.restoreAfterCancellation()

        assertEquals(GroupRosterLoadState.FAILED, tracker.state)
    }

    @Test
    fun cancelledNewestOverlappingRefreshRestoresLastSettledState() {
        val tracker = GroupRosterLoadTracker(GroupRosterLoadState.FAILED)
        tracker.transition(GroupRosterRefreshEvent.STARTED)
        tracker.transition(GroupRosterRefreshEvent.STARTED)
        tracker.restoreAfterCancellation()

        assertEquals(GroupRosterLoadState.FAILED, tracker.state)
    }

    /** Retains the roster regression while exercising the shared guard used by the controller. */
    @Test
    fun newerRosterRefreshSupersedesOlderRefreshAndMutationInvalidatesBoth() {
        val refreshes = StalenessGuard()
        val first = refreshes.advance()
        val second = refreshes.advance()

        assertFalse(refreshes.isCurrent(first))
        assertTrue(refreshes.isCurrent(second))

        refreshes.advance()
        assertFalse(refreshes.isCurrent(second))
    }

    @Test
    fun delayedAuthoritativeRosterTransitionsFromLoadingToReady() {
        var state = GroupRosterLoadState.LOADING
        state = reduceGroupRosterLoadState(state, GroupRosterRefreshEvent.STARTED)

        val resolution =
            resolveAuthoritativeGroupRoster(
                details =
                    GroupDetailsFfi(
                        mlsState = testMlsState(memberCount = 2u),
                        group = group(admins = listOf("alice")),
                        members =
                            listOf(
                                member("alice", account = "alice", local = true, isAdmin = true, isSelf = true),
                                member("bob", isAdmin = false),
                            ),
                    ),
                activeAccountIdHex = "alice",
            )

        assertNull(resolution.invariant)
        assertEquals(listOf("alice", "bob"), resolution.applied.members.map { it.memberIdHex })
        state = reduceGroupRosterLoadState(state, GroupRosterRefreshEvent.SUCCEEDED)
        assertEquals(GroupRosterLoadState.READY, state)
    }

    @Test
    fun successfulEmptyJoinedRosterIsInconsistentInsteadOfAuthoritativeZero() {
        val resolution =
            resolveAuthoritativeGroupRoster(
                details =
                    GroupDetailsFfi(
                        mlsState = testMlsState(memberCount = 2u),
                        group = group(admins = listOf("alice")),
                        members = emptyList(),
                    ),
                activeAccountIdHex = "alice",
            )

        assertEquals(GroupRosterInvariant.EMPTY_JOINED_ROSTER, resolution.invariant)
        assertEquals(
            GroupRosterLoadState.INCONSISTENT,
            reduceGroupRosterLoadState(
                current = GroupRosterLoadState.LOADING,
                event = GroupRosterRefreshEvent.INCONSISTENT,
            ),
        )
    }

    @Test
    fun joinedRosterMustContainTheConversationAccountNotJustAnotherLocalAccount() {
        val resolution =
            resolveAuthoritativeGroupRoster(
                details =
                    GroupDetailsFfi(
                        mlsState = testMlsState(memberCount = 1u),
                        group = group(admins = listOf("alice")),
                        members =
                            listOf(
                                member("bob", account = "bob", local = true, isAdmin = false, isSelf = false),
                            ),
                    ),
                activeAccountIdHex = "alice",
            )

        assertEquals(GroupRosterInvariant.LOCAL_MEMBER_MISSING, resolution.invariant)
    }

    @Test
    fun joinedRosterMemberCountMustMatchMlsState() {
        val resolution =
            resolveAuthoritativeGroupRoster(
                details =
                    GroupDetailsFfi(
                        mlsState = testMlsState(memberCount = 3u),
                        group = group(admins = listOf("alice")),
                        members =
                            listOf(
                                member("alice", account = "alice", local = true, isAdmin = true, isSelf = true),
                                member("bob", isAdmin = false),
                            ),
                    ),
                activeAccountIdHex = "alice",
            )

        assertEquals(GroupRosterInvariant.MEMBER_COUNT_MISMATCH, resolution.invariant)
    }

    @Test
    fun validMultiDeviceRosterMatchesLeafCountWhileCollapsingDisplayRows() {
        val resolution =
            resolveAuthoritativeGroupRoster(
                details =
                    GroupDetailsFfi(
                        mlsState = testMlsState(memberCount = 2u),
                        group = group(admins = listOf("alice")),
                        members =
                            listOf(
                                member("alice", account = "alice", local = true, isAdmin = true, isSelf = true),
                                member("alice", account = "alice", local = false, isAdmin = true),
                            ),
                    ),
                activeAccountIdHex = "alice",
            )

        assertNull(resolution.invariant)
        assertEquals(1, resolution.applied.members.size)
        assertEquals(1, resolution.uniqueMemberCount)
    }

    @Test
    fun emptyRosterRemainsValidAfterLeavingTheGroup() {
        val resolution =
            resolveAuthoritativeGroupRoster(
                details =
                    GroupDetailsFfi(
                        mlsState = testMlsState(memberCount = 0u),
                        group =
                            group(
                                admins = listOf("alice"),
                                selfMembership = SelfMembershipFfi.LEFT,
                            ),
                        members = emptyList(),
                    ),
                activeAccountIdHex = "alice",
            )

        assertNull(resolution.invariant)
    }

    @Test
    fun detailedProjectionIncludesRemoteAdminMemberWhenSameEpochChangeLands() {
        val authoritative = group(admins = listOf("alice", "bob", "carol"))

        val applied =
            applyAuthoritativeGroupDetails(
                GroupDetailsFfi(
                    mlsState = testMlsState(),
                    group = authoritative,
                    members =
                        listOf(
                            member("alice", account = "alice", local = true, isAdmin = true, isSelf = true),
                            member("bob", isAdmin = true),
                            member("carol", isAdmin = true),
                        ),
                ),
            )

        // The detailed FFI result is already the engine-authoritative projection.
        // This guards the part Android actually transforms locally: member rows
        // must come from details.members, including remote/admin changes that were
        // not part of any optimistic local admin subset.
        assertEquals(
            listOf(
                AppGroupMemberRecordFfi(memberIdHex = "alice", account = "alice", local = true),
                AppGroupMemberRecordFfi(memberIdHex = "bob", account = null, local = false),
                AppGroupMemberRecordFfi(memberIdHex = "carol", account = null, local = false),
            ),
            applied.members,
        )
    }

    @Test
    fun detailedProjectionAllowsEmptyMembers() {
        val applied =
            applyAuthoritativeGroupDetails(
                GroupDetailsFfi(
                    mlsState = testMlsState(),
                    group = group(admins = listOf("alice")),
                    members = emptyList(),
                ),
            )

        assertEquals(emptyList<AppGroupMemberRecordFfi>(), applied.members)
    }

    @Test
    fun detailedProjectionCollapsesDuplicateIdentityRowsAndMergesLocalMetadata() {
        val applied =
            applyAuthoritativeGroupDetails(
                GroupDetailsFfi(
                    mlsState = testMlsState(),
                    group = group(admins = listOf("alice")),
                    members =
                        listOf(
                            member("alice", account = null, local = true, isAdmin = true),
                            member("alice", account = "alice-local", local = false, isAdmin = true),
                        ),
                ),
            )

        assertEquals(
            listOf(
                AppGroupMemberRecordFfi(memberIdHex = "alice", account = "alice-local", local = true),
            ),
            applied.members,
        )
    }

    @Test
    fun detailedProjectionPreservesMixedNamespaceActionTargets() {
        val applied =
            applyAuthoritativeGroupDetails(
                GroupDetailsFfi(
                    mlsState = testMlsState(memberCount = 2u),
                    group = group(admins = listOf("abc")),
                    members =
                        listOf(
                            member("abc", account = null, isAdmin = true),
                            member("", account = "ABC", isAdmin = false),
                        ),
                ),
            )

        assertEquals(listOf("abc", ""), applied.members.map { it.memberIdHex })
        assertEquals(listOf(null, "ABC"), applied.members.map { it.account })
    }

    @Test
    fun memberSnapshotReadyToCache_rejectsEmptyRoster() {
        assertFalse(memberSnapshotReadyToCache(emptyList()))
    }

    @Test
    fun memberSnapshotReadyToCache_acceptsEmptyRosterWhenSelfRemovalIsKnown() {
        assertTrue(memberSnapshotReadyToCache(emptyList(), knownSelfRemoval = true))
    }

    @Test
    fun memberSnapshotRetryDelay_capsAtSustainableBackgroundInterval() {
        assertEquals(300_000L, memberSnapshotRetryDelayMillis(Int.MAX_VALUE))
    }

    @Test
    fun memberSnapshotReadyToCache_acceptsDmRosterWithSelf() {
        val members =
            listOf(
                chatMember("alice", account = "alice", local = true),
                chatMember("bob"),
            )
        assertTrue(memberSnapshotReadyToCache(members))
    }

    @Test
    fun memberSnapshotReadyToCache_givesFirstSelfOnlyDirectRosterOneGraceRetry() {
        val selfOnly = listOf(chatMember("alice", account = "alice", local = true))

        assertFalse(
            memberSnapshotReadyToCache(
                members = selfOnly,
                directConversation = true,
                activeAccountIdHex = "alice",
                selfOnlyDirectGraceElapsed = false,
            ),
        )
        assertTrue(
            memberSnapshotReadyToCache(
                members = selfOnly,
                directConversation = true,
                activeAccountIdHex = "alice",
                selfOnlyDirectGraceElapsed = true,
            ),
        )
    }

    @Test
    fun memberSnapshotReadyToCache_acceptsNonEmptyRosterWithoutSelfForRemovalDetection() {
        assertTrue(
            memberSnapshotReadyToCache(
                members = listOf(chatMember("bob")),
            ),
        )
    }

    @Test
    fun authoritativeChatListMembersReplaceStaleDmRosterBeforeLookup() {
        val alice = chatMember("alice", account = "alice", local = true)
        val bob = chatMember("bob", account = "bob")
        val updated =
            applyAuthoritativeChatListMembers(
                groupIdHex = "dm",
                members = listOf(alice),
                activeAccountIdHex = "alice",
                memberCacheByGroup = mapOf("dm" to listOf(alice, bob)),
                removedGroupIds = emptySet(),
            )

        val liveMembers = updated.memberCacheByGroup.getValue("dm")
        assertEquals(listOf(alice), liveMembers)
        assertFalse(
            GroupProjector.isImplicitDmWith(
                members = liveMembers,
                name = "",
                activeAccountIdHex = "alice",
                targetIdHex = "bob",
                equivalentTarget = { false },
            ),
        )
    }

    @Test
    fun authoritativeChatListMembersTrackWhetherSelfIsStillPresent() {
        val alice = chatMember("alice", account = "alice", local = true)
        val bob = chatMember("bob", account = "bob")

        val removed =
            applyAuthoritativeChatListMembers(
                groupIdHex = "group",
                members = listOf(bob),
                activeAccountIdHex = "alice",
                memberCacheByGroup = emptyMap(),
                removedGroupIds = emptySet(),
            )
        assertEquals(setOf("group"), removed.removedGroupIds)

        val restored =
            applyAuthoritativeChatListMembers(
                groupIdHex = "group",
                members = listOf(alice, bob),
                activeAccountIdHex = "alice",
                memberCacheByGroup = removed.memberCacheByGroup,
                removedGroupIds = removed.removedGroupIds,
            )
        assertEquals(emptySet<String>(), restored.removedGroupIds)
    }

    @Test
    fun conversationSeedTreatsProjectedRemovalAsVerifiedWithoutRoster() {
        val seed =
            conversationMembershipSeed(
                initialGroup = group(admins = listOf("alice"), selfMembership = SelfMembershipFfi.REMOVED),
                initialMemberSnapshot = null,
                activeAccountIdHex = "alice",
            )

        assertTrue(seed.seededMembershipKnown)
        assertFalse(seed.seededSelfMember)
        assertTrue(seed.membersVerified)
        assertFalse(seed.membersLoaded)
        assertEquals(emptyList<AppGroupMemberRecordFfi>(), seed.members)
    }

    @Test
    fun conversationSeedProjectedRemovalOverridesStaleSelfSnapshot() {
        val alice = chatMember("alice", account = "alice", local = true)
        val bob = chatMember("bob", account = "bob")

        val seed =
            conversationMembershipSeed(
                initialGroup = group(admins = listOf("alice"), selfMembership = SelfMembershipFfi.LEFT),
                initialMemberSnapshot = GroupMemberSnapshot(listOf(alice, bob)),
                activeAccountIdHex = "alice",
            )

        assertTrue(seed.seededMembershipKnown)
        assertFalse(seed.seededSelfMember)
        assertTrue(seed.membersVerified)
        assertTrue(seed.membersLoaded)
        assertEquals(listOf(bob), seed.members)
    }

    @Test
    fun conversationSeedConfirmsSelfMemberWithRoster() {
        val alice = chatMember("alice", account = "alice", local = true)
        val bob = chatMember("bob", account = "bob")

        val seed =
            conversationMembershipSeed(
                initialGroup = group(admins = listOf("alice"), selfMembership = SelfMembershipFfi.MEMBER),
                initialMemberSnapshot = GroupMemberSnapshot(listOf(alice, bob)),
                activeAccountIdHex = "alice",
            )

        assertTrue(seed.seededMembershipKnown)
        assertTrue(seed.seededSelfMember)
        assertFalse(seed.membersVerified)
        assertTrue(seed.membersLoaded)
        assertEquals(listOf(alice, bob), seed.members)
    }

    @Test
    fun conversationSeedUsesProjectedMemberWithoutRoster() {
        val seed =
            conversationMembershipSeed(
                initialGroup = group(admins = listOf("alice"), selfMembership = SelfMembershipFfi.MEMBER),
                initialMemberSnapshot = null,
                activeAccountIdHex = "alice",
            )

        assertTrue(seed.seededMembershipKnown)
        assertTrue(seed.seededSelfMember)
        assertFalse(seed.membersVerified)
        assertFalse(seed.membersLoaded)
        assertEquals(emptyList<AppGroupMemberRecordFfi>(), seed.members)
    }

    private fun group(
        admins: List<String>,
        selfMembership: SelfMembershipFfi = SelfMembershipFfi.MEMBER,
    ) = AppGroupRecordFfi(
        selfMembership = selfMembership,
        groupIdHex = "group",
        protocolProfile = dev.ipf.marmotkit.AppProtocolProfileFfi.LEGACY,
        profilePresent = false,
        endpoint = "endpoint",
        name = "Test Group",
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
        pendingConfirmation = false,
        unrecoverable = false,
        welcomerAccountIdHex = null,
        viaWelcomeMessageIdHex = null,
        disappearingMessageSecs = 0uL,
        leaveRequestPending = false,
        leaveRequestedAtMs = null,
        disbanding = false,
        disbanded = false,
        disbandRequest = null,
    )

    private fun chatMember(
        memberId: String,
        account: String? = null,
        local: Boolean = false,
    ) = AppGroupMemberRecordFfi(
        memberIdHex = memberId,
        account = account,
        local = local,
    )

    private fun member(
        memberId: String,
        account: String? = null,
        local: Boolean = false,
        isAdmin: Boolean,
        isSelf: Boolean = false,
    ) = GroupMemberDetailsFfi(
        memberIdHex = memberId,
        account = account,
        local = local,
        isAdmin = isAdmin,
        isSelf = isSelf,
        npub = "npub-$memberId",
        displayName = null,
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

private fun testMlsState(
    groupIdHex: String = "",
    memberCount: UInt = 0u,
): dev.ipf.marmotkit.AppGroupMlsStateFfi =
    dev.ipf.marmotkit.AppGroupMlsStateFfi(
        groupIdHex = groupIdHex,
        protocolProfile = dev.ipf.marmotkit.AppProtocolProfileFfi.CURRENT,
        lifecycleState = dev.ipf.marmotkit.GroupLifecycleStateFfi.STABLE,
        epoch = 0uL,
        memberCount = memberCount,
        unrecoverable = false,
        requiredAppComponents = emptyList(),
        disbandingEnabled = false,
        disbanding = false,
        disbandingBlockers = emptyList(),
        disbandRequest = null,
    )
