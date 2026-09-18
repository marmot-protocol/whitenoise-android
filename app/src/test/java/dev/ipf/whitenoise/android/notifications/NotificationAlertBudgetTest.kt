package dev.ipf.whitenoise.android.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

/** One ring per account per catch-up cohort, one per live burst: the rest are shown, not rung. */
class NotificationAlertBudgetTest {
    /** Inside a cohort the first post rings and every later post of that cohort stays silent, mentions included. */
    @Test
    fun aCatchUpCohortRingsOnce() {
        assertEquals(
            NotificationAlertDecision.Alert,
            notificationAlertDecision(
                catchUpGeneration = 3L,
                alertedCatchUpGeneration = null,
                msSinceLastAlert = null,
                isMention = false,
            ),
        )
        assertEquals(
            NotificationAlertDecision.SilentCatchUp,
            notificationAlertDecision(
                catchUpGeneration = 3L,
                alertedCatchUpGeneration = 3L,
                msSinceLastAlert = 30_000L,
                isMention = false,
            ),
        )
        assertEquals(
            "at most one attention event per cohort, even for a mention",
            NotificationAlertDecision.SilentCatchUp,
            notificationAlertDecision(
                catchUpGeneration = 3L,
                alertedCatchUpGeneration = 3L,
                msSinceLastAlert = 30_000L,
                isMention = true,
            ),
        )
        assertEquals(
            "a new cohort rings again",
            NotificationAlertDecision.Alert,
            notificationAlertDecision(
                catchUpGeneration = 4L,
                alertedCatchUpGeneration = 3L,
                msSinceLastAlert = 30_000L,
                isMention = false,
            ),
        )
    }

    /** The first post of an account in a cohort rings even if a live alert rang a moment ago. */
    @Test
    fun aCohortsFirstPostRingsThroughABurst() {
        assertEquals(
            NotificationAlertDecision.Alert,
            notificationAlertDecision(
                catchUpGeneration = 3L,
                alertedCatchUpGeneration = null,
                msSinceLastAlert = 1_000L,
                isMention = false,
            ),
        )
    }

    /** Inside one cohort every signed-in account rings once; the accounts do not share the charge. */
    @Test
    fun eachAccountRingsOnceInACohort() {
        val window = NotificationCatchUpWindow(clock = { 0L })
        val budget = NotificationAlertBudget(catchUpWindow = window)
        val now = 1_000_000_000_000L

        window.open()
        budget.reserve(nowMs = now, isMention = false, accountRef = "a").commit()
        assertEquals(
            NotificationAlertDecision.SilentCatchUp,
            budget.reserve(nowMs = now + 1_000L, isMention = false, accountRef = "a").decision,
        )
        val other = budget.reserve(nowMs = now + 2_000L, isMention = false, accountRef = "b")
        assertEquals("the other account has not rung for this cohort", NotificationAlertDecision.Alert, other.decision)
        other.commit()
        assertEquals(
            NotificationAlertDecision.SilentCatchUp,
            budget.reserve(nowMs = now + 3_000L, isMention = false, accountRef = "b").decision,
        )
    }

    /** Outside a cohort the first message rings, a follower inside the window does not, and after it rings again. */
    @Test
    fun liveArrivalsRingOncePerBurst() {
        assertEquals(
            NotificationAlertDecision.Alert,
            notificationAlertDecision(
                catchUpGeneration = null,
                alertedCatchUpGeneration = null,
                msSinceLastAlert = null,
                isMention = false,
            ),
        )
        assertEquals(
            NotificationAlertDecision.SilentBurst,
            notificationAlertDecision(
                catchUpGeneration = null,
                alertedCatchUpGeneration = 3L,
                msSinceLastAlert = 1_000L,
                isMention = false,
            ),
        )
        assertEquals(
            NotificationAlertDecision.Alert,
            notificationAlertDecision(
                catchUpGeneration = null,
                alertedCatchUpGeneration = 3L,
                msSinceLastAlert = NOTIFICATION_ALERT_BURST_WINDOW_MS,
                isMention = false,
            ),
        )
    }

    /** A live mention of the reader breaks through a burst. */
    @Test
    fun liveMentionsBreakTheBurstRule() {
        assertEquals(
            NotificationAlertDecision.Alert,
            notificationAlertDecision(
                catchUpGeneration = null,
                alertedCatchUpGeneration = null,
                msSinceLastAlert = 1_000L,
                isMention = true,
            ),
        )
    }

    /** Reserving holds the ring at once so a racing post stays silent; release hands it back, commit spends it. */
    @Test
    fun aReservationHoldsTheRingUntilItIsSettled() {
        var elapsed = 0L
        val window = NotificationCatchUpWindow(clock = { elapsed })
        val budget = NotificationAlertBudget(catchUpWindow = window)
        val now = 1_000_000_000_000L

        window.open()
        val first = budget.reserve(nowMs = now, isMention = false, accountRef = "a")
        assertEquals(NotificationAlertDecision.Alert, first.decision)
        assertEquals(
            "a concurrent post sees the held ring",
            NotificationAlertDecision.SilentCatchUp,
            budget.reserve(nowMs = now, isMention = false, accountRef = "a").decision,
        )
        first.release()
        val second = budget.reserve(nowMs = now + 1_000L, isMention = false, accountRef = "a")
        assertEquals("the released ring is free again", NotificationAlertDecision.Alert, second.decision)
        second.commit()
        assertEquals(
            NotificationAlertDecision.SilentCatchUp,
            budget.reserve(nowMs = now + 30_000L, isMention = false, accountRef = "a").decision,
        )
        window.close()
        elapsed += NOTIFICATION_CATCH_UP_TAIL_MS + 1L
        assertEquals(
            "the cohort is over, the burst window too",
            NotificationAlertDecision.Alert,
            budget.reserve(nowMs = now + 30_000L, isMention = false, accountRef = "a").decision,
        )
    }

    /** Releasing a stale claim after a later alert was reserved leaves the later claim standing. */
    @Test
    fun releasingAStaleClaimKeepsTheLaterAlert() {
        val budget = NotificationAlertBudget(catchUpWindow = NotificationCatchUpWindow(clock = { 0L }))
        val now = 1_000_000_000_000L

        val first = budget.reserve(nowMs = now, isMention = false, accountRef = "a")
        val later = budget.reserve(nowMs = now + 20_000L, isMention = false, accountRef = "a")
        assertEquals(NotificationAlertDecision.Alert, later.decision)
        first.release()
        assertEquals(
            NotificationAlertDecision.SilentBurst,
            budget.reserve(nowMs = now + 21_000L, isMention = false, accountRef = "a").decision,
        )
    }

    /** Releasing one account's unsettled claim does not disturb another account's later claim. */
    @Test
    fun releasingOneAccountsClaimLeavesAnotherAccountsAlertStanding() {
        val window = NotificationCatchUpWindow(clock = { 0L })
        val budget = NotificationAlertBudget(catchUpWindow = window)
        val now = 1_000_000_000_000L

        window.open()
        val first = budget.reserve(nowMs = now, isMention = false, accountRef = "a")
        val second = budget.reserve(nowMs = now + 1_000L, isMention = false, accountRef = "b")
        assertEquals(NotificationAlertDecision.Alert, first.decision)
        assertEquals(NotificationAlertDecision.Alert, second.decision)
        first.release()
        assertEquals(
            "the released account may ring again for this cohort",
            NotificationAlertDecision.Alert,
            budget.reserve(nowMs = now + 2_000L, isMention = false, accountRef = "a").decision,
        )
        assertEquals(
            "the other account's claim still stands",
            NotificationAlertDecision.SilentCatchUp,
            budget.reserve(nowMs = now + 3_000L, isMention = false, accountRef = "b").decision,
        )
    }

    /** A claim that a later alert overtook no longer holds the ring, and releasing it keeps the later alert. */
    @Test
    fun aLaterAlertSupersedesAnUnsettledClaim() {
        val budget = NotificationAlertBudget(catchUpWindow = NotificationCatchUpWindow(clock = { 0L }))
        val now = 1_000_000_000_000L

        val stale = budget.reserve(nowMs = now, isMention = false, accountRef = "a")
        assertEquals(true, stale.stillHoldsTheRing())
        budget.reserve(nowMs = now + 20_000L, isMention = false, accountRef = "b").commit()
        assertEquals(false, stale.stillHoldsTheRing())
        stale.release()
        assertEquals(
            "the later alert still opens a burst window",
            NotificationAlertDecision.SilentBurst,
            budget.reserve(nowMs = now + 21_000L, isMention = false, accountRef = "a").decision,
        )
    }

    /** An alert written between cohorts does not charge the next cohort. */
    @Test
    fun liveAlertsDoNotChargeTheNextCohort() {
        var elapsed = 0L
        val window = NotificationCatchUpWindow(clock = { elapsed })
        val budget = NotificationAlertBudget(catchUpWindow = window)
        val now = 1_000_000_000_000L

        budget.reserve(nowMs = now, isMention = false, accountRef = "a").commit()
        elapsed += 60_000L
        window.open()
        assertEquals(
            NotificationAlertDecision.Alert,
            budget.reserve(nowMs = now + 60_000L, isMention = false, accountRef = "a").decision,
        )
    }
}
