package dev.ipf.darkmatter.state

import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.GroupDetailsFfi
import dev.ipf.marmotkit.GroupMemberDetailsFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GroupMutationDetailsApplicationTest {
    @Test
    fun promoteAdminUsesAuthoritativeDetailedProjectionWhenRemoteAdminAlsoLands() {
        val authoritative = group(admins = listOf("alice", "bob", "carol"))
        val optimisticPromoteAdmins = listOf("alice", "bob")

        val applied =
            applyAuthoritativeGroupDetails(
                GroupDetailsFfi(
                    group = authoritative,
                    members =
                        listOf(
                            member("alice", isAdmin = true, isSelf = true),
                            member("bob", isAdmin = true),
                            member("carol", isAdmin = true),
                        ),
                ),
            )

        // This simulates a promote-admin result whose detailed projection already
        // includes a concurrent/remote admin change in the same epoch. The old
        // optimistic `group.copy(admins = group.admins + target)` path would have
        // rendered only [alice, bob] and flickered away carol until convergence.
        assertEquals(authoritative, applied.group)
        assertEquals(listOf("alice", "bob", "carol"), applied.group.admins)
        assertNotEquals(optimisticPromoteAdmins, applied.group.admins)
        assertEquals(
            listOf(
                AppGroupMemberRecordFfi(memberIdHex = "alice", account = "alice", local = true),
                AppGroupMemberRecordFfi(memberIdHex = "bob", account = null, local = false),
                AppGroupMemberRecordFfi(memberIdHex = "carol", account = null, local = false),
            ),
            applied.members,
        )
    }

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
        )

    private fun member(
        memberId: String,
        isAdmin: Boolean,
        isSelf: Boolean = false,
    ) = GroupMemberDetailsFfi(
        memberIdHex = memberId,
        account = if (isSelf) memberId else null,
        local = isSelf,
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
            defaultBlobEndpoints = listOf(AppBlobEndpointFfi(locatorKind = "blossom-v1", baseUrl = "https://blossom.primal.net")),
        )
}
