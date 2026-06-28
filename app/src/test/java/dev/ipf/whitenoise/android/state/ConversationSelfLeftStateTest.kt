package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.GroupDetailsFfi
import dev.ipf.marmotkit.GroupMemberDetailsFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSelfLeftStateTest {
    @Test
    fun staleGroupDetailsCannotReaddSelfAfterLocalLeave() {
        val state = ConversationSelfLeftState(seededMembershipKnown = true, seededSelfMember = true)
        val staleDetailsMembers = applyDetailsMembers("alice", "bob")

        assertTrue(state.isSelfMember(staleDetailsMembers, activeAccountIdHex = "alice"))

        // Mirrors ConversationController.leaveGroup(): after a successful local
        // self-leave, recordSelfLeft() runs before any refreshMembers() result can
        // arrive. A stale groupDetails() response still listing self must not be
        // able to restore the full roster/composer.
        state.recordSelfLeft()

        val committedMembers = state.rosterHonoringSelfLeft(staleDetailsMembers, activeAccountIdHex = "alice")

        assertEquals(listOf(appMember("bob")), committedMembers)
        assertFalse(state.isSelfMember(committedMembers, activeAccountIdHex = "alice"))
    }

    @Test
    fun seededLeftSnapshotPreventsFirstDetailsRefreshFromReaddingSelf() {
        val state = ConversationSelfLeftState(seededMembershipKnown = true, seededSelfMember = false)
        val staleDetailsMembers = applyDetailsMembers("alice", "bob")

        assertTrue(state.selfLeft)

        // Mirrors reopening a just-left conversation: the new controller did not
        // run leaveGroup(), but the seeded member snapshot already omitted self.
        // The initial refreshMembers()/applyGroupDetails() round-trip may still
        // return a pre-eviction roster; the seed-derived latch must strip self.
        val committedMembers = state.rosterHonoringSelfLeft(staleDetailsMembers, activeAccountIdHex = "alice")

        assertEquals(listOf(appMember("bob")), committedMembers)
        assertFalse(state.isSelfMember(committedMembers, activeAccountIdHex = "alice"))
    }

    @Test
    fun acceptInviteClearsSelfLeftSoDetailsCanRestoreSelf() {
        val state = ConversationSelfLeftState(seededMembershipKnown = true, seededSelfMember = false)
        val joinedDetailsMembers = applyDetailsMembers("alice", "bob")

        state.clearSelfLeft()

        assertFalse(state.selfLeft)
        assertEquals(
            listOf(appMember("alice", local = true), appMember("bob")),
            state.rosterHonoringSelfLeft(joinedDetailsMembers, activeAccountIdHex = "alice"),
        )
        assertTrue(state.isSelfMember(joinedDetailsMembers, activeAccountIdHex = "alice"))
    }

    private fun applyDetailsMembers(
        self: String,
        other: String,
    ): List<AppGroupMemberRecordFfi> =
        applyAuthoritativeGroupDetails(
            GroupDetailsFfi(
                group = group(admins = listOf(self)),
                members =
                    listOf(
                        member(self, account = self, local = true, isAdmin = true, isSelf = true),
                        member(other, account = null, local = false, isAdmin = false, isSelf = false),
                    ),
            ),
        ).members

    private fun appMember(
        memberId: String,
        local: Boolean = false,
    ) = AppGroupMemberRecordFfi(
        memberIdHex = memberId,
        account = if (local) memberId else null,
        local = local,
    )

    private fun group(admins: List<String>) =
        AppGroupRecordFfi(
            groupIdHex = "group",
            endpoint = "endpoint",
            name = "Test Group",
            description = "A group",
            admins = admins,
            relays = listOf("wss://relay.example"),
            nostrGroupIdHex = "nostr",
            avatarUrl = null,
            avatarDim = null,
            avatarThumbhash = null,
            encryptedMedia = encryptedMedia(),
            archived = false,
            pendingConfirmation = false,
            welcomerAccountIdHex = null,
            viaWelcomeMessageIdHex = null,
            disappearingMessageSecs = 0uL,
        )

    private fun member(
        memberId: String,
        account: String? = null,
        local: Boolean = false,
        isAdmin: Boolean,
        isSelf: Boolean,
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
            mediaFormat = "encrypted-media-v1",
            allowedLocatorKinds = listOf("blossom-v1"),
            defaultBlobEndpoints =
                listOf(
                    AppBlobEndpointFfi(
                        locatorKind = "blossom-v1",
                        baseUrl = "https://blossom.primal.net",
                    ),
                ),
        )
}
