package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Peer resolution for blocked-DM composer ownership. */
class ConversationDmPeerTest {
    /** A named direct chat uses its roster peer even when no peer avatar is used. */
    @Test
    fun namedDirectChatResolvesRosterPeer() {
        assertEquals(
            "peer",
            resolvedDirectPeerAccount(true, false, listOf(member("self"), member("peer")), "self", null),
        )
    }

    /** A fresh accepted invite keeps its known peer until the live roster arrives. */
    @Test
    fun acceptedInviteFallsBackToKnownAvatarPeer() {
        assertEquals("peer", resolvedDirectPeerAccount(true, false, listOf(member("self")), "self", "peer"))
    }

    /** Pending invitations and groups never acquire a blocked-DM notice. */
    @Test
    fun pendingInvitesAndGroupsHaveNoDirectPeer() {
        val roster = listOf(member("self"), member("peer"))
        assertNull(resolvedDirectPeerAccount(true, true, roster, "self", "peer"))
        assertNull(resolvedDirectPeerAccount(false, false, roster, "self", "peer"))
    }

    /** Unknown roster and fallback leave the block state unresolved. */
    @Test
    fun unknownPeerStaysUnknown() {
        assertNull(resolvedDirectPeerAccount(true, false, emptyList(), "self", null))
    }

    private fun member(account: String): AppGroupMemberRecordFfi =
        AppGroupMemberRecordFfi(memberIdHex = account, account = null, local = account == "self")
}
