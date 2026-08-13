package dev.ipf.whitenoise.android.notifications

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.os.Looper
import dev.ipf.whitenoise.android.state.NotificationBootstrapTestFixture
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

/** API 34 cold-process coverage through the production WhiteNoiseAppState bootstrap path. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationColdProcessStartupTest {
    @Test
    fun messageDeliveredDuringColdBootstrapPostsOnceAfterChannelsAreReady() =
        runBlocking {
            val context: Application = RuntimeEnvironment.getApplication()
            val manager = context.getSystemService(NotificationManager::class.java)
            val fixture = NotificationBootstrapTestFixture(context)
            shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            manager.activeNotifications.forEach { manager.cancel(it.tag, it.id) }

            try {
                fixture.bootstrap()
                fixture.awaitUpdateConsumed()
                waitForNotification(manager)

                assertTrue(fixture.receiverWasAttachedAtPostStartEmission)
                assertTrue(fixture.channelsWereReadyAtPostStartEmission)
                assertNotNull(manager.getNotificationChannel(NotificationChannelSpec.GROUP_MESSAGES.id))
                assertEquals(
                    1,
                    manager.activeNotifications.size,
                )
            } finally {
                manager.activeNotifications.forEach { manager.cancel(it.tag, it.id) }
                fixture.close()
            }
        }

    private fun waitForNotification(manager: NotificationManager) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (
            manager.activeNotifications.isEmpty() &&
            System.nanoTime() < deadline
        ) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(25L)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }
}
