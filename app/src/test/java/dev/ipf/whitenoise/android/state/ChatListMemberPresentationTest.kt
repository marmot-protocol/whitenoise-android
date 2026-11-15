package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.whitenoise.android.ui.share.ACCOUNT_HEX
import dev.ipf.whitenoise.android.ui.share.PEER_A
import dev.ipf.whitenoise.android.ui.share.member
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatListMemberPresentationTest {
    @Test
    fun unknownUnnamedSelfOnlyRosterStillNeedsItsPeer() {
        val self = member(ACCOUNT_HEX, true)
        assertTrue(isDirectConversationRosterCandidate(ChatConversationKindFfi.UNKNOWN, listOf(self, self), ""))
        assertTrue(isDirectConversationRosterCandidate(ChatConversationKindFfi.UNKNOWN, emptyList(), ""))
        assertFalse(isDirectConversationRosterCandidate(null, listOf(self), ""))
    }

    @Test
    fun namedGroupsDoNotBorrowTheUnresolvedDirectHeuristic() {
        val self = member(ACCOUNT_HEX, true)
        assertFalse(isDirectConversationRosterCandidate(ChatConversationKindFfi.UNKNOWN, listOf(self), "Team"))
        assertFalse(isDirectConversationRosterCandidate(ChatConversationKindFfi.GROUP, listOf(self), ""))
    }

    @Test
    fun explicitDirectKindAndCompletedUnknownRosterKeepTheProjectorClassification() {
        val self = member(ACCOUNT_HEX, true)
        val peer = member(PEER_A, false)
        assertTrue(isDirectConversationRosterCandidate(ChatConversationKindFfi.DIRECT, listOf(self), "Contact"))
        assertTrue(isDirectConversationRosterCandidate(ChatConversationKindFfi.UNKNOWN, listOf(self, peer), ""))
        assertFalse(isDirectConversationRosterCandidate(ChatConversationKindFfi.GROUP, listOf(self, peer), ""))
    }
}
