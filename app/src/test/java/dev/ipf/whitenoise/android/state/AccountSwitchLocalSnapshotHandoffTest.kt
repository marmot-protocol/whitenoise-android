package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountSwitchLocalSnapshotHandoffTest {
    @Test
    fun destructiveAccountFenceRejectsTheOldAccountAsSoonAsANewSwitchStarts() {
        val handoff = AccountSwitchLocalSnapshotHandoff()
        val stable = handoff.beginRequest("account-a")
        handoff.finishRequest(stable)
        val deletionFence = requireNotNull(handoff.captureForAccount("account-a"))

        val switchToB = handoff.beginRequest("account-b")

        assertFalse(handoff.isCurrent(deletionFence))
        assertNull(handoff.captureForAccount("account-a"))
        assertEquals(switchToB, handoff.captureForAccount("account-b"))
    }

    @Test
    fun failedSwitchReleasesItsIntentWithoutRevivingAnOldGeneration() {
        val handoff = AccountSwitchLocalSnapshotHandoff()
        val original = handoff.beginRequest("account-a")
        handoff.finishRequest(original)
        val switchToB = handoff.beginRequest("account-b")

        handoff.finishRequest(switchToB)

        assertFalse(handoff.isCurrent(original))
        assertEquals(switchToB, handoff.captureForAccount("account-a"))
    }

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
