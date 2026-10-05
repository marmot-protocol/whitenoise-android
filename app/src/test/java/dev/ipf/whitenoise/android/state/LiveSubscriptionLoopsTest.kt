package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
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

    @Test
    fun cancelsAttemptScopedJobsWithoutWaitingForNaturalCompletion() =
        runTest {
            val watcherStarted = CompletableDeferred<Unit>()
            val watcherCancelled = CompletableDeferred<Unit>()

            val startedAt = testScheduler.currentTime
            withTimeout(200L) {
                coroutineScope {
                    runUntilFirstLiveSubscriptionEndsWithAttemptJobs(
                        startAttemptJobs = {
                            launch {
                                watcherStarted.complete(Unit)
                                try {
                                    delay(60_000L)
                                } finally {
                                    watcherCancelled.complete(Unit)
                                    withContext(NonCancellable) {
                                        delay(500L)
                                    }
                                }
                            }
                        },
                        first = {
                            watcherStarted.await()
                        },
                        second = { delay(60_000L) },
                    )
                }
            }

            val elapsedMs = testScheduler.currentTime - startedAt
            withTimeout(100L) {
                watcherCancelled.await()
            }
            assertTrue("attempt returned after ${elapsedMs}ms of virtual time", elapsedMs < 200L)
            advanceUntilIdle()
        }
}
