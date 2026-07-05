package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NotificationJobSlotTest {
    @Test
    fun startIfInactiveStartsOnlyOneJobAcrossConcurrentCallers() {
        val slot = NotificationJobSlot()
        val started = AtomicInteger(0)
        val ready = CountDownLatch(32)
        val go = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(32)

        try {
            val futures =
                (1..32).map {
                    pool.submit {
                        ready.countDown()
                        assertTrue("workers did not line up", go.await(2, TimeUnit.SECONDS))
                        slot.startIfInactive {
                            started.incrementAndGet()
                            Job()
                        }
                    }
                }
            assertTrue("workers did not start", ready.await(2, TimeUnit.SECONDS))
            go.countDown()
            futures.forEach { it.get(2, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertEquals(1, started.get())
    }
}
