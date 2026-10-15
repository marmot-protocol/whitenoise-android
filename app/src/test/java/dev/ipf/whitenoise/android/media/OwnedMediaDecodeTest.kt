package dev.ipf.whitenoise.android.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class OwnedMediaDecodeTest {
    @Test
    fun cancellationDisposesAResultCreatedBeforeItsDelivery() =
        runBlocking {
            val entered = CountDownLatch(1)
            val releases = AtomicInteger()
            val job =
                launch(Dispatchers.Unconfined) {
                    decodeOwnedMedia(decode = {
                        val owned = Any()
                        entered.countDown()
                        try {
                            CountDownLatch(1).await(5, TimeUnit.SECONDS)
                        } catch (_: InterruptedException) {
                            // The decoder just finished producing a result as delivery was cancelled.
                        }
                        owned
                    }, discard = { releases.incrementAndGet() })
                }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            job.cancel()
            job.join()
            assertEquals(1, releases.get())
        }

    @Test
    fun successfullyDeliveredOwnershipIsNotDiscarded() =
        runBlocking {
            val owned = Any()
            val releases = AtomicInteger()
            assertEquals(owned, decodeOwnedMedia(decode = { owned }, discard = { releases.incrementAndGet() }))
            assertEquals(0, releases.get())
        }
}
