package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotKitException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the short retry boundary for idempotent local runtime mutations. */
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
            var attempts = 0
            val failure =
                runCatching {
                    retryIdempotentRuntimeMutation {
                        attempts += 1
                        throw MarmotKitException.StorageBusy("database is locked")
                    }
                }.exceptionOrNull()

            assertTrue(failure is MarmotKitException.StorageBusy)
            assertEquals(IDEMPOTENT_RUNTIME_MUTATION_RETRY_ATTEMPTS, attempts)
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

            runCurrent()
            assertEquals(1, attempts)

            retry.cancelAndJoin()

            assertTrue(retry.isCancelled)
            assertEquals(1, attempts)
        }
}
