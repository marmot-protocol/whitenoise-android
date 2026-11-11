package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationJobSlotTest {
    @Test
    fun reconnectWakeDuringFailedSubscribeSkipsThePendingBackoff() =
        runTest {
            val retryWake = MutableStateFlow(0L)
            val capturedBeforeSubscribe = retryWake.value

            // Models reconnect arriving while subscribe/cleanup is still in flight.
            retryWake.value += 1L
            awaitNotificationRetryWindow(
                retryWake = retryWake,
                capturedGeneration = capturedBeforeSubscribe,
                backoffMillis = 60_000L,
            )

            assertEquals(0L, currentTime)
        }

    @Test
    fun listenerUsesTheNormalBackoffWithoutAReconnectWake() =
        runTest {
            val retryWake = MutableStateFlow(0L)

            awaitNotificationRetryWindow(
                retryWake = retryWake,
                capturedGeneration = retryWake.value,
                backoffMillis = 1_000L,
            )

            assertEquals(1_000L, currentTime)
        }

    @Test
    fun listenerBackoffRemainsCancellable() =
        runTest {
            val retryWake = MutableStateFlow(0L)
            val waiting =
                async(start = CoroutineStart.UNDISPATCHED) {
                    awaitNotificationRetryWindow(
                        retryWake = retryWake,
                        capturedGeneration = retryWake.value,
                        backoffMillis = 60_000L,
                    )
                }

            runCurrent()
            assertFalse(waiting.isCompleted)
            waiting.cancel()
            assertTrue(waiting.isCancelled)
        }

    @Test
    fun activeReceiverIsReusedWithoutWaiting() =
        runTest {
            var waited = false

            val ready =
                awaitActiveNotificationReceiver(
                    isReceiverActive = { true },
                    listenerJob = Job(),
                    awaitReceiverActive = { waited = true },
                )

            assertTrue(ready)
            assertFalse(waited)
        }

    @Test
    fun reconnectAwaitsTheReceiverOwnedByTheCurrentListenerJob() =
        runTest {
            var receiverActive = false
            val receiverAttached = CompletableDeferred<Unit>()
            val listenerJob = Job()

            val result =
                async(start = CoroutineStart.UNDISPATCHED) {
                    awaitActiveNotificationReceiver(
                        isReceiverActive = { receiverActive },
                        listenerJob = listenerJob,
                        awaitReceiverActive = { receiverAttached.await() },
                    )
                }

            assertFalse("the receiver was not active yet", result.isCompleted)
            receiverActive = true
            receiverAttached.complete(Unit)

            assertTrue(result.await())
            listenerJob.cancel()
        }

    @Test
    fun reconnectStopsWaitingWhenTheOwningListenerEnds() =
        runTest {
            val listenerJob = Job()

            val result =
                async {
                    awaitActiveNotificationReceiver(
                        isReceiverActive = { false },
                        listenerJob = listenerJob,
                        awaitReceiverActive = { awaitCancellation() },
                    )
                }

            listenerJob.complete()

            assertFalse(result.await())
        }

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

    /**
     * The slot stays reserved while its cancelled job is still stopping. The teardown gate is released
     * in `finally`, so a failed reservation assertion ends the test instead of parking it (#3727).
     */
    @Test
    fun cancellationReservesTheSlotUntilTheOwnedJobHasStopped() =
        runBlocking {
            val slot = NotificationJobSlot()
            val cancellationEntered = CompletableDeferred<Unit>()
            val releaseCancellation = CompletableDeferred<Unit>()
            val listener =
                launch {
                    try {
                        awaitCancellation()
                    } finally {
                        cancellationEntered.complete(Unit)
                        withContext(NonCancellable) {
                            releaseCancellation.await()
                        }
                    }
                }
            slot.startIfInactive { listener }

            val cancellation = launch { slot.cancelAndJoin() }
            var replacementStarts = 0
            try {
                withTimeout(SLOT_BOUND_MS) { cancellationEntered.await() }

                val duringCancellation =
                    slot.currentOrStart {
                        replacementStarts += 1
                        Job()
                    }
                assertNull("a concurrent startup must not escape account teardown", duringCancellation)
                assertEquals(0, replacementStarts)
            } finally {
                // Release the NonCancellable teardown even when an assertion failed, so the scope can finish.
                releaseCancellation.complete(Unit)
                withTimeout(SLOT_BOUND_MS) { cancellation.join() }
            }

            val afterCancellation =
                slot.currentOrStart {
                    replacementStarts += 1
                    Job()
                }
            assertNotNull(afterCancellation)
            assertEquals(1, replacementStarts)
            slot.cancelAndJoin()
        }

    private companion object {
        /** Real-time bound for the parked teardown to be reached and released. */
        const val SLOT_BOUND_MS = 5_000L
    }
}
