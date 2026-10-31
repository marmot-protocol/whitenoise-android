package dev.ipf.whitenoise.android.media.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MediaParentResourcesTest {
    @Test
    fun cancelCannotReleaseAnAlreadyRunningCleanupTask() {
        val resources = MediaParentResources()
        val running = resources.task()
        assertTrue(running.start())
        running.cancelBeforeStart()
        assertFalse(resources.available())
        running.finish()
        assertTrue(resources.available())
        val queued = resources.task()
        queued.cancelBeforeStart()
        assertFalse(queued.start())
        assertTrue(resources.available())
    }

    @Test
    fun concurrentCloseCannotAcknowledgeBeforeTheActualCloseReturns() {
        val resources = MediaParentResources()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var calls = 0
        val owner =
            resources.own(
                AutoCloseable {
                    calls++
                    entered.countDown()
                    assertTrue(release.await(2, TimeUnit.SECONDS))
                },
            )
        val first = Thread { owner.close() }.apply { start() }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            owner.close() // The watchdog/finally duplicate never invents a successful close.
            assertFalse(resources.available())
            assertEquals(1, calls)
        } finally {
            release.countDown()
            first.join(2000)
        }
        assertFalse(first.isAlive)
        assertTrue(resources.available())
    }

    @Test
    fun failedCloseRemainsHeldWithoutRetryOrReset() {
        val resources = MediaParentResources()
        var calls = 0
        val owner =
            resources.own(
                AutoCloseable {
                    calls++
                    throw IOException("Synthetic close failure")
                },
            )
        assertTrue(runCatching { owner.close() }.exceptionOrNull() is IOException)
        owner.close()
        assertEquals(1, calls)
        assertFalse(resources.healthy())
        assertFalse(resources.available())
    }
}
