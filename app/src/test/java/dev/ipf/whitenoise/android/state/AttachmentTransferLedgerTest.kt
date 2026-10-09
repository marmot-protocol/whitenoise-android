package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ledger counts one live card per attachment and lets only the current run end it. */
class AttachmentTransferLedgerTest {
    private val counts = mutableListOf<Int>()
    private val lines = mutableListOf<String>()
    private val ledger = AttachmentTransferLedger(onLiveCountChanged = { counts += it }, log = { lines += it })

    /** Concurrent transfers are counted separately and the count returns to zero as each ends. */
    @Test
    fun distinctTransfersAreCountedAndReleased() {
        val first = ledger.begin(1, AttachmentTransferOwner.UserInitiatedJob)
        val second = ledger.begin(2, AttachmentTransferOwner.WorkManager, attempt = 0)

        assertEquals(2, ledger.liveCount())
        assertTrue(ledger.end(first, AttachmentTransferOutcome.Completed))
        assertTrue(ledger.end(second, AttachmentTransferOutcome.Failed))

        assertEquals(0, ledger.liveCount())
        assertEquals(listOf(1, 2, 1, 0), counts)
    }

    /** A handoff between schedulers keeps one live entry for the attachment and does not announce a new count. */
    @Test
    fun handoffReplacesTheEntryWithoutDoubleCounting() {
        ledger.begin(7, AttachmentTransferOwner.WorkManager, attempt = 3)
        val replacement = ledger.begin(7, AttachmentTransferOwner.UserInitiatedJob)

        assertEquals(1, ledger.liveCount())
        assertEquals(listOf(1), counts)
        assertTrue(lines.last().contains("replaced_seq=1 replaced_owner=work_manager"))
        assertTrue(ledger.end(replacement, AttachmentTransferOutcome.Completed))
        assertEquals(0, ledger.liveCount())
    }

    /** A stale run ending after its replacement began cannot remove the replacement's live card. */
    @Test
    fun aStaleRunCannotEndItsReplacement() {
        val stale = ledger.begin(7, AttachmentTransferOwner.WorkManager, attempt = 1)
        val replacement = ledger.begin(7, AttachmentTransferOwner.UserInitiatedJob)

        assertFalse(ledger.end(stale, AttachmentTransferOutcome.Stopped))

        assertEquals(1, ledger.liveCount())
        assertTrue(lines.last().startsWith("attachment_transfer end_ignored seq=1 "))
        assertTrue(ledger.end(replacement, AttachmentTransferOutcome.Completed))
    }

    /** Ending the same run twice changes nothing the second time. */
    @Test
    fun endingTwiceIsIgnored() {
        val token = ledger.begin(3, AttachmentTransferOwner.UserInitiatedJob)

        assertTrue(ledger.end(token, AttachmentTransferOutcome.Completed))
        assertFalse(ledger.end(token, AttachmentTransferOutcome.Completed))

        assertEquals(listOf(1, 0), counts)
    }

    /** Each outcome is recorded by its own label, so a retry is never reported as a failure. */
    @Test
    fun outcomesAreRecordedDistinctly() {
        AttachmentTransferOutcome.entries.forEachIndexed { index, outcome ->
            val token = ledger.begin(index, AttachmentTransferOwner.WorkManager, attempt = index)
            ledger.end(token, outcome)
        }

        val endings = lines.filter { it.startsWith("attachment_transfer end ") }
        assertEquals(
            listOf("completed", "failed", "retrying", "stopped"),
            endings.map { it.substringAfter("outcome=") },
        )
    }

    /** The diagnostics carry only a sequence, the owner, the attempt, the outcome and replaced owners. */
    @Test
    fun diagnosticsNameNoAttachmentConversationOrAccount() {
        val token = ledger.begin(987_654_321, AttachmentTransferOwner.WorkManager, attempt = 4)
        ledger.end(token, AttachmentTransferOutcome.Retrying)

        val allowed = Regex("^attachment_transfer (begin|end|end_ignored)( [a-z_]+=[a-z0-9_/]+)+$")
        lines.forEach { line ->
            assertTrue("unexpected diagnostic shape: $line", allowed.matches(line))
            assertFalse("job id leaked: $line", line.contains("987654321"))
        }
        assertEquals("attachment_transfer begin seq=1 owner=work_manager attempt=4", lines.first())
    }
}
