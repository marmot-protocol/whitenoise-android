package dev.ipf.darkmatter.state

import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.GroupDetailsFfi
import dev.ipf.marmotkit.GroupMemberDetailsFfi
import org.junit.Assert.assertEquals
import org.junit.Test

class GroupMutationDetailsApplicationTest {
    @Test
    fun detailedProjectionIncludesRemoteAdminMemberWhenSameEpochChangeLands() {
        val authoritative = group(admins = listOf("alice", "bob", "carol"))

        val applied =
            applyAuthoritativeGroupDetails(
                GroupDetailsFfi(
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
                    group = group(admins = listOf("alice")),
                    members = emptyList(),
                ),
            )

        assertEquals(emptyList<AppGroupMemberRecordFfi>(), applied.members)
    }

    @Test
    fun detailedProjectionPreservesAccountLocalAndDuplicateMemberRows() {
        val applied =
            applyAuthoritativeGroupDetails(
                GroupDetailsFfi(
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
                AppGroupMemberRecordFfi(memberIdHex = "alice", account = null, local = true),
                AppGroupMemberRecordFfi(memberIdHex = "alice", account = "alice-local", local = false),
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
            mediaFormat = "encrypted-media-v1",
            allowedLocatorKinds = listOf("blossom-v1"),
            defaultBlobEndpoints = listOf(AppBlobEndpointFfi(locatorKind = "blossom-v1", baseUrl = "https://blossom.primal.net")),
        )
}
