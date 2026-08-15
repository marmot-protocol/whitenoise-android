package dev.ipf.whitenoise.android.notifications

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationRuntimeSupervisorTest {
    @Test
    fun firstAttemptSuccessDoesNotScheduleRetry() =
        runTest {
            val waits = mutableListOf<Long>()
            var attempts = 0
            val supervisor = testSupervisor(waits)

            val outcome =
                supervisor.supervise(
                    recoveryAllowed = { true },
                    startRuntime = { attempts += 1 },
                )

            assertEquals(NotificationRuntimeSupervisionOutcome.Started(attempts = 1), outcome)
            assertEquals(1, attempts)
            assertTrue(waits.isEmpty())
        }

    @Test
    fun transientFailuresRetryOnceAtATimeAndThenRecover() =
        runTest {
            val waits = mutableListOf<Long>()
            val failures = mutableListOf<Triple<Int, Long?, String>>()
            var attempts = 0
            val supervisor = testSupervisor(waits)

            val outcome =
                supervisor.supervise(
                    recoveryAllowed = { true },
                    startRuntime = {
                        attempts += 1
                        if (attempts < 3) error("transient")
                    },
                    onAttemptFailed = { attempt, delay, failureClass ->
                        failures += Triple(attempt, delay, failureClass)
                    },
                )

            assertEquals(NotificationRuntimeSupervisionOutcome.Started(attempts = 3), outcome)
            assertEquals(listOf(100L, 200L), waits)
            assertEquals(
                listOf(
                    Triple(1, 100L, "IllegalStateException"),
                    Triple(2, 200L, "IllegalStateException"),
                ),
                failures,
            )
        }

    @Test
    fun retryExhaustionIsBoundedAndReportsOnlyFailureClass() =
        runTest {
            val waits = mutableListOf<Long>()
            val supervisor = testSupervisor(waits)
            var attempts = 0

            val outcome =
                supervisor.supervise(
                    recoveryAllowed = { true },
                    startRuntime = {
                        attempts += 1
                        throw RelayBootstrapFailure("wss://private.example/account-pubkey")
                    },
                )

            assertEquals(
                NotificationRuntimeSupervisionOutcome.Exhausted(
                    attempts = 4,
                    failureClass = "RelayBootstrapFailure",
                ),
                outcome,
            )
            assertEquals(4, attempts)
            assertEquals(listOf(100L, 200L, 400L), waits)
            assertTrue(outcome.toString().contains("private.example").not())
        }

    @Test
    fun destructiveRecoveryBoundaryCancelsCapturedRetryBeforeAnotherAttempt() =
        runTest {
            val waits = mutableListOf<Long>()
            var allowed = true
            var attempts = 0
            val supervisor = testSupervisor(waits)

            val outcome =
                supervisor.supervise(
                    recoveryAllowed = { allowed },
                    startRuntime = {
                        attempts += 1
                        allowed = false
                        error("wipe started")
                    },
                )

            assertEquals(NotificationRuntimeSupervisionOutcome.RecoveryBoundaryChanged(attempts = 1), outcome)
            assertEquals(1, attempts)
            assertTrue(waits.isEmpty())
        }

    @Test
    fun recoveryBoundaryThatChangesDuringSuccessfulStartStillWins() =
        runTest {
            var allowed = true
            var attempts = 0
            val supervisor = testSupervisor(mutableListOf())

            val outcome =
                supervisor.supervise(
                    recoveryAllowed = { allowed },
                    startRuntime = {
                        attempts += 1
                        allowed = false
                    },
                )

            assertEquals(NotificationRuntimeSupervisionOutcome.RecoveryBoundaryChanged(attempts = 1), outcome)
            assertEquals(1, attempts)
        }

    @Test
    fun serviceDestructionCancellationStopsSupervision() =
        runTest {
            var attempts = 0
            val supervisor = testSupervisor(mutableListOf())
            val job =
                launch {
                    supervisor.supervise(
                        recoveryAllowed = { true },
                        startRuntime = {
                            attempts += 1
                            awaitCancellation()
                        },
                    )
                }

            testScheduler.runCurrent()
            job.cancelAndJoin()

            assertEquals(1, attempts)
            assertTrue(job.isCancelled)
        }

    @Test(expected = CancellationException::class)
    fun cancellationFromRuntimeIsNeverRetried() =
        runTest {
            testSupervisor(mutableListOf()).supervise(
                recoveryAllowed = { true },
                startRuntime = { throw CancellationException("service destroyed") },
            )
        }

    @Test(expected = OutOfMemoryError::class)
    fun fatalRuntimeErrorsAreNeverRetried() =
        runTest {
            testSupervisor(mutableListOf()).supervise(
                recoveryAllowed = { true },
                startRuntime = { throw OutOfMemoryError("fatal") },
            )
        }

    private fun testSupervisor(waits: MutableList<Long>) =
        NotificationRuntimeSupervisor(
            policy =
                NotificationRuntimeRetryPolicy(
                    maxAttempts = 4,
                    initialDelayMillis = 100L,
                    maxDelayMillis = 400L,
                ),
            waitBeforeRetry = { waits += it },
        )

    private class RelayBootstrapFailure(
        message: String,
    ) : IllegalStateException(message)
}
