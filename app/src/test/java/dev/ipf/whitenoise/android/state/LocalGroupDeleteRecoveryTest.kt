package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotKitException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** A closed response must be reconciled before any second local wipe. */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalGroupDeleteRecoveryTest {
    @Test
    fun transientClosedTransportRetriesOnlyAfterGroupIsConfirmedPresent() =
        runTest {
            val events = mutableListOf<String>()
            var deletes = 0
            deleteLocalGroupWithRecovery(
                isCurrent = { true },
                delete = {
                    events += "delete"
                    if (++deletes == 1) throw MarmotKitException.TransportClosed()
                },
                isGroupPresent = {
                    events += "present"
                    true
                },
            )
            assertEquals(listOf("delete", "present", "delete"), events)
        }

    @Test
    fun committedDeleteWithLostResponseDoesNotRepeatMutation() =
        runTest {
            var deletes = 0
            deleteLocalGroupWithRecovery(
                isCurrent = { true },
                delete = {
                    deletes += 1
                    throw MarmotKitException.TransportClosed()
                },
                isGroupPresent = { false },
            )
            assertEquals(1, deletes)
        }

    @Test
    fun exhaustedRetryReportsOneFailureWithoutUnboundedDeletes() =
        runTest {
            val failure = MarmotKitException.TransportClosed()
            var deletes = 0
            val result =
                runCatching {
                    deleteLocalGroupWithRecovery(
                        isCurrent = { true },
                        delete = {
                            deletes += 1
                            throw failure
                        },
                        isGroupPresent = { true },
                    )
                }
            assertSame(failure, result.exceptionOrNull())
            assertEquals(IDEMPOTENT_RUNTIME_MUTATION_RETRY_ATTEMPTS, deletes)
        }

    @Test
    fun failedReconciliationNeverAuthorizesAnotherWipe() =
        runTest {
            var deletes = 0
            var reads = 0
            val result =
                runCatching {
                    deleteLocalGroupWithRecovery(
                        isCurrent = { true },
                        delete = {
                            deletes += 1
                            throw MarmotKitException.TransportClosed()
                        },
                        isGroupPresent = {
                            reads += 1
                            throw MarmotKitException.TransportClosed()
                        },
                    )
                }
            assertTrue(result.isFailure)
            assertEquals(1, deletes)
            assertEquals(IDEMPOTENT_RUNTIME_MUTATION_RETRY_ATTEMPTS, reads)
        }

    @Test
    fun accountSwitchDuringBackoffPreventsSecondWipe() =
        runTest {
            var current = true
            var deletes = 0
            val job =
                async {
                    deleteLocalGroupWithRecovery(
                        isCurrent = { current },
                        delete = {
                            deletes += 1
                            throw MarmotKitException.TransportClosed()
                        },
                        isGroupPresent = { true },
                    )
                }
            runCurrent()
            current = false
            advanceTimeBy(IDEMPOTENT_RUNTIME_MUTATION_RETRY_BACKOFF_MS)
            runCurrent()
            assertTrue(job.getCompletionExceptionOrNull() is CancellationException)
            assertEquals(1, deletes)
        }

    @Test
    fun coroutineCancellationDuringBackoffPreventsSecondWipe() =
        runTest {
            var deletes = 0
            val job =
                async {
                    deleteLocalGroupWithRecovery(
                        isCurrent = { true },
                        delete = {
                            deletes += 1
                            throw MarmotKitException.TransportClosed()
                        },
                        isGroupPresent = { true },
                    )
                }
            runCurrent()
            job.cancel()
            advanceTimeBy(IDEMPOTENT_RUNTIME_MUTATION_RETRY_BACKOFF_MS)
            runCurrent()
            assertFalse(job.isActive)
            assertEquals(1, deletes)
        }
}
