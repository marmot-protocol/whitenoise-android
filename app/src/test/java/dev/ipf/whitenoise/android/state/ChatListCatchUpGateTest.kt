package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatListCatchUpGateTest {
    /** A bind may request full catch-up once even if local streams reopen repeatedly. */
    @Test
    fun onlyTheInitialRequestIsClaimed() {
        val gate = ChatListCatchUpGate()

        assertTrue(gate.claimInitial())
        repeat(10) {
            assertFalse(gate.claimInitial())
        }
    }

    /** A new bind owns a fresh initial catch-up decision. */
    @Test
    fun aNewBindReceivesAFreshGate() {
        val previous = ChatListCatchUpGate()
        previous.claimInitial()

        assertTrue(ChatListCatchUpGate().claimInitial())
    }
}
