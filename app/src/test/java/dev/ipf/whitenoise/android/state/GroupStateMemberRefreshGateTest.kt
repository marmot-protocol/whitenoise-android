package dev.ipf.whitenoise.android.state

import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
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
        selfMembership: SelfMembershipFfi = SelfMembershipFfi.MEMBER,
        lifecycle: GroupLifecycleStateFfi = GroupLifecycleStateFfi.STABLE,
        memberCount: UInt = members.size.toUInt(),
    ) = GroupRosterFfi(
        groupIdHex = "group",
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

    private fun group(selfMembership: SelfMembershipFfi = SelfMembershipFfi.MEMBER) =
        AppGroupRecordFfi(
            selfMembership = selfMembership,
            groupIdHex = "group",
            protocolProfile = dev.ipf.marmotkit.AppProtocolProfileFfi.LEGACY,
            profilePresent = false,
            endpoint = "endpoint",
            name = "Group",
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
