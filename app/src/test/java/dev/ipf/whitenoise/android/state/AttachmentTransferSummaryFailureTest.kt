package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/** A failing notification service never reaches a transfer, and its failure is logged by type only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AttachmentTransferSummaryFailureTest {
    private val failing =
        AttachmentTransferSummaryNotifier(
            post = { throw SecurityException("fixture://private-notification-context") },
            cancel = { throw IllegalStateException("fixture://private-cancel-context") },
        )

    /** Every line the transfer diagnostics have logged so far. */
    private fun logged() = ShadowLog.getLogsForTag("DMAttachmentTransfers").map { it.msg }

    /** The failure lines among them. */
    private fun failures() = logged().filter { "summary_failed" in it }

    /** Posting, removing and clearing the summary all swallow a runtime failure from the notification service. */
    @Test
    fun aFailingNotificationServiceNeverEscapesTheNotifier() {
        failing(2)
        failing(0)
        failing.clearStale()

        assertEquals(3, failures().size)
    }

    /** The log names only the exception type, never its message, so no context from the failing call leaks. */
    @Test
    fun theFailureLogCarriesOnlyTheExceptionType() {
        failing(2)
        failing.clearStale()

        assertEquals(
            listOf(
                "attachment_transfer_summary_failed type=SecurityException",
                "attachment_transfer_summary_failed type=IllegalStateException",
            ),
            failures(),
        )
        failures().forEach { assertFalse(it.contains("fixture://")) }
    }

    /** A ledger wired to a failing notifier still begins, counts and ends every transfer. */
    @Test
    fun aFailingSummaryNeverBreaksTheLedger() {
        val ledger = AttachmentTransferLedger(onLiveCountChanged = failing)

        val first = ledger.begin(1, AttachmentTransferOwner.UserInitiatedJob)
        val second = ledger.begin(2, AttachmentTransferOwner.WorkManager, attempt = 0)
        assertEquals(2, ledger.liveCount())

        assertTrue(ledger.end(first, AttachmentTransferOutcome.Completed))
        assertTrue(ledger.end(second, AttachmentTransferOutcome.Failed))
        assertEquals(0, ledger.liveCount())
    }
}
