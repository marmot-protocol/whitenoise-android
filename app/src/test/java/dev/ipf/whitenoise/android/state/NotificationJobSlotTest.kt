package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun handoffKeepsPreviousJobUntilReplacementSubscribes() =
        runBlocking {
            val slot = NotificationJobSlot()
            lateinit var oldJob: Job
            val replacementReachedSubscribe = CompletableDeferred<Unit>()
            val handoffCompleted = CompletableDeferred<Unit>()
            var previousActiveWhenReplacementSubscribes = false

            slot.startIfInactive {
                launch { awaitCancellation() }.also { oldJob = it }
            }

            val handoffJob =
                launch {
                    slot.handoff { ready ->
                        launch {
                            previousActiveWhenReplacementSubscribes = oldJob.isActive
                            replacementReachedSubscribe.complete(Unit)
                            ready.complete(Unit)
                            awaitCancellation()
                        }
                    }
                    handoffCompleted.complete(Unit)
                }

            try {
                replacementReachedSubscribe.await()
                assertTrue(
                    "previous listener must remain active until replacement has subscribed",
                    previousActiveWhenReplacementSubscribes,
                )
                handoffCompleted.await()
                assertTrue("previous listener must be cancelled after the replacement is ready", oldJob.isCancelled)
            } finally {
                handoffJob.cancelAndJoin()
                slot.cancelAndJoin()
            }
        }

    @Test
    fun handoffKeepsPreviousJobWhenReplacementFailsBeforeReady() =
        runBlocking {
            val slot = NotificationJobSlot()
            var oldJobCancelled = false

            slot.startIfInactive {
                launch {
                    try {
                        awaitCancellation()
                    } finally {
                        oldJobCancelled = true
                    }
                }
            }

            try {
                val failure =
                    runCatching {
                        slot.handoff { _ ->
                            Job().apply {
                                completeExceptionally(IllegalStateException("subscribeNotifications failed"))
                            }
                        }
                    }

                assertTrue("replacement failure must be reported to the caller", failure.isFailure)

                assertTrue("old listener must stay in the slot", slot.isActive())
                assertFalse("old listener must not be cancelled on replacement failure", oldJobCancelled)
            } finally {
                slot.cancelAndJoin()
            }
        }

    @Test
    fun cancelAndJoinCancelsReplacementWaitingForReadiness() =
        runBlocking {
            val slot = NotificationJobSlot()
            val replacementStarted = CompletableDeferred<Unit>()
            lateinit var replacement: Job

            slot.startIfInactive { Job() }
            val handoffJob =
                launch {
                    runCatching {
                        slot.handoff {
                            Job().also {
                                replacement = it
                                replacementStarted.complete(Unit)
                            }
                        }
                    }
                }

            replacementStarted.await()
            try {
                slot.cancelAndJoin()
                assertTrue("account teardown must cancel a replacement waiting for readiness", replacement.isCancelled)
            } finally {
                replacement.cancel()
                handoffJob.cancelAndJoin()
            }
        }

    @Test
    fun handoffRegistersReplacementBeforeConcurrentCancellation() {
        val slot = NotificationJobSlot()
        val replacementStartEntered = CountDownLatch(1)
        val allowReplacementStartToReturn = CountDownLatch(1)
        val cancellationAttempted = CountDownLatch(1)
        val cancellationReturned = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        var replacement: Job? = null

        slot.startIfInactive { Job() }
        val handoff =
            pool.submit {
                runBlocking {
                    runCatching {
                        slot.handoff {
                            val replacementJob = Job()
                            replacement = replacementJob
                            replacementStartEntered.countDown()
                            assertTrue(
                                "replacement start was not released",
                                allowReplacementStartToReturn.await(2, TimeUnit.SECONDS),
                            )
                            replacementJob
                        }
                    }
                }
            }

        try {
            assertTrue("replacement did not start", replacementStartEntered.await(2, TimeUnit.SECONDS))
            val cancellation =
                pool.submit {
                    runBlocking {
                        cancellationAttempted.countDown()
                        slot.cancelAndJoin()
                        cancellationReturned.countDown()
                    }
                }
            assertTrue("cancellation did not start", cancellationAttempted.await(2, TimeUnit.SECONDS))
            assertFalse(
                "cancellation must not miss a replacement between start and slot registration",
                cancellationReturned.await(200, TimeUnit.MILLISECONDS),
            )

            allowReplacementStartToReturn.countDown()
            cancellation.get(2, TimeUnit.SECONDS)
            handoff.get(2, TimeUnit.SECONDS)
            assertTrue("concurrent cancellation must own and cancel the replacement", replacement?.isCancelled == true)
        } finally {
            allowReplacementStartToReturn.countDown()
            replacement?.cancel()
            runBlocking { slot.cancelAndJoin() }
            handoff.cancel(true)
            pool.shutdownNow()
        }
    }
}
