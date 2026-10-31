package dev.ipf.whitenoise.android.ui.conversation.media

import dev.ipf.whitenoise.android.media.sandbox.ImageInspection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ViewerImageWorkTest {
    @Test
    fun cancelledInspectionOwnsTheLaneUntilItsCodecActuallyFinishes() =
        runBlocking {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val reads = AtomicInteger()
            val inspecting = AtomicInteger()
            val peak = AtomicInteger()
            val first =
                launch(Dispatchers.Default) {
                    withViewerImageWork {
                        reads.incrementAndGet()
                        inspectViewerSource(byteArrayOf(1)) {
                            val now = inspecting.incrementAndGet()
                            peak.updateAndGet { maxOf(it, now) }
                            entered.countDown()
                            try {
                                // Model a codec that does not immediately honor Thread.interrupt.
                                // Ownership must remain held.
                                while (release.count > 0) {
                                    try {
                                        release.await(20, TimeUnit.MILLISECONDS)
                                    } catch (_: InterruptedException) {
                                        // native call still returning
                                    }
                                }
                                ImageInspection(20, 10, "image/png", 1)
                            } finally {
                                inspecting.decrementAndGet()
                            }
                        }
                    }
                }
            val started = entered.await(5, TimeUnit.SECONDS)
            if (!started) {
                release.countDown()
                first.cancelAndJoin()
            }
            assertTrue(started)
            first.cancel()
            val queued = CountDownLatch(1)
            val next =
                launch(Dispatchers.Default) {
                    queued.countDown()
                    withViewerImageWork {
                        reads.incrementAndGet()
                        inspectViewerSource(byteArrayOf(2)) {
                            val now = inspecting.incrementAndGet()
                            peak.updateAndGet { maxOf(it, now) }
                            try {
                                ImageInspection(20, 10, "image/png", 1)
                            } finally {
                                inspecting.decrementAndGet()
                            }
                        }
                    }
                }
            try {
                assertTrue(queued.await(5, TimeUnit.SECONDS))
                assertEquals(1, reads.get())
            } finally {
                release.countDown()
            }
            withTimeout(5_000) {
                first.cancelAndJoin()
                next.join()
            }
            assertEquals(2, reads.get())
            assertEquals(1, peak.get())
        }
}
