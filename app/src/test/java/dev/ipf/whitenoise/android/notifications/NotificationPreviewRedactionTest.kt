package dev.ipf.whitenoise.android.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.LocalNotificationFormatter.MESSAGE_NOTIFICATION_ID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** Verifies cleanup against live OS cards, including silent platform rejection and dismissal races. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationPreviewRedactionTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)
    private val pacer = NotificationPostPacer(burstCapacity = 100, sleep = {})

    @Before
    fun setup() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        NotificationGroupReconciler.shared(context).close()
        manager.cancelAll()
        NotificationChannels.ensureChannels(context)
        context
            .getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
            .edit()
            .remove(NotificationPreviewPreferences.KEY)
            .commit()
        ConversationCardPostedRegistry.clearPosted(TAG, MESSAGE_NOTIFICATION_ID)
    }

    @After
    fun cleanup() {
        NotificationGroupReconciler.shared(context).close()
        manager.cancelAll()
        ConversationCardPostedRegistry.clearPosted(TAG, MESSAGE_NOTIFICATION_ID)
    }

    /** A persisted opt-out resumes cleanup independently of account setup after process death. */
    @Test
    fun startupRecoveryScrubsCardsAndKeepsTheirDismissalAge() =
        runBlocking {
            postPrivate()
            val age = UserEventNotificationGroup.dismissalTime(manager.activeNotifications.single { it.tag == TAG })
            context
                .getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
                .edit()
                .putBoolean(NotificationPreviewPreferences.KEY, false)
                .commit()
            assertTrue(NotificationPreviewPreferences.recover(context))
            val hidden = manager.activeNotifications.first { it.tag == TAG }.notification
            assertEquals(context.getString(R.string.app_name), hidden.extras.getCharSequence(Notification.EXTRA_TITLE))
            assertEquals(age, hidden.extras.getLong(UserEventNotificationGroup.EXTRA_LEGACY_POST_TIME))
            assertFalse(hidden.extras.containsKey("private_debug"))
            assertTrue(hidden.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        }

    /** Shed notify updates fall back to targeted cancellation and Retry, leaving a service card alone. */
    @Test
    fun unconfirmedRewriteCancelsOnlyTheKnownPrivateCardAndReportsFailure() =
        runBlocking {
            postPrivate()
            manager.notify(
                "service",
                999,
                NotificationCompat
                    .Builder(context, NotificationChannelSpec.GROUP_MESSAGES.id)
                    .setSmallIcon(R.drawable.ic_stat_whitenoise)
                    .setContentTitle("Service")
                    .build(),
            )
            var writes = 0
            val cleanup =
                NotificationPreviewRedactor(
                    context,
                    pacer = pacer,
                    post = { _, _, _, _ -> writes++ },
                    sleep = {},
                )
            assertFalse(cleanup.redact())
            assertEquals(2, writes)
            assertTrue(manager.activeNotifications.any { it.tag == "service" })
            assertFalse(manager.activeNotifications.any { it.tag == TAG })
        }

    /** Unknown inventory cannot be treated as empty or justify cancelling unrelated notifications. */
    @Test
    fun enumerationFailureKeepsTheInventoryUnknown() =
        runBlocking {
            postPrivate()
            val cleanup =
                NotificationPreviewRedactor(
                    context,
                    pacer = pacer,
                    read = { throw IllegalStateException("tray unavailable") },
                    sleep = {},
                )
            assertFalse(cleanup.redact())
            assertEquals(
                "Private content",
                manager.activeNotifications
                    .single { it.tag == TAG }
                    .notification.extras
                    .getCharSequence(Notification.EXTRA_TITLE),
            )
        }

    /** A dismissal between the initial snapshot and commit cannot recreate the card. */
    @Test
    fun cleanupRereadsBeforeWritingADismissedCard() =
        runBlocking {
            postPrivate()
            var reads = 0
            var writes = 0
            val cleanup =
                NotificationPreviewRedactor(context, pacer = pacer, read = {
                    if (++reads == 2) manager.cancel(TAG, MESSAGE_NOTIFICATION_ID)
                    it.activeNotifications
                }, post = { _, _, _, _ -> writes++ }, sleep = {})
            assertTrue(cleanup.redact())
            assertEquals(0, writes)
            assertTrue(manager.activeNotifications.isEmpty())
        }

    /** A newer accepted generic post must not be overwritten using a stale OS tray snapshot. */
    @Test
    fun pendingNewGenerationIsNotReplacedByTheOlderSnapshot() =
        runBlocking {
            postPrivate()
            ConversationCardPostedRegistry.markPosted(TAG, MESSAGE_NOTIFICATION_ID, generationId = "new")
            var reads = 0
            var writes = 0
            val cleanup =
                NotificationPreviewRedactor(context, pacer = pacer, read = {
                    if (++reads == 3) {
                        val original = manager.activeNotifications.single { it.tag == TAG }.notification
                        val newer = notificationWithoutPreview(context, original)
                        newer.extras.putString(UserEventNotificationGroup.EXTRA_GENERATION, "new")
                        manager.notify(TAG, MESSAGE_NOTIFICATION_ID, newer)
                    }
                    it.activeNotifications
                }, post = { _, _, _, _ -> writes++ }, sleep = {})
            assertTrue(cleanup.redact())
            assertEquals(0, writes)
            assertEquals(
                "new",
                manager.activeNotifications
                    .single { it.tag == TAG }
                    .notification.extras
                    .getString(UserEventNotificationGroup.EXTRA_GENERATION),
            )
        }

    /** A write accepted locally but shed by Android cannot leave an older private card forever. */
    @Test
    fun pendingGenerationThatNeverAppearsCancelsOnlyTheOldPrivateCard() =
        runBlocking {
            postPrivate()
            ConversationCardPostedRegistry.markPosted(TAG, MESSAGE_NOTIFICATION_ID, generationId = "never-visible")
            var writes = 0
            val cleanup =
                NotificationPreviewRedactor(
                    context,
                    pacer = pacer,
                    post = { _, _, _, _ -> writes++ },
                    sleep = {},
                )
            assertFalse(cleanup.redact())
            assertEquals(0, writes)
            assertTrue(manager.activeNotifications.none { it.tag == TAG })
            assertTrue(NotificationPreviewRedactor(context, pacer = pacer, sleep = {}).redact())
        }

    private fun postPrivate() {
        val builder =
            NotificationCompat
                .Builder(context, NotificationChannelSpec.GROUP_MESSAGES.id)
                .setSmallIcon(R.drawable.ic_stat_whitenoise)
                .setContentTitle("Private content")
                .addExtras(android.os.Bundle().apply { putString("private_debug", "Private text") })
        UserEventNotificationGroup.decorateChild(
            context,
            builder,
            NotificationGroupChild(TAG, MESSAGE_NOTIFICATION_ID, "old"),
            silent = false,
        )
        manager.notify(TAG, MESSAGE_NOTIFICATION_ID, builder.build())
    }

    companion object {
        private const val TAG = "privacy-fixture"
    }
}
