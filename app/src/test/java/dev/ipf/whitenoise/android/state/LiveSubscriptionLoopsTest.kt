package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSubscriptionLoopsTest {
    @Test
    fun rethrowsFirstConsumerFailure() {
        val seen =
            runBlocking {
                var caught: Throwable? = null
                try {
                    coroutineScope {
                        runUntilFirstLiveSubscriptionEnds(
                            first = { throw IllegalStateException("stream failed") },
                            second = { delay(60_000) },
                        )
                    }
                } catch (throwable: Throwable) {
                    caught = throwable
                }
                caught
            }
        assertTrue(seen is IllegalStateException)
        assertEquals("stream failed", seen?.message)
    }
}
