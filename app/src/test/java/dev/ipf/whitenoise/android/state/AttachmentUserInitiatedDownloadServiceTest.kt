package dev.ipf.whitenoise.android.state

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.PersistableBundle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** JobScheduler retains only the same validated native lookup identity as WorkManager. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AttachmentUserInitiatedDownloadServiceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val request =
        AttachmentTransferRequest(
            accountRef = "private-account",
            groupIdHex = "ab".repeat(16),
            messageIdHex = "cd".repeat(32),
            attachmentIndex = 2,
            sourceMessageIdHex = "ef".repeat(32),
        )

    @Test
    fun jobExtrasRoundTripOnlyTheMinimalNativeIdentity() {
        val extras = attachmentJobExtras(request)

        assertEquals(request, decodeAttachmentJobExtras(extras))
        assertEquals(attachmentDownloadWorkName(request), extras.getString("identity"))
        assertFalse(extras.keySet().contains("url"))
        assertFalse(extras.keySet().contains("filename"))
        assertFalse(extras.keySet().contains("nonce"))
        assertFalse(extras.keySet().contains("media_reference"))
    }

    @Test
    fun malformedOrMismatchedJobIdentityCannotReachMdk() {
        val missingIdentity = PersistableBundle(attachmentJobExtras(request))
        missingIdentity.remove("identity")
        assertNull(decodeAttachmentJobExtras(missingIdentity))

        val invalidGroup = PersistableBundle(attachmentJobExtras(request))
        invalidGroup.putString("group_id_hex", "not-a-group")
        assertNull(decodeAttachmentJobExtras(invalidGroup))
    }

    @Test
    fun schedulerIdAndDedupIdentityStayStableAcrossSourceProjection() {
        val sourceLess = request.copy(sourceMessageIdHex = null)

        assertEquals(attachmentJobId(sourceLess), attachmentJobId(request))
        assertEquals(attachmentDownloadWorkName(sourceLess), attachmentDownloadWorkName(request))
        assertTrue(attachmentJobId(request) >= 0)
    }

    /** Builds the per-transfer notification the way both schedulers do. */
    private fun transferNotification(): Notification = attachmentDownloadNotification(context)

    /** A transfer card is an ongoing, locally shown progress item that never alerts a second time. */
    @Test
    fun transferNotificationIsOngoingProgressThatNeverRealerts() {
        val notification = transferNotification()

        assertEquals(Notification.CATEGORY_PROGRESS, notification.category)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertTrue(notification.flags and Notification.FLAG_LOCAL_ONLY != 0)
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
    }

    /** The transfer channel stays low importance, silent, vibration-free and badge-free. */
    @Test
    fun transferChannelIsSilentAndLowImportance() {
        transferNotification()
        val channel =
            context
                .getSystemService(NotificationManager::class.java)
                .getNotificationChannel("attachment_download_v1")

        assertEquals(NotificationManager.IMPORTANCE_LOW, channel!!.importance)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
        assertFalse(channel.shouldShowLights())
        assertFalse(channel.canShowBadge())
    }

    /** The card names no account, conversation, message or file, only the generic title and text. */
    @Test
    fun transferNotificationCarriesNoIdentity() {
        val extras = transferNotification().extras
        val visibleText =
            listOf(
                Notification.EXTRA_TITLE,
                Notification.EXTRA_TEXT,
                Notification.EXTRA_SUB_TEXT,
                Notification.EXTRA_BIG_TEXT,
            ).mapNotNull { extras.getCharSequence(it)?.toString() }

        assertEquals(listOf("Downloading", "Media attachment"), visibleText)
        visibleText.forEach { text ->
            assertFalse(text.contains(request.accountRef))
            assertFalse(text.contains(request.groupIdHex))
            assertFalse(text.contains(request.messageIdHex))
        }
    }

    /** WorkManager's fallback posts the same quiet card, under the same id as the user-initiated job. */
    @Test
    fun workManagerFallbackSharesTheQuietCardAndTheJobId() {
        val info = attachmentWorkForegroundInfo(context, request)

        assertEquals(attachmentJobId(request), info.notificationId)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, info.foregroundServiceType)
        assertTrue(info.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(Notification.CATEGORY_PROGRESS, info.notification.category)
    }

    /** One attachment keeps one card id however it is retried, while distinct attachments stay separate cards. */
    @Test
    fun oneAttachmentHasOneCardIdAndDistinctAttachmentsDoNot() {
        val sameAttachmentAgain = request.copy()
        val anotherAttachment = request.copy(attachmentIndex = request.attachmentIndex + 1)

        assertEquals(attachmentJobId(request), attachmentJobId(sameAttachmentAgain))
        assertNotEquals(attachmentJobId(request), attachmentJobId(anotherAttachment))
    }
}
