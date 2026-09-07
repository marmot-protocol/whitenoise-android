package dev.ipf.whitenoise.android.ui.navigation

import android.os.Handler
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Regression coverage for the notification-route fixture's real-worker startup boundary. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class NotificationRouteTimelinePresentationFixtureTest : NotificationRouteTimelinePresentationFixture() {
    /** Yields to immediate worker work without firing an unrelated future main-loop deadline. */
    @Test
    fun initialRouteWaitDoesNotAdvanceFutureMainDeadline() {
        val handler = Handler(Looper.getMainLooper())
        val delayedDeadline = CountDownLatch(1)
        val workerStarted = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val workerCompleted = CountDownLatch(1)
        val polls = AtomicInteger(0)
        val delayedCallback = Runnable(delayedDeadline::countDown)
        val worker =
            Thread {
                workerStarted.countDown()
                releaseWorker.await()
                workerCompleted.countDown()
            }
        handler.postDelayed(delayedCallback, 20L)
        worker.start()
        try {
            check(workerStarted.await(1L, TimeUnit.SECONDS)) { "real worker did not start" }
            awaitInitialRouteCondition(
                failureMessage = { "real worker did not complete before the route deadline" },
            ) {
                if (polls.incrementAndGet() == 3) releaseWorker.countDown()
                workerCompleted.count == 0L
            }
            assertEquals("startup wait advanced a future main deadline", 1L, delayedDeadline.count)
        } finally {
            releaseWorker.countDown()
            handler.removeCallbacks(delayedCallback)
            worker.join(1_000L)
        }
    }
}
