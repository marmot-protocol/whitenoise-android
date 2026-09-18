package dev.ipf.whitenoise.android.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

/** One ring per catch-up cohort or burst: the rest of the cohort and close followers are shown, not rung. */
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

    /** The budget only remembers alerts the presenter reports as written, and charges them to the open cohort. */
    @Test
    fun budgetConsumesOnlyWrittenAlerts() {
        var elapsed = 0L
        val window = NotificationCatchUpWindow(clock = { elapsed })
        val budget = NotificationAlertBudget(catchUpWindow = window)
        val now = 1_000_000_000_000L

        window.open()
        assertEquals(NotificationAlertDecision.Alert, budget.decide(nowMs = now, isMention = false))
        // Deciding did not consume anything: a second decision at the same moment still rings.
        assertEquals(NotificationAlertDecision.Alert, budget.decide(nowMs = now, isMention = false))
        budget.markAlerted(now)
        assertEquals(
            NotificationAlertDecision.SilentCatchUp,
            budget.decide(nowMs = now + 30_000L, isMention = false),
        )
        window.close()
        elapsed += NOTIFICATION_CATCH_UP_TAIL_MS + 1L
        assertEquals(
            "the cohort is over, the burst window too",
            NotificationAlertDecision.Alert,
            budget.decide(nowMs = now + 30_000L, isMention = false),
        )
        budget.markAlerted(now + 30_000L)
        assertEquals(
            NotificationAlertDecision.SilentBurst,
            budget.decide(nowMs = now + 32_000L, isMention = false),
        )
    }

    /** An alert written between cohorts does not charge the next cohort. */
    @Test
    fun liveAlertsDoNotChargeTheNextCohort() {
        var elapsed = 0L
        val window = NotificationCatchUpWindow(clock = { elapsed })
        val budget = NotificationAlertBudget(catchUpWindow = window)
        val now = 1_000_000_000_000L

        budget.markAlerted(now)
        elapsed += 60_000L
        window.open()
        assertEquals(NotificationAlertDecision.Alert, budget.decide(nowMs = now + 60_000L, isMention = false))
    }
}
