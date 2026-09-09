package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.GroupRecoveryStatusFfi
import dev.ipf.marmotkit.GroupRejoinInvitationFfi
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GroupRecoveryStateTest {
    private val invitation =
        GroupRejoinInvitationFfi(
            welcomeIdHex = "welcome",
            welcomerAccountIdHex = "welcomer",
            epoch = 7u,
            localStateToken = "token",
        )
    private val status =
        GroupRecoveryStatusFfi(
            groupIdHex = "group",
            automaticRecoveryFailed = false,
            pendingReinvites = 0u,
            failedReinvites = 0u,
            rejoinInvitations = listOf(invitation),
        )

    @Test
    fun `current offer matches only when all reviewed evidence is unchanged`() {
        assertNotNull(status.matchingRejoinInvitation(invitation.copy()))
        assertNull(status.matchingRejoinInvitation(invitation.copy(welcomeIdHex = "changed")))
        assertNull(status.matchingRejoinInvitation(invitation.copy(welcomerAccountIdHex = "changed")))
        assertNull(status.matchingRejoinInvitation(invitation.copy(epoch = 8u)))
        assertNull(status.matchingRejoinInvitation(invitation.copy(localStateToken = "changed")))
    }
}
