package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Test

/** The retained-row watch caps its waits at the sweep cadence and tells trimmed rows from expired ones. */
class RetainedRowWatchTest {
    private val now = 1_000_000L
    private val cap = DisappearingMessageSweep.FOREGROUND_SWEEP_MAX_DELAY_MS

    /** Runs the decision with the row retained and not gone unless overridden. */
    private fun decide(
        retained: Boolean = true,
        gone: Boolean = false,
        deadline: Long? = null,
        remembered: Long? = null,
    ) = decideRetainedRowWatch(now, retained, gone, deadline, remembered)

    /** A deleted or expired retained row is gone. */
    @Test
    fun retainedGoneRowIsGone() {
        assertEquals(RetainedRowWatch.Gone, decide(gone = true, deadline = now + 5_000))
    }

    /** A near deadline is awaited exactly. */
    @Test
    fun nearDeadlineIsAwaitedExactly() {
        assertEquals(RetainedRowWatch.Waiting(5_000L, now + 5_000), decide(deadline = now + 5_000))
    }

    /** A far deadline is awaited in steps no longer than the sweep cap. */
    @Test
    fun farDeadlineWaitIsCapped() {
        assertEquals(RetainedRowWatch.Waiting(cap, now + 3_600_000L), decide(deadline = now + 3_600_000L))
    }

    /** A retained row with no deadline yet, such as an unread received poll, keeps being polled. */
    @Test
    fun retainedRowWithoutDeadlineKeepsLooping() {
        assertEquals(RetainedRowWatch.Waiting(cap, null), decide(deadline = null))
    }

    /** A row trimmed before its remembered deadline is unknown, so the watch keeps waiting. */
    @Test
    fun absentRowBeforeTheRememberedDeadlineKeepsWaiting() {
        assertEquals(
            RetainedRowWatch.Waiting(30_000L, now + 30_000L),
            decide(retained = false, remembered = now + 30_000L),
        )
    }

    /** A row absent at or after its remembered deadline was pruned by the engine, so it is gone. */
    @Test
    fun absentRowAfterTheRememberedDeadlineIsGone() {
        assertEquals(RetainedRowWatch.Gone, decide(retained = false, remembered = now - 1))
        assertEquals(RetainedRowWatch.Gone, decide(retained = false, remembered = now))
    }

    /** An absent row that never showed a deadline has nothing to wait for. */
    @Test
    fun absentRowWithoutAnyDeadlineIsUnwatched() {
        assertEquals(RetainedRowWatch.Unwatched, decide(retained = false))
    }
}
