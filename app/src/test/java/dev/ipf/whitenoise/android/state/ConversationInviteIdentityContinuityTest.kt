package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.whitenoise.android.core.GroupProjector
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationInviteIdentityContinuityTest {
    @Test
    fun acceptedInvitePeerSeedsIdentityUntilRosterArrives() {
        val pendingRoster =
            conversationIdentityProjection(
                members = emptyList(),
                activeAccountIdHex = SELF,
                acceptedInvitePeerAccount = INVITER,
            )

        assertEquals(INVITER, pendingRoster.otherMemberAccount)
        assertEquals(2, pendingRoster.memberCount)
        assertEquals(
            "Alice",
            GroupProjector.displayTitle(
                name = "",
                pendingInviteAccount = null,
                groupIdHex = "group",
                otherMemberAccount = pendingRoster.otherMemberAccount,
                memberCount = pendingRoster.memberCount,
                memberTitle = { "Alice" },
            ),
        )
    }

    @Test
    fun authoritativeRosterReplacesAcceptedInviteSeed() {
        val resolvedRoster =
            conversationIdentityProjection(
                members = listOf(member(SELF, local = true), member(PEER)),
                activeAccountIdHex = SELF,
                acceptedInvitePeerAccount = INVITER,
            )

        assertEquals(PEER, resolvedRoster.otherMemberAccount)
        assertEquals(2, resolvedRoster.memberCount)
    }

    @Test
    fun selfOnlyRosterKeepsAcceptedInvitePeerUntilPeerArrives() {
        val selfOnlyRoster =
            conversationIdentityProjection(
                members = listOf(member(SELF, local = true)),
                activeAccountIdHex = SELF,
                acceptedInvitePeerAccount = INVITER,
            )

        assertEquals(INVITER, selfOnlyRoster.otherMemberAccount)
        assertEquals(2, selfOnlyRoster.memberCount)
    }

    private fun member(
        memberIdHex: String,
        local: Boolean = false,
    ) = AppGroupMemberRecordFfi(
        memberIdHex = memberIdHex,
        account = null,
        local = local,
    )

    private companion object {
        const val SELF = "self"
        const val INVITER = "inviter"
        const val PEER = "peer"
    }
}
