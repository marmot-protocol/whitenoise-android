package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Test

/** The user-initiated service ends exactly the transfer it began, however the scheduler winds the job down. */
class UserInitiatedTransfersTest {
    private val lines = mutableListOf<String>()
    private val ledger = AttachmentTransferLedger(log = { lines += it })
    private val transfers = UserInitiatedTransfers { ledger }

    /** The endings recorded so far, as outcome labels in order. */
    private fun outcomes() = lines.endOutcomes()

    /** A job that finishes releases its card exactly once. */
    @Test
    fun aFinishedJobIsReleasedAsCompleted() {
        transfers.began(11)
        assertEquals(1, ledger.liveCount())

        transfers.finished(11)
        transfers.finished(11)

        assertEquals(0, ledger.liveCount())
        assertEquals(listOf("completed"), outcomes())
    }

    /** A job the scheduler stops is released as stopped, and a finish arriving afterwards changes nothing. */
    @Test
    fun aStoppedJobIsReleasedAsStoppedAndALateFinishIsIgnored() {
        transfers.began(11)

        transfers.stopped(11)
        transfers.finished(11)

        assertEquals(0, ledger.liveCount())
        assertEquals(listOf("stopped"), outcomes())
    }

    /** Backlog stop then rerun: each run is its own entry, so the rerun's card survives the earlier stop. */
    @Test
    fun aRerunAfterAStopIsCountedAsANewRun() {
        transfers.began(11)
        transfers.stopped(11)
        transfers.began(11)

        assertEquals(1, ledger.liveCount())
        transfers.finished(11)

        assertEquals(0, ledger.liveCount())
        assertEquals(listOf("stopped", "completed"), outcomes())
        assertEquals(
            listOf("attachment_transfer begin seq=1", "attachment_transfer begin seq=2"),
            lines.filter { it.contains(" begin ") }.map { it.substringBefore(" owner=") },
        )
    }

    /** Concurrent jobs are independent: ending one leaves the other live. */
    @Test
    fun concurrentJobsAreReleasedIndependently() {
        transfers.began(11)
        transfers.began(12)

        transfers.finished(11)

        assertEquals(1, ledger.liveCount())
        transfers.stopped(12)
        assertEquals(0, ledger.liveCount())
    }

    /** Destroying the service releases every run it still owns, so no count outlives the process work. */
    @Test
    fun destroyingTheServiceReleasesEveryRun() {
        transfers.began(11)
        transfers.began(12)
        transfers.began(13)

        transfers.stoppedAll()

        assertEquals(0, ledger.liveCount())
        assertEquals(listOf("stopped", "stopped", "stopped"), outcomes())
    }

    /** Stopping a job that was never begun, for example an invalid identity, changes nothing. */
    @Test
    fun stoppingAnUnknownJobChangesNothing() {
        transfers.began(11)

        transfers.stopped(99)
        transfers.finished(99)

        assertEquals(1, ledger.liveCount())
        assertEquals(emptyList<String>(), outcomes())
    }

    /** Every entry is tagged with the user-initiated owner, so a card can be matched to its scheduler. */
    @Test
    fun entriesNameTheUserInitiatedOwner() {
        transfers.began(11)

        assertEquals("attachment_transfer begin seq=1 owner=user_initiated_job attempt=n/a", lines.single())
    }
}
