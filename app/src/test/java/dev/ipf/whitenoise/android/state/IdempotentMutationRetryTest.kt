package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotKitException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins bounded contention recovery and the short transport retry boundary for local mutations. */
@OptIn(ExperimentalCoroutinesApi::class)
class IdempotentMutationRetryTest {
    /** Typed contention that cannot have started the mutation remains safe to retry. */
    @Test
    fun retriesTypedRuntimeAndStorageContention() =
        runTest {
            val transientFailures =
                listOf(
                    MarmotKitException.AccountWorkerBusy(),
                    MarmotKitException.RuntimeBusy(),
                    MarmotKitException.AccountSessionBusy(),
                    MarmotKitException.StorageBusy("database is locked"),
                )

            transientFailures.forEach { transientFailure ->
                var attempts = 0
                val result =
                    retryIdempotentRuntimeMutation {
                        attempts += 1
                        if (attempts == 1) throw transientFailure
                        "accepted"
                    }

                assertEquals("accepted", result)
                assertEquals(2, attempts)
            }
        }

    /** Ambiguous or terminal failures must never repeat an idempotent mutation. */
    @Test
    fun excludesAmbiguousAndTerminalBusyFailures() {
        val terminalFailures =
            listOf(
                MarmotKitException.AccountWorkerResponseTimedOut(),
                MarmotKitException.GroupSendQueueFull("group"),
                MarmotKitException.Publish("relay rejected event"),
                MarmotKitException.UnknownGroup("group"),
            )

        terminalFailures.forEach { failure ->
            assertFalse(isRetryableIdempotentMutationError(failure))
        }
    }

    /** Persistent contention stops at the configured attempt budget. */
    @Test
    fun persistentContentionExhaustsTheBoundedBudget() =
        runTest {
            val attemptTimes = mutableListOf<Long>()
            val failure =
                runCatching {
                    retryIdempotentRuntimeMutation(timeSource = testScheduler.timeSource) {
                        attemptTimes += testScheduler.currentTime
                        throw MarmotKitException.StorageBusy("database is locked")
                    }
                }.exceptionOrNull()

            assertTrue(failure is MarmotKitException.StorageBusy)
            assertEquals(listOf(0L, 700L, 2_100L, 4_900L, 9_900L, 14_900L, 19_900L, 24_900L, 29_900L), attemptTimes)
        }

    /** Several seconds of account catch-up must not exhaust the old 1.4-second window. */
    @Test
    fun retriesUntilMultiSecondCatchUpReleasesTheWorker() =
        runTest {
            var attempts = 0
            val result =
                retryIdempotentRuntimeMutation(timeSource = testScheduler.timeSource) {
                    attempts += 1
                    if (testScheduler.currentTime < 10_000L) throw MarmotKitException.AccountWorkerBusy()
                    "accepted"
                }

            assertEquals("accepted", result)
            assertEquals(6, attempts)
            assertEquals(14_900L, testScheduler.currentTime)
        }

    /** Native SQLite wait time consumes the same retry window instead of multiplying it. */
    @Test
    fun slowNativeFailureCannotStartAnotherAttemptAfterTheDeadline() =
        runTest {
            var attempts = 0
            val expected = MarmotKitException.StorageBusy("database is locked")
            val failure =
                runCatching {
                    retryIdempotentRuntimeMutation(timeSource = testScheduler.timeSource) {
                        attempts += 1
                        delay(30_000L)
                        throw expected
                    }
                }.exceptionOrNull()

            assertTrue(failure === expected)
            assertEquals(1, attempts)
        }

    /** The retry window never cancels or discards a successful in-flight acceptance. */
    @Test
    fun slowSuccessfulMutationIsNotTimedOutOrReplayed() =
        runTest {
            var attempts = 0
            val result =
                retryIdempotentRuntimeMutation(timeSource = testScheduler.timeSource) {
                    attempts += 1
                    delay(31_000L)
                    "accepted"
                }

            assertEquals("accepted", result)
            assertEquals(1, attempts)
        }

    /** Non-contention failures retain the original retry count and fixed delay. */
    @Test
    fun transportFailureStillStopsAfterThreeCalls() =
        runTest {
            val attemptTimes = mutableListOf<Long>()
            val failure =
                runCatching {
                    retryIdempotentRuntimeMutation(timeSource = testScheduler.timeSource) {
                        attemptTimes += testScheduler.currentTime
                        throw MarmotKitException.TransportClosed()
                    }
                }.exceptionOrNull()

            assertTrue(failure is MarmotKitException.TransportClosed)
            assertEquals(listOf(0L, 700L, 1_400L), attemptTimes)
        }

    /** Earlier busy results do not grant a subsequent transport failure a longer budget. */
    @Test
    fun transportFailureAfterContentionRetainsTheShortBudget() =
        runTest {
            var attempts = 0
            val expected = MarmotKitException.TransportClosed()
            val failure =
                runCatching {
                    retryIdempotentRuntimeMutation(timeSource = testScheduler.timeSource) {
                        attempts += 1
                        if (attempts < 3) throw MarmotKitException.AccountWorkerBusy()
                        throw expected
                    }
                }.exceptionOrNull()

            assertTrue(failure === expected)
            assertEquals(3, attempts)
            assertEquals(2_100L, testScheduler.currentTime)
        }

    /** A coroutine resumed after the elapsed window cannot re-enter the native mutation. */
    @Test
    fun expiredWindowAfterSuspensionDoesNotStartAnotherCall() =
        runTest {
            var attempts = 0
            val expected = MarmotKitException.AccountWorkerBusy()
            val failure =
                runCatching {
                    retryIdempotentRuntimeMutation(
                        onTransientFailure = { delay(31_000L) },
                        timeSource = testScheduler.timeSource,
                    ) {
                        attempts += 1
                        throw expected
                    }
                }.exceptionOrNull()

            assertTrue(failure === expected)
            assertEquals(1, attempts)
        }

    /** An ambiguous timeout after safe contention is returned immediately. */
    @Test
    fun ambiguousFailureDuringExtendedRetryIsNotReplayed() =
        runTest {
            var attempts = 0
            val expected = MarmotKitException.AccountWorkerResponseTimedOut()
            val failure =
                runCatching {
                    retryIdempotentRuntimeMutation(timeSource = testScheduler.timeSource) {
                        attempts += 1
                        if (attempts < 5) throw MarmotKitException.AccountWorkerBusy()
                        throw expected
                    }
                }.exceptionOrNull()

            assertTrue(failure === expected)
            assertEquals(5, attempts)
        }

    /** Cancellation interrupts the retry delay without spending another attempt. */
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun cancellationStopsRetryDuringBackoff() =
        runTest {
            var attempts = 0
            val retry =
                async {
                    retryIdempotentRuntimeMutation {
                        attempts += 1
                        throw MarmotKitException.AccountWorkerBusy()
                    }
                }

            advanceTimeBy(10_000L)
            runCurrent()
            assertEquals(5, attempts)

            retry.cancelAndJoin()

            assertTrue(retry.isCancelled)
            assertEquals(5, attempts)
        }
}
