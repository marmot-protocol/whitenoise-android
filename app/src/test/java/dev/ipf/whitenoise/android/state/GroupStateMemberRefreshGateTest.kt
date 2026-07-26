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
    fun displayOnlyGroupUpdatesSkipForcedEvictionProbe() {
        val previous = group(name = "Before", avatarUrl = "https://example.com/before.png")
        val renamed = previous.copy(name = "After", avatarUrl = "https://example.com/after.png")

        assertFalse(groupStateUpdateNeedsEvictionProbe(previous, renamed))
    }

    @Test
    fun unchangedGroupUpdatesKeepForcedEvictionProbe() {
        val previous = group()
        val updated = previous.copy()

        assertTrue(groupStateUpdateNeedsEvictionProbe(previous, updated))
    }

    @Test
    fun adminOrderOnlySkipsForcedEvictionProbe() {
        val previous = group(admins = listOf("alice", "bob"))
        val reordered = previous.copy(admins = listOf("BOB", " alice "))

        assertFalse(groupStateUpdateNeedsEvictionProbe(previous, reordered))
    }

    @Test
    fun adminMembershipChangesTriggerForcedEvictionProbe() {
        val previous = group(admins = listOf("alice"))
        val updated = previous.copy(admins = listOf("alice", "bob"))

        assertTrue(groupStateUpdateNeedsEvictionProbe(previous, updated))
    }

    @Test
    fun confirmationStateChangesTriggerForcedEvictionProbe() {
        val previous = group(pendingConfirmation = true)
        val updated = previous.copy(pendingConfirmation = false)

        assertTrue(groupStateUpdateNeedsEvictionProbe(previous, updated))
    }

    @Test
    fun selfMembershipChangesTriggerForcedEvictionProbe() {
        val previous = group(selfMembership = SelfMembershipFfi.MEMBER)
        val updated = previous.copy(selfMembership = SelfMembershipFfi.REMOVED)

        assertTrue(groupStateUpdateNeedsEvictionProbe(previous, updated))
    }

    @Test
    fun selfRemovalTransitionIsDetectedImmediately() {
        val previous = group(selfMembership = SelfMembershipFfi.MEMBER)
        val removed = previous.copy(selfMembership = SelfMembershipFfi.REMOVED)
        val left = previous.copy(selfMembership = SelfMembershipFfi.LEFT)
        val unchangedRemoved = previous.copy(selfMembership = SelfMembershipFfi.REMOVED)

        assertTrue(groupStateUpdateRemovesSelf(previous, removed))
        assertTrue(groupStateUpdateRemovesSelf(previous, left))
        assertFalse(groupStateUpdateRemovesSelf(removed, unchangedRemoved))
        assertFalse(groupStateUpdateRemovesSelf(previous, previous.copy(name = "Renamed")))
    }

    @Test
    fun groupIdentityChangesTriggerForcedEvictionProbe() {
        val previous = group(groupIdHex = "group-a")
        val updated = previous.copy(groupIdHex = "group-b")

        assertTrue(groupStateUpdateNeedsEvictionProbe(previous, updated))
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
        protocolProfile = dev.ipf.marmotkit.AppProtocolProfileFfi.LEGACY,
        profilePresent = false,
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
        unrecoverable = false,
        welcomerAccountIdHex = null,
        viaWelcomeMessageIdHex = null,
        disappearingMessageSecs = 0uL,
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
