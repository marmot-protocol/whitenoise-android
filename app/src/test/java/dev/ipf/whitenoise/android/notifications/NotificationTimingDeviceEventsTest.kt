package dev.ipf.whitenoise.android.notifications

import android.app.Notification
import android.os.Process
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class NotificationTimingDeviceEventsTest {
    private val listener = NotificationTimingListenerService()

    @Before
    fun setUp() = NotificationTimingDeviceEvents.clear()

    @After
    fun tearDown() = NotificationTimingDeviceEvents.clear()

    /** A callback paused after matching the old target cannot leak into the newly armed capture. */
    @Test
    fun rearmingCannotRetainAnInFlightOldPost() {
        raceWithTargetChange(
            deliver = listener::onNotificationPosted,
            changeTarget = { NotificationTimingDeviceEvents.arm(PACKAGE, TAG, 2) },
        )
        assertNull(NotificationTimingDeviceEvents.awaitPost(0))
        listener.onNotificationPosted(notification(id = 2))
        assertEquals(2, NotificationTimingDeviceEvents.awaitPost(0)?.id)
    }

    /** Cleanup cannot be followed by an already-accepted callback repopulating the post queue. */
    @Test
    fun clearingCannotRetainAnInFlightPost() {
        raceWithTargetChange(listener::onNotificationPosted, NotificationTimingDeviceEvents::clear)
        listener.onNotificationPosted(notification())
        assertNull(NotificationTimingDeviceEvents.awaitPost(0))
    }

    /** Card removal uses the same target fence as posts, including a replacement capture. */
    @Test
    fun rearmingCannotRetainAnInFlightOldRemoval() {
        raceWithTargetChange(
            deliver = ::deliverRemoval,
            changeTarget = { NotificationTimingDeviceEvents.arm(PACKAGE, TAG, 2) },
        )
        assertNull(NotificationTimingDeviceEvents.awaitRemoval(0))
        deliverRemoval(notification(id = 2))
        val removal = NotificationTimingDeviceEvents.awaitRemoval(0)
        assertEquals(2, removal?.id)
        assertEquals(NotificationListenerService.REASON_CANCEL, removal?.reason)
    }

    /** Cleanup also fences removals already materializing on a callback thread. */
    @Test
    fun clearingCannotRetainAnInFlightRemoval() {
        raceWithTargetChange(::deliverRemoval, NotificationTimingDeviceEvents::clear)
        deliverRemoval(notification())
        assertNull(NotificationTimingDeviceEvents.awaitRemoval(0))
    }

    /** Neither queue accepts another package, tag, or id even when the other fields match. */
    @Test
    fun callbacksRequireTheExactArmedTarget() {
        NotificationTimingDeviceEvents.arm(PACKAGE, TAG, 1)
        listOf(notification(packageName = "other.package"), notification(tag = "other-tag"), notification(id = 2))
            .forEach {
                listener.onNotificationPosted(it)
                deliverRemoval(it)
            }
        assertNull(NotificationTimingDeviceEvents.awaitPost(0))
        assertNull(NotificationTimingDeviceEvents.awaitRemoval(0))
        listener.onNotificationPosted(notification())
        deliverRemoval(notification())
        assertEquals(1, NotificationTimingDeviceEvents.awaitPost(0)?.id)
        assertEquals(1, NotificationTimingDeviceEvents.awaitRemoval(0)?.id)
    }

    /** Reusing a tuple for a new capture still discards both queues from the prior capture. */
    @Test
    fun rearmingTheSameTargetDropsPreviouslyQueuedEvents() {
        NotificationTimingDeviceEvents.arm(PACKAGE, TAG, 1)
        listener.onNotificationPosted(notification())
        deliverRemoval(notification())
        NotificationTimingDeviceEvents.arm(PACKAGE, TAG, 1)
        assertNull(NotificationTimingDeviceEvents.awaitPost(0))
        assertNull(NotificationTimingDeviceEvents.awaitRemoval(0))
    }

    /** Exercises the framework removal entry point without depending on unused ranking data. */
    private fun deliverRemoval(notification: StatusBarNotification) {
        listener.onNotificationRemoved(
            notification,
            ReflectionHelpers.newInstance(NotificationListenerService.RankingMap::class.java),
            NotificationListenerService.REASON_CANCEL,
        )
    }

    /** Pauses event materialization while another thread either replaces the target or waits for its lock. */
    private fun raceWithTargetChange(
        deliver: (StatusBarNotification) -> Unit,
        changeTarget: () -> Unit,
    ) {
        NotificationTimingDeviceEvents.arm(PACKAGE, TAG, 1)
        val readingKey = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val changingTarget = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val callback =
            Thread {
                try {
                    deliver(
                        notification {
                            readingKey.countDown()
                            check(releaseCallback.await(5, TimeUnit.SECONDS))
                        },
                    )
                } catch (throwable: Throwable) {
                    failure.compareAndSet(null, throwable)
                }
            }
        val transition =
            Thread {
                try {
                    changingTarget.countDown()
                    changeTarget()
                } catch (throwable: Throwable) {
                    failure.compareAndSet(null, throwable)
                }
            }
        try {
            callback.start()
            assertTrue("Callback did not reach event materialization", readingKey.await(5, TimeUnit.SECONDS))
            transition.start()
            assertTrue(changingTarget.await(5, TimeUnit.SECONDS))
            awaitTargetTransition(transition)
        } finally {
            releaseCallback.countDown()
            callback.join(5_000)
            transition.join(5_000)
        }
        assertFalse("Callback did not finish", callback.isAlive)
        assertFalse("Target transition did not finish", transition.isAlive)
        failure.get()?.let { throw AssertionError("Concurrent callback failed", it) }
    }

    /** Requires a real competing transition, not a thread that simply has not been scheduled yet. */
    private fun awaitTargetTransition(transition: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (transition.isAlive && transition.state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
            Thread.yield()
        }
        assertTrue(
            "Target transition neither finished nor contended with the callback",
            !transition.isAlive || transition.state == Thread.State.BLOCKED,
        )
    }

    /** Supplies a synthetic framework notification with a controllable key-read boundary. */
    @Suppress("DEPRECATION")
    private fun notification(
        id: Int = 1,
        packageName: String = PACKAGE,
        tag: String = TAG,
        beforeKeyRead: () -> Unit = {},
    ): StatusBarNotification =
        object : StatusBarNotification(
            packageName,
            packageName,
            id,
            tag,
            1_000,
            0,
            0,
            Notification(),
            Process.myUserHandle(),
            0,
        ) {
            override fun getKey(): String {
                beforeKeyRead()
                return super.getKey()
            }
        }

    private companion object {
        const val PACKAGE = "dev.ipf.whitenoise.android.timingtest"
        const val TAG = "synthetic-timing"
    }
}
