package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class RecoveryProjectionGenerationHandoffTest {
    @Test
    fun concurrentPublicationsKeepTheNewestGenerationAndConsumeItOnce() {
        val handoff = RecoveryProjectionGenerationHandoff()
        val start = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val publishers =
            listOf(7L, 11L).map { generation ->
                thread {
                    start.await()
                    handoff.publish(generation)
                    finished.countDown()
                }
            }

        start.countDown()
        finished.await()
        publishers.forEach(Thread::join)

        assertEquals(11L, handoff.consume())
        assertEquals(0L, handoff.consume())
    }
}
