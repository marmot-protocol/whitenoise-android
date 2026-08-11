package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OptimisticInviteAcceptanceTest {
    @Test
    fun pendingInviteBecomesUsableImmediately() {
        val pending = group(pendingConfirmation = true, archived = true)

        val optimistic = optimisticAcceptedInvite(pending)

        assertFalse(optimistic.pendingConfirmation)
        assertFalse(optimistic.archived)
    }

    @Test
    fun failureRollsBackOnlyTheUnreconciledOptimisticProjection() {
        val pending = group(pendingConfirmation = true)
        val optimistic = optimisticAcceptedInvite(pending)

        assertEquals(pending, rollbackOptimisticAcceptedInvite(optimistic, optimistic, pending))

        val authoritative = optimistic.copy(name = "Updated while accepting")
        assertSame(
            authoritative,
            rollbackOptimisticAcceptedInvite(authoritative, optimistic, pending),
        )
        assertTrue(authoritative.name.startsWith("Updated"))
    }

    private fun group(
        pendingConfirmation: Boolean,
        archived: Boolean = false,
    ) = AppGroupRecordFfi(
        groupIdHex = "group",
        protocolProfile = AppProtocolProfileFfi.LEGACY,
        endpoint = "wss://relay.example",
        profilePresent = true,
        name = "Invite",
        description = "",
        admins = emptyList(),
        relays = emptyList(),
        nostrGroupIdHex = "nostr-group",
        avatarUrl = null,
        avatarDim = null,
        avatarThumbhash = null,
        imageHashHex = null,
        encryptedMedia =
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
                            baseUrl = "https://blossom.example",
                        ),
                    ),
            ),
        disappearingMessageSecs = 0uL,
        archived = archived,
        pendingConfirmation = pendingConfirmation,
        unrecoverable = false,
        selfMembership = SelfMembershipFfi.MEMBER,
        leaveRequestPending = false,
        leaveRequestedAtMs = null,
        disbanding = false,
        disbandRequest = null,
        disbanded = false,
        welcomerAccountIdHex = "welcomer",
        viaWelcomeMessageIdHex = "welcome",
    )
}
