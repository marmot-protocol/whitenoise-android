package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountSwitchLocalSnapshotHandoffTest {
    @Test
    fun rapidABADiscardsTheSupersededTargetAndConsumesTheWinnerOnce() {
        val handoff = AccountSwitchLocalSnapshotHandoff()
        val switchToB = handoff.beginRequest()
        val switchBackToA = handoff.beginRequest()
        val bSnapshot = snapshot("account-b")
        val aSnapshot = snapshot("account-a")

        assertFalse(handoff.publish(switchToB, bSnapshot))
        assertTrue(handoff.publish(switchBackToA, aSnapshot))
        assertSame(aSnapshot, handoff.consume("account-a"))
        assertNull("a consumed snapshot must never seed a second controller", handoff.consume("account-a"))
    }

    @Test
    fun mismatchedConsumerDropsTheSnapshotInsteadOfExposingCrossAccountRows() {
        val handoff = AccountSwitchLocalSnapshotHandoff()
        val generation = handoff.beginRequest()
        assertTrue(handoff.publish(generation, snapshot("account-b")))

        assertNull(handoff.consume("account-a"))
        assertNull("a mismatched handoff must be destroyed", handoff.consume("account-b"))
    }

    private fun snapshot(accountRef: String) =
        AccountSwitchLocalSnapshot(
            accountRef = accountRef,
            activeAccountIdHex = null,
            rows = emptyList(),
            groups = emptyList(),
            memberIds = emptyList(),
            profiles = emptyList(),
        )
}
