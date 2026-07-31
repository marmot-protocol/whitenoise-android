package dev.ipf.whitenoise.android.ui.group

import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.state.ChatListItem
import org.junit.Assert.assertEquals
import org.junit.Test

class DirectDetailsSharedGroupsTest {
    @Test
    fun keepsGroupsIncludingNamedPairsAndExcludesDirectChats() {
        val openDm = item("current", name = "", memberCount = 2)
        val otherDm = item("other-dm", name = "", memberCount = 2)
        val unnamedGroup = item("unnamed", name = "", memberCount = 4)
        val namedPair = item("named-pair", name = "Project", memberCount = 2)
        val commonGroup = item("friends", name = "Friends", memberCount = 4)

        assertEquals(
            listOf("unnamed", "named-pair", "friends"),
            directDetailsSharedGroups(
                groups = listOf(openDm, otherDm, unnamedGroup, namedPair, commonGroup),
                currentGroupIdHex = "CURRENT",
            ).map { it.group.groupIdHex },
        )
    }

    @Test
    fun previewShowsThreeGroupsUntilExpanded() {
        val groups = (1..5).map { item("group-$it", name = "Group $it", memberCount = 3) }

        assertEquals(listOf("group-1", "group-2", "group-3"), visibleDirectDetailsSharedGroups(groups, expanded = false).map { it.group.groupIdHex })
        assertEquals(groups, visibleDirectDetailsSharedGroups(groups, expanded = true))
    }

    private fun item(
        groupId: String,
        name: String,
        memberCount: Int,
    ) = ChatListItem(
        group = group(groupId, name),
        latest = null,
        otherMemberAccount = null,
        memberCount = memberCount,
        memberSnapshot = null,
    )

    private fun group(
        groupId: String,
        name: String,
    ) = AppGroupRecordFfi(
        selfMembership = SelfMembershipFfi.MEMBER,
        groupIdHex = groupId,
        protocolProfile = AppProtocolProfileFfi.LEGACY,
        profilePresent = false,
        endpoint = "endpoint",
        name = name,
        description = "",
        admins = listOf("self"),
        relays = listOf("wss://relay.example"),
        nostrGroupIdHex = "nostr-$groupId",
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

    private fun encryptedMedia() =
        AppGroupEncryptedMediaComponentFfi(
            componentId = 0x8008u,
            component = "marmot.group.encrypted-media.v1",
            required = true,
            version = EncryptedMediaVersionFfi.V1,
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
