package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

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
    fun cancelsAttemptScopedJobsWithoutWaitingForNaturalCompletion() {
        runBlocking {
            val watcherStarted = CompletableDeferred<Unit>()
            val watcherCancelled = CompletableDeferred<Unit>()

            val elapsedMs =
                measureTimeMillis {
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
                }

            withTimeout(100L) {
                watcherCancelled.await()
            }
            assertTrue("attempt returned after ${elapsedMs}ms", elapsedMs < 200L)
        }
    }
}
