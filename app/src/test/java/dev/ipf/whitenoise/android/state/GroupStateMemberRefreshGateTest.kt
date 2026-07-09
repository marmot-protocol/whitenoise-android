package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupStateMemberRefreshGateTest {
    @Test
    fun displayOnlyGroupUpdatesDoNotTriggerRosterRefresh() {
        val previous = group(name = "Before", avatarUrl = "https://example.com/before.png")
        val renamed = previous.copy(name = "After", avatarUrl = "https://example.com/after.png")

        assertFalse(groupStateUpdateMayChangeMembers(previous, renamed))
    }

    @Test
    fun adminOrderOnlyDoesNotTriggerRosterRefresh() {
        val previous = group(admins = listOf("alice", "bob"))
        val reordered = previous.copy(admins = listOf("BOB", " alice "))

        assertFalse(groupStateUpdateMayChangeMembers(previous, reordered))
    }

    @Test
    fun adminMembershipChangesTriggerRosterRefresh() {
        val previous = group(admins = listOf("alice"))
        val updated = previous.copy(admins = listOf("alice", "bob"))

        assertTrue(groupStateUpdateMayChangeMembers(previous, updated))
    }

    @Test
    fun confirmationStateChangesTriggerRosterRefresh() {
        val previous = group(pendingConfirmation = true)
        val updated = previous.copy(pendingConfirmation = false)

        assertTrue(groupStateUpdateMayChangeMembers(previous, updated))
    }

    @Test
    fun selfMembershipChangesTriggerRosterRefreshForEvictionProbe() {
        val previous = group(selfMembership = SelfMembershipFfi.MEMBER)
        val updated = previous.copy(selfMembership = SelfMembershipFfi.REMOVED)

        assertTrue(groupStateUpdateMayChangeMembers(previous, updated))
    }

    @Test
    fun groupIdentityChangesTriggerRosterRefresh() {
        val previous = group(groupIdHex = "group-a")
        val updated = previous.copy(groupIdHex = "group-b")

        assertTrue(groupStateUpdateMayChangeMembers(previous, updated))
    }

    private fun group(
        groupIdHex: String = "group",
        name: String = "Group",
        avatarUrl: String? = null,
        admins: List<String> = listOf("alice"),
        pendingConfirmation: Boolean = false,
        selfMembership: SelfMembershipFfi = SelfMembershipFfi.MEMBER,
    ) = AppGroupRecordFfi(
        selfMembership = selfMembership,
        groupIdHex = groupIdHex,
        endpoint = "endpoint",
        name = name,
        description = "A group",
        admins = admins,
        relays = listOf("wss://relay.example"),
        nostrGroupIdHex = "nostr",
        avatarUrl = avatarUrl,
        avatarDim = null,
        avatarThumbhash = null,
        imageHashHex = null,
        encryptedMedia = encryptedMedia(),
        archived = false,
        pendingConfirmation = pendingConfirmation,
        welcomerAccountIdHex = null,
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
