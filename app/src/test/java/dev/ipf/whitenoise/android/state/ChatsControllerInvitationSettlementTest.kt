package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatsControllerInvitationSettlementTest {
    /** An invitation answered on another device moves the row first, so the cached record disagrees. */
    @Test
    fun aRowAnsweredElsewhereDisagreesWithAPendingRecord() {
        assertTrue(invitationSettlementDisagrees(rowPendingConfirmation = false, cachedPendingConfirmation = true))
        assertTrue(invitationSettlementDisagrees(rowPendingConfirmation = true, cachedPendingConfirmation = false))
    }

    /** A row and record that agree, or a chat with no cached record, need no read. */
    @Test
    fun agreeingOrUncachedChatsNeedNoRead() {
        assertFalse(invitationSettlementDisagrees(rowPendingConfirmation = true, cachedPendingConfirmation = true))
        assertFalse(invitationSettlementDisagrees(rowPendingConfirmation = false, cachedPendingConfirmation = false))
        assertFalse(invitationSettlementDisagrees(rowPendingConfirmation = false, cachedPendingConfirmation = null))
    }
}
