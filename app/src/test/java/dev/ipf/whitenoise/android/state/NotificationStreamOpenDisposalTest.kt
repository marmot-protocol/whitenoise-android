package dev.ipf.whitenoise.android.state

import android.app.Application
import android.app.NotificationManager
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNotificationManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Stream-open cleanup owns its event subscription even while Android's tray snapshot is blocked. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [StreamOpenSnapshotNotificationManager::class])
class NotificationStreamOpenDisposalTest {
    /** Cancellation during the first platform snapshot still destroys the opened subscription exactly once. */
    @Test
    fun cancellationDuringTheOpenSnapshotDisposesTheSubscription() =
        runBlocking {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val context: Application = RuntimeEnvironment.getApplication()
            val fixture = NotificationBootstrapTestFixture(context = context, emitStartupNotification = false)
            StreamOpenSnapshotNotificationManager.beforeSnapshot = {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
            val listener = launch(Dispatchers.IO) { fixture.runReceiptLoop() }
            try {
                withTimeout(5_000L) {
                    while (entered.count > 0L) delay(1L)
                }
                assertEquals(1, fixture.eventsSubscriptions.get())
                listener.cancel()
                release.countDown()
                withTimeout(5_000L) { listener.join() }
                assertTrue(listener.isCancelled)
                assertEquals(
                    "cancellation must not leak the just-opened stream",
                    1,
                    fixture.destroyedEventSubscriptions.get(),
                )
            } finally {
                release.countDown()
                listener.cancelAndJoin()
                StreamOpenSnapshotNotificationManager.beforeSnapshot = null
                fixture.close()
            }
        }
}

/** Holds only the stream-open Android snapshot; no native read or platform notification is fabricated. */
@Implements(NotificationManager::class)
class StreamOpenSnapshotNotificationManager : ShadowNotificationManager() {
    /** Pauses the synchronous snapshot at the lifecycle boundary before delegating to Robolectric. */
    @Implementation
    override fun getActiveNotifications(): Array<StatusBarNotification> {
        beforeSnapshot?.invoke()
        return super.getActiveNotifications()
    }

    companion object {
        @Volatile
        var beforeSnapshot: (() -> Unit)? = null
    }
}
