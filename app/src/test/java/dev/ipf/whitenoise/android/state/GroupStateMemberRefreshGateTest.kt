package dev.ipf.whitenoise.android.state

import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.GroupMemberDetailsFfi
import dev.ipf.marmotkit.GroupRosterFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GroupStateMemberRefreshGateTest {
    @Test
    fun restoredTwoMemberSnapshotPreservesTranscriptChromeAndDoesNotLeakToReplacementController() =
        runBlocking {
            val restoredSnapshot =
                GroupMemberSnapshot(
                    listOf(
                        cachedMember("alice", local = true),
                        cachedMember("bob"),
                    ),
                )
            val firstController =
                ConversationController(
                    appState = appState(),
                    initialGroup = group(name = ""),
                    initialMemberSnapshot = restoredSnapshot,
                    initialChatListRow = chatListRow(ChatConversationKindFfi.GROUP),
                    groupRosterReader = { _, _ ->
                        roster(
                            member("alice", isAdmin = true, isSelf = true, local = true),
                            member("bob", isAdmin = false),
                        )
                    },
                )

            assertEquals(2, firstController.memberCount)
            assertFalse(firstController.isDm)
            assertFalse(firstController.membersVerified)
            assertTrue(firstController.usesDirectTranscriptChrome)
            firstController.retryMembers()
            assertTrue(firstController.usesDirectTranscriptChrome)

            val restoredDirectController =
                ConversationController(
                    appState = appState(),
                    initialGroup = group(name = ""),
                    initialMemberSnapshot = restoredSnapshot,
                    initialChatListRow = chatListRow(ChatConversationKindFfi.DIRECT),
                )
            assertFalse(restoredDirectController.membersVerified)
            assertTrue(restoredDirectController.isDm)
            assertTrue(restoredDirectController.usesDirectTranscriptChrome)

            val unprojectedUnnamedController =
                ConversationController(
                    appState = appState(),
                    initialGroup = group(name = ""),
                    initialMemberSnapshot = restoredSnapshot,
                )
            assertTrue(unprojectedUnnamedController.isDm)
            assertFalse(unprojectedUnnamedController.membersVerified)
            assertTrue(unprojectedUnnamedController.usesDirectTranscriptChrome)

            val refreshFailureController =
                ConversationController(
                    appState = appState(),
                    initialGroup = group(),
                    initialMemberSnapshot = restoredSnapshot,
                    initialChatListRow = chatListRow(ChatConversationKindFfi.GROUP),
                    groupRosterReader = { _, _ -> error("refresh failed") },
                )
            assertTrue(refreshFailureController.usesDirectTranscriptChrome)
            refreshFailureController.retryMembers()
            assertFalse(refreshFailureController.membersVerified)
            assertEquals(GroupRosterLoadState.FAILED, refreshFailureController.memberRosterState)
            assertTrue(refreshFailureController.usesDirectTranscriptChrome)

            // Account and conversation switches replace the controller. The
            // replacement derives presentation from its own opening snapshot
            // instead of inheriting the previous conversation's two-party mode.
            val replacementController = ConversationController(appState = appState(), initialGroup = group())
            assertFalse(replacementController.membersVerified)
            assertFalse(replacementController.usesDirectTranscriptChrome)
            assertTrue(firstController.usesDirectTranscriptChrome)
        }

    @Test
    fun verifiedRosterDrivesTwoToThreeToTwoTranscriptChromeWithoutFailureFlicker() =
        runBlocking {
            var rosterRead = 0
            val controller =
                ConversationController(
                    appState = appState(),
                    initialGroup = group(),
                    groupRosterReader = { _, _ ->
                        when (rosterRead++) {
                            0 ->
                                roster(
                                    member("alice", isAdmin = true, isSelf = true, local = true),
                                    member("bob", isAdmin = false),
                                )
                            1 ->
                                roster(
                                    member("alice", isAdmin = true, isSelf = true, local = true),
                                    member("bob", isAdmin = false),
                                    member("carol", isAdmin = false),
                                )
                            2 ->
                                roster(
                                    member("alice", isAdmin = true, isSelf = true, local = true),
                                    member("ALICE", isAdmin = true, isSelf = true, local = true),
                                    member("bob", isAdmin = false),
                                    memberCount = 2u,
                                )
                            else -> error("refresh failed")
                        }
                    },
                )

            assertFalse(controller.membersVerified)
            assertFalse(controller.usesDirectTranscriptChrome)

            controller.retryMembers()
            assertFalse(controller.isDm)
            assertEquals(2, controller.memberCount)
            assertTrue(controller.usesDirectTranscriptChrome)

            controller.retryMembers()
            assertEquals(3, controller.memberCount)
            assertFalse(controller.usesDirectTranscriptChrome)

            controller.retryMembers()
            assertEquals(2, controller.memberCount)
            assertTrue(controller.usesDirectTranscriptChrome)

            controller.retryMembers()
            assertEquals(GroupRosterLoadState.READY, controller.memberRosterState)
            assertTrue(controller.usesDirectTranscriptChrome)
        }

    @Test
    fun refreshUsesOneAuthoritativeRosterRead() =
        runBlocking {
            var rosterReads = 0
            val controller =
                ConversationController(
                    // This test state has no Marmot runtime. Any direct
                    // groupMlsState/groupDetails call would fail the refresh;
                    // the injected roster boundary is the only valid read.
                    appState = appState(),
                    initialGroup = group(),
                    groupRosterReader = { account, groupId ->
                        rosterReads++
                        assertEquals("alice", account)
                        assertEquals("group", groupId)
                        roster(
                            member("alice", isAdmin = true, isSelf = true, local = true),
                            member("bob", isAdmin = true),
                        )
                    },
                )

            controller.retryMembers()

            assertEquals(1, rosterReads)
            assertEquals(listOf("alice", "bob"), controller.members.map { it.memberIdHex })
            assertEquals(listOf("alice", "bob"), controller.group.admins)
            assertEquals(GroupRosterLoadState.READY, controller.memberRosterState)
        }

    @Test
    fun lightweightRosterCarriesTerminalMembershipAndLifecycle() {
        val applied =
            applyAuthoritativeGroupRoster(
                currentGroup = group(),
                roster =
                    roster(
                        member("bob", isAdmin = true),
                        selfMembership = SelfMembershipFfi.REMOVED,
                        lifecycle = GroupLifecycleStateFfi.UNRECOVERABLE,
                    ),
            )

        assertEquals(SelfMembershipFfi.REMOVED, applied.group.selfMembership)
        assertTrue(applied.group.unrecoverable)
        assertEquals(listOf("bob"), applied.group.admins)
        assertEquals(listOf("bob"), applied.members.map { it.memberIdHex })
    }

    @Test
    fun lightweightRosterRejectsMemberCountMismatch() {
        val resolution =
            resolveAuthoritativeGroupRoster(
                currentGroup = group(),
                roster =
                    roster(
                        member("alice", isAdmin = true, isSelf = true, local = true),
                        memberCount = 2u,
                    ),
                activeAccountIdHex = "alice",
            )

        assertEquals(GroupRosterInvariant.MEMBER_COUNT_MISMATCH, resolution.invariant)
    }

    @Test
    fun lightweightRosterComparesAuthoritativeCountAfterIdentityDeduplication() {
        val resolution =
            resolveAuthoritativeGroupRoster(
                currentGroup = group(),
                roster =
                    roster(
                        member("alice", isAdmin = true, isSelf = true, local = true),
                        member("ALICE", isAdmin = true, isSelf = true, local = true),
                        member("bob", isAdmin = false),
                        memberCount = 2u,
                    ),
                activeAccountIdHex = "alice",
            )

        assertEquals(null, resolution.invariant)
        assertEquals(2, resolution.uniqueMemberCount)
        assertEquals(listOf("alice", "bob"), resolution.applied.members.map { it.memberIdHex.lowercase() })
    }

    @Test
    fun mismatchedRosterGroupIdAppliesNoConversationState() =
        runBlocking {
            val controller =
                ConversationController(
                    appState = appState(),
                    initialGroup = group(),
                    groupRosterReader = { _, _ ->
                        roster(
                            member("bob", isAdmin = true),
                            groupIdHex = "other-group",
                            selfMembership = SelfMembershipFfi.REMOVED,
                            lifecycle = GroupLifecycleStateFfi.UNRECOVERABLE,
                        )
                    },
                )

            controller.retryMembers()

            assertEquals("group", controller.group.groupIdHex)
            assertEquals(listOf("alice"), controller.group.admins)
            assertEquals(SelfMembershipFfi.MEMBER, controller.group.selfMembership)
            assertFalse(controller.group.unrecoverable)
            assertTrue(controller.members.isEmpty())
            assertEquals(GroupRosterLoadState.INCONSISTENT, controller.memberRosterState)
        }

    @Test
    fun selfRemovalTransitionIsDetectedImmediately() {
        val previous = group(selfMembership = SelfMembershipFfi.MEMBER)
        val removed = previous.copy(selfMembership = SelfMembershipFfi.REMOVED)
        val left = previous.copy(selfMembership = SelfMembershipFfi.LEFT)

        assertTrue(groupStateUpdateRemovesSelf(previous, removed))
        assertTrue(groupStateUpdateRemovesSelf(previous, left))
        assertFalse(groupStateUpdateRemovesSelf(removed, removed.copy()))
        assertFalse(groupStateUpdateRemovesSelf(previous, previous.copy(name = "Renamed")))
    }

    private fun appState() =
        WhiteNoiseAppState(
            context = ApplicationProvider.getApplicationContext(),
            draftStore = DraftStore(RosterDraftPersistence()),
            accountIdHexResolver = { it },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = "alice",
                        accountIdHex = "alice",
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                ),
            activeAccountRef = "alice",
        )

    private fun roster(
        vararg members: GroupMemberDetailsFfi,
        groupIdHex: String = "group",
        selfMembership: SelfMembershipFfi = SelfMembershipFfi.MEMBER,
        lifecycle: GroupLifecycleStateFfi = GroupLifecycleStateFfi.STABLE,
        memberCount: UInt = members.size.toUInt(),
    ) = GroupRosterFfi(
        groupIdHex = groupIdHex,
        members = members.toList(),
        epoch = 7uL,
        rosterRevision = 11uL,
        selfMembership = selfMembership,
        memberCount = memberCount,
        lifecycleState = lifecycle,
    )

    private fun member(
        memberId: String,
        isAdmin: Boolean,
        isSelf: Boolean = false,
        local: Boolean = false,
    ) = GroupMemberDetailsFfi(
        memberIdHex = memberId,
        account = memberId.takeIf { local },
        local = local,
        isAdmin = isAdmin,
        isSelf = isSelf,
        npub = "npub-$memberId",
        displayName = null,
    )

    private fun cachedMember(
        memberId: String,
        local: Boolean = false,
    ) = AppGroupMemberRecordFfi(
        memberIdHex = memberId,
        account = memberId.takeIf { local },
        local = local,
    )

    private fun chatListRow(conversationKind: ChatConversationKindFfi) =
        ChatListRowFfi(
            selfMembership = SelfMembershipFfi.MEMBER,
            unreadMentionCount = 0uL,
            unreadMention = false,
            groupIdHex = "group",
            archived = false,
            pendingConfirmation = false,
            title = "Group",
            groupName = "Group",
            avatarUrl = null,
            avatar = null,
            lastMessage = null,
            unreadCount = 0uL,
            hasUnread = false,
            firstUnreadMessageIdHex = null,
            lastReadMessageIdHex = null,
            lastReadTimelineAt = null,
            conversationCreatedAt = 0uL,
            activitySortAt = 0uL,
            updatedAt = 0uL,
            leaveRequestPending = false,
            leaveRequestedAtMs = null,
            manuallyMarkedUnread = false,
            conversationKind = conversationKind,
            muted = false,
            mutedUntilMs = null,
            pinned = false,
            pinnedPosition = null,
            lifecycleState = GroupLifecycleStateFfi.STABLE,
            disbanding = false,
            disbandRequest = null,
        )

    private fun group(
        selfMembership: SelfMembershipFfi = SelfMembershipFfi.MEMBER,
        name: String = "Group",
    ) = AppGroupRecordFfi(
        selfMembership = selfMembership,
        groupIdHex = "group",
        protocolProfile = dev.ipf.marmotkit.AppProtocolProfileFfi.LEGACY,
        profilePresent = false,
        endpoint = "endpoint",
        name = name,
        description = "A group",
        admins = listOf("alice"),
        relays = listOf("wss://relay.example"),
        nostrGroupIdHex = "nostr",
        avatarUrl = null,
        avatarDim = null,
        avatarThumbhash = null,
        imageHashHex = null,
        encryptedMedia =
            AppGroupEncryptedMediaComponentFfi(
                componentId = 0x8008u,
                component = "marmot.group.encrypted-media.v1",
                required = true,
                version = dev.ipf.marmotkit.EncryptedMediaVersionFfi.V1,
                mediaFormat = "encrypted-media-v1",
                allowedLocatorKinds = listOf("blossom-v1"),
                defaultBlobEndpoints =
                    listOf(
                        AppBlobEndpointFfi(
                            locatorKind = "blossom-v1",
                            baseUrl = "https://blossom.primal.net",
                        ),
                    ),
            ),
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
}

private class RosterDraftPersistence : DraftPersistence {
    private val values = mutableMapOf<String, String>()

    override fun read(): Map<String, String> = values.toMap()

    override fun write(
        key: String,
        value: String?,
    ) {
        if (value == null) values.remove(key) else values[key] = value
    }
}
