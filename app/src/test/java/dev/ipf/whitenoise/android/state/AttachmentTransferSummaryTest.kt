package dev.ipf.whitenoise.android.state

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Several live transfer cards collapse under one quiet count, and the count never outlives them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AttachmentTransferSummaryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val notifier = AttachmentTransferSummaryNotifier(context)

    /** Whether this posted notification is a group summary. */
    private fun StatusBarNotification.isSummary() = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0

    /** The group summary currently posted, if any. */
    private fun summary() = manager.activeNotifications.singleOrNull { it.isSummary() }

    /** The summary's body text, which is the count and nothing more. */
    private fun summaryText(): String =
        summary()!!
            .notification.extras
            .getCharSequence(Notification.EXTRA_TEXT)
            .toString()

    /** Two or more live cards post a quiet group summary that states the count. */
    @Test
    fun twoLiveTransfersPostAQuietCountSummary() {
        notifier(2)

        val posted = summary()!!.notification
        assertEquals(ATTACHMENT_TRANSFER_GROUP, posted.group)
        assertEquals("2 attachments downloading", summaryText())
        assertTrue(posted.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(posted.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertTrue(posted.flags and Notification.FLAG_LOCAL_ONLY != 0)
        assertEquals(Notification.GROUP_ALERT_SUMMARY, posted.groupAlertBehavior)
        assertEquals(Notification.CATEGORY_PROGRESS, posted.category)
    }

    /** The count follows the live transfers up and down. */
    @Test
    fun theCountFollowsTheLiveTransfers() {
        notifier(2)
        notifier(3)
        assertEquals("3 attachments downloading", summaryText())

        notifier(2)
        assertEquals("2 attachments downloading", summaryText())
    }

    /** A single remaining card needs no explanation, so the summary is removed. */
    @Test
    fun theSummaryGoesWhenFewerThanTwoRemain() {
        notifier(2)
        notifier(1)

        assertNull(summary())
        notifier(2)
        notifier(0)
        assertNull(summary())
    }

    /** The summary states only a number, never which attachments, accounts or conversations. */
    @Test
    fun theSummaryCarriesNoIdentity() {
        notifier(2)

        val extras = summary()!!.notification.extras
        val visible =
            listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_SUB_TEXT)
                .mapNotNull { extras.getCharSequence(it)?.toString() }
        assertEquals(listOf("Downloading", "2 attachments downloading"), visible)
    }

    /** An orphaned summary removes itself, so a process that dies with transfers live cannot leave it for long. */
    @Test
    fun theSummaryExpiresOnItsOwn() {
        notifier(2)

        assertTrue(summary()!!.notification.timeoutAfter > 0L)
    }

    /** A summary left by a dead process is removed at start, because no transfer is live in the new process. */
    @Test
    fun startupClearsAStaleSummary() {
        notifier(5)
        assertNotNull(summary())

        notifier.clearStale()

        assertNull(summary())
    }

    /** Every transfer card joins the group, quietly, so the summary can collapse them. */
    @Test
    fun transferCardsJoinTheGroupWithoutAlerting() {
        val card = attachmentDownloadNotification(context)

        assertEquals(ATTACHMENT_TRANSFER_GROUP, card.group)
        assertEquals(Notification.GROUP_ALERT_SUMMARY, card.groupAlertBehavior)
        assertFalse(card.flags and Notification.FLAG_GROUP_SUMMARY != 0)
    }

    /** The ledger drives the summary end to end: the second card raises it, and the last finish removes it. */
    @Test
    fun theLedgerDrivesTheSummary() {
        val ledger = AttachmentTransferLedger(onLiveCountChanged = notifier)

        val first = ledger.begin(1, AttachmentTransferOwner.UserInitiatedJob)
        assertNull(summary())
        val second = ledger.begin(2, AttachmentTransferOwner.WorkManager, attempt = 0)
        assertEquals("2 attachments downloading", summaryText())

        ledger.end(first, AttachmentTransferOutcome.Completed)
        assertNull(summary())
        ledger.end(second, AttachmentTransferOutcome.Completed)
        assertNull(summary())
    }
}
