package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationNetworkRecoveryTest {
    @Test
    fun receiverRecoveryOverlapsOutboundWakeAndCatchUpStartsWhenReceiverReady() =
        runTest {
            val wakeStarted = CompletableDeferred<Unit>()
            val receiverStarted = CompletableDeferred<Unit>()
            val releaseWake = CompletableDeferred<Unit>()
            val releaseReceiver = CompletableDeferred<Boolean>()
            var catchUpRan = false

            val result =
                async {
                    runNotificationReconnectOnNetworkRestore(
                        wakeDurableOutbound = {
                            wakeStarted.complete(Unit)
                            releaseWake.await()
                        },
                        ensureNotificationReceiverActive = {
                            receiverStarted.complete(Unit)
                            releaseReceiver.await()
                        },
                        catchUpAccounts = {
                            catchUpRan = true
                            true
                        },
                    )
                }

            wakeStarted.await()
            receiverStarted.await()
            releaseReceiver.complete(true)
            runCurrent()
            assertTrue("receiver readiness should start catch-up without waiting for outbound wake", catchUpRan)
            assertFalse("the attempt must still settle its outbound wake", result.isCompleted)

            releaseWake.complete(Unit)
            assertEquals(NotificationNetworkRecoveryOutcome.Success, result.await())
        }

    @Test
    fun unavailableReceiverLeavesCatchUpPending() =
        runTest {
            var catchUpRan = false

            val result =
                runNotificationReconnectOnNetworkRestore(
                    wakeDurableOutbound = {},
                    ensureNotificationReceiverActive = { false },
                    catchUpAccounts = {
                        catchUpRan = true
                        true
                    },
                )

            assertEquals(NotificationNetworkRecoveryOutcome.ReceiverUnavailable, result)
            assertFalse(catchUpRan)
        }

    @Test
    fun failedCatchUpIsRetryable() =
        runTest {
            val result =
                runNotificationReconnectOnNetworkRestore(
                    wakeDurableOutbound = {},
                    ensureNotificationReceiverActive = { true },
                    catchUpAccounts = { false },
                )

            assertEquals(NotificationNetworkRecoveryOutcome.CatchUpFailed, result)
        }

    @Test
    fun drainRetriesTheSameGenerationUntilCatchUpSucceeds() =
        runTest {
            var requested = 1L
            var completed = 0L
            val attempts = mutableListOf<Pair<Long, Int>>()
            val retries = mutableListOf<Int>()

            drainNotificationNetworkRecovery(
                shouldContinue = { true },
                requestedGeneration = { requested },
                completedGeneration = { completed },
                runAttempt = { generation, attempt ->
                    attempts += generation to attempt
                    if (attempt == 1) {
                        NotificationNetworkRecoveryOutcome.ReceiverUnavailable
                    } else {
                        NotificationNetworkRecoveryOutcome.Success
                    }
                },
                markCompleted = { completed = it },
                awaitRetry = { _, attempt -> retries += attempt },
            )

            assertEquals(listOf(1L to 1, 1L to 2), attempts)
            assertEquals(listOf(1), retries)
            assertEquals(1L, completed)
        }

    @Test
    fun drainCoalescesAnInFlightEdgeToTheNewestGeneration() =
        runTest {
            var requested = 1L
            var completed = 0L
            val attemptedGenerations = mutableListOf<Long>()

            drainNotificationNetworkRecovery(
                shouldContinue = { true },
                requestedGeneration = { requested },
                completedGeneration = { completed },
                runAttempt = { generation, _ ->
                    attemptedGenerations += generation
                    if (generation == 1L) requested = 3L
                    NotificationNetworkRecoveryOutcome.Success
                },
                markCompleted = { completed = it },
                awaitRetry = { _, _ -> error("a successful recovery must not back off") },
            )

            assertEquals(listOf(1L, 3L), attemptedGenerations)
            assertEquals(3L, completed)
        }

    @Test
    fun newerGenerationStartsWithAFreshRetryBudget() =
        runTest {
            var requested = 1L
            var completed = 0L
            val attempts = mutableListOf<Pair<Long, Int>>()

            drainNotificationNetworkRecovery(
                shouldContinue = { true },
                requestedGeneration = { requested },
                completedGeneration = { completed },
                runAttempt = { generation, attempt ->
                    attempts += generation to attempt
                    if (generation == 1L) {
                        NotificationNetworkRecoveryOutcome.ReceiverUnavailable
                    } else {
                        NotificationNetworkRecoveryOutcome.Success
                    }
                },
                markCompleted = { completed = it },
                awaitRetry = { _, _ -> requested = 2L },
            )

            assertEquals(listOf(1L to 1, 2L to 1), attempts)
            assertEquals(2L, completed)
        }

    @Test
    fun drainRetainsTheGenerationWhenConnectivityDropsDuringRetry() =
        runTest {
            var online = true
            var completed = 0L

            drainNotificationNetworkRecovery(
                shouldContinue = { online },
                requestedGeneration = { 7L },
                completedGeneration = { completed },
                runAttempt = { _, _ -> NotificationNetworkRecoveryOutcome.CatchUpFailed },
                markCompleted = { completed = it },
                awaitRetry = { _, _ -> online = false },
            )

            assertEquals(0L, completed)
        }

    @Test
    fun retryDelayIsPromptAndBounded() {
        assertEquals(500L, notificationNetworkRecoveryRetryDelayMillis(1))
        assertEquals(1_000L, notificationNetworkRecoveryRetryDelayMillis(2))
        assertEquals(8_000L, notificationNetworkRecoveryRetryDelayMillis(100))
    }
}
