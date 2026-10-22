package dev.ipf.whitenoise.android.ui.chats.newchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupMembersReviewTicketTest {
    @Test
    fun retainedCallbackCannotClaimInvalidatedPresentationAfterIdenticalReopening() {
        val expired = GroupMembersReviewTicket()
        var submitted = 0
        val retained = {
            if (expired.claim(ownerCurrent = { true }, busy = { false }, selectionCurrent = { true })) submitted++
        }

        expired.invalidate()
        val reopened = GroupMembersReviewTicket()
        retained()

        assertFalse(expired.active)
        assertEquals(0, submitted)
        assertTrue(reopened.claim(ownerCurrent = { true }, busy = { false }, selectionCurrent = { true }))
        assertFalse(expired.claim(ownerCurrent = { true }, busy = { false }, selectionCurrent = { true }))
    }

    @Test
    fun acceptedPresentationCannotBeClaimedTwice() {
        val ticket = GroupMembersReviewTicket()
        assertTrue(ticket.claim(ownerCurrent = { true }, busy = { false }, selectionCurrent = { true }))
        assertFalse(ticket.claim(ownerCurrent = { true }, busy = { false }, selectionCurrent = { true }))
        ticket.invalidate()
        assertFalse(ticket.claim(ownerCurrent = { true }, busy = { false }, selectionCurrent = { true }))
    }

    @Test
    fun inadmissibleLiveCallbackDoesNotConsumePresentation() {
        val ticket = GroupMembersReviewTicket()
        assertFalse(ticket.claim(ownerCurrent = { false }, busy = { false }, selectionCurrent = { true }))
        assertFalse(ticket.claim(ownerCurrent = { true }, busy = { true }, selectionCurrent = { true }))
        assertFalse(ticket.claim(ownerCurrent = { true }, busy = { false }, selectionCurrent = { false }))
        assertTrue(ticket.active)
        assertTrue(ticket.claim(ownerCurrent = { true }, busy = { false }, selectionCurrent = { true }))
    }
}
