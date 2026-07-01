package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.GroupDetailsFfi
import dev.ipf.marmotkit.GroupMemberDetailsFfi
import dev.ipf.whitenoise.android.core.GroupProjector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            mediaFormat = "encrypted-media-v1",
            allowedLocatorKinds = listOf("blossom-v1"),
            defaultBlobEndpoints = listOf(AppBlobEndpointFfi(locatorKind = "blossom-v1", baseUrl = "https://blossom.primal.net")),
        )
}
