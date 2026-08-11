package dev.ipf.whitenoise.android.notifications

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import dev.ipf.whitenoise.android.state.NotificationJobSlot
import dev.ipf.whitenoise.android.state.awaitNotificationReceiverForStartup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * API 34 Android lifecycle coverage running in the required CI unit matrix.
 *
 * The fake has MDK's no-replay broadcast semantics while channel creation and
 * the final tray assertion use Android's real NotificationManager.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationColdProcessStartupTest {
    @Test
    fun messageDeliveredDuringColdBootstrapPostsOnceAfterChannelsAreReady() =
        runBlocking {
            val context: Application = RuntimeEnvironment.getApplication()
            val manager = context.getSystemService(NotificationManager::class.java)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val broadcast = NoReplayNotificationBroadcastFake(scope)
            manager.cancel(TEST_TAG, TEST_NOTIFICATION_ID)
            shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

            try {
                // WhiteNoiseAppState performs this platform-only setup before
                // Marmot.start(), so the first receiver-delivered update cannot
                // beat channel or permission readiness.
                NotificationChannels.ensureChannels(context)
                assertNotNull(manager.getNotificationChannel(NotificationChannelSpec.DIRECT_MESSAGES.id))
                assertTrue(manager.areNotificationsEnabled())

                assertTrue(broadcast.establishReceiver())
                broadcast.emit {
                    manager.notify(
                        TEST_TAG,
                        TEST_NOTIFICATION_ID,
                        NotificationCompat
                            .Builder(context, NotificationChannelSpec.DIRECT_MESSAGES.id)
                            .setSmallIcon(android.R.drawable.ic_dialog_email)
                            .setContentTitle("Startup message")
                            .setContentText("Delivered while bootstrap is still running")
                            .build(),
                    )
                }

                waitForNotification(manager)
                assertEquals(
                    1,
                    manager.activeNotifications.count {
                        it.tag == TEST_TAG && it.id == TEST_NOTIFICATION_ID
                    },
                )
            } finally {
                manager.cancel(TEST_TAG, TEST_NOTIFICATION_ID)
                broadcast.close()
                scope.cancel()
            }
        }

    private fun waitForNotification(manager: NotificationManager) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (
            manager.activeNotifications.none { it.tag == TEST_TAG && it.id == TEST_NOTIFICATION_ID } &&
            System.nanoTime() < deadline
        ) {
            Thread.sleep(25L)
        }
    }

    /** A passive, no-replay receiver matching MDK notification subscription semantics. */
    private class NoReplayNotificationBroadcastFake(
        private val scope: CoroutineScope,
    ) {
        private val slot = NotificationJobSlot()
        private val receiverActive = MutableStateFlow(false)
        private val retryWake = MutableStateFlow(0L)

        suspend fun establishReceiver(): Boolean =
            awaitNotificationReceiverForStartup(
                notificationJob = slot,
                receiverActive = receiverActive,
                receiverRetryWake = retryWake,
                timeoutMillis = 5_000L,
                launchListener = ::launchListener,
            )

        fun emit(post: () -> Unit) {
            if (receiverActive.value) post()
        }

        suspend fun close() {
            slot.cancelAndJoin()
        }

        private fun launchListener(): Job =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                receiverActive.value = true
                try {
                    awaitCancellation()
                } finally {
                    receiverActive.value = false
                }
            }
    }

    private companion object {
        const val TEST_TAG = "notification-startup-ordering"
        const val TEST_NOTIFICATION_ID = 1_982
    }
}
