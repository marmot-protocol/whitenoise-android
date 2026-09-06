package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountSwitchLocalSnapshotHandoffTest {
    @Test
    fun guardedTargetZeroProjectionSupersedesAStaleUnreadIndicator() {
        val stale =
            AccountUnreadValue(
                unreadCount = 6uL,
                freshness = AccountUnreadFreshness.UNKNOWN,
                hasManualUnread = false,
            )
        val handoff = AccountSwitchLocalSnapshotHandoff()
        val generation = handoff.beginRequest()
        val target = snapshot("account-b")

        var presented = stale
        if (handoff.publish(generation, target)) {
            presented = accountUnreadValueFromRows(target.rows, target.activeAccountIdHex)
        }

        assertEquals(AccountUnreadFreshness.CONFIRMED, presented.freshness)
        assertEquals(0uL, presented.confirmedUnreadCount())
        assertFalse(presented.showsUnreadDot())
    }

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

    /** A captured owner cannot become current again after an A-to-B-to-A switch sequence. */
    @Test
    fun capturedGenerationRejectsRapidReturnToTheSameAccount() {
        val handoff = AccountSwitchLocalSnapshotHandoff()
        val originalAccountGeneration = handoff.capture()

        handoff.beginRequest()
        handoff.beginRequest()

        assertFalse(handoff.isCurrent(originalAccountGeneration))
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
