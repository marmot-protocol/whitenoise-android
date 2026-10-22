package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bursts share one read worker per conversation and retain a follow-up after the latest request. */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationReadReconciliationCoordinatorTest {
    /** An unfinished read is never cancelled or multiplied, while another account or chat can proceed. */
    @Test
    fun aBurstDuringAReadConflatesWithoutBlockingOtherConversations() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val calls = mutableListOf<Pair<String, String>>()
            var firstReadFinished = false
            val coordinator =
                NotificationReadReconciliationCoordinator(this, 1_000) { account, group ->
                    calls += account to group
                    if (calls.size == 1) {
                        try {
                            release.await()
                        } finally {
                            firstReadFinished = true
                        }
                    }
                }
            coordinator.request("account", "CHAT")
            runCurrent()
            repeat(100) { coordinator.request("account", "chat") }
            runCurrent()
            assertEquals(listOf("account" to "chat"), calls)
            assertFalse("a new request must not cancel the running read", firstReadFinished)
            coordinator.request("other", "chat")
            coordinator.request("account", "other")
            runCurrent()
            assertTrue("other" to "chat" in calls)
            assertTrue("account" to "other" in calls)
            release.complete(Unit)
            advanceUntilIdle()
            assertEquals(
                "initial read, one dirty pass and the latest follow-up",
                3,
                calls.count { it == ("account" to "chat") },
            )
            assertEquals(2, calls.count { it == ("other" to "chat") })
            assertEquals(2, calls.count { it == ("account" to "other") })
        }

    /** A new event replaces the pending follow-up deadline, and an idle key can be scheduled again. */
    @Test
    fun theLatestRequestKeepsItsOwnFollowUpAndRetiresAfterward() =
        runTest {
            val times = mutableListOf<Long>()
            val coordinator =
                NotificationReadReconciliationCoordinator(this, 1_000) { _, _ -> times += testScheduler.currentTime }
            coordinator.request("account", "chat")
            runCurrent()
            advanceTimeBy(500)
            coordinator.request("account", "chat")
            runCurrent()
            advanceTimeBy(500)
            runCurrent()
            assertEquals(listOf(0L, 500L), times)
            advanceTimeBy(500)
            runCurrent()
            assertEquals(listOf(0L, 500L, 1_500L), times)
            coordinator.request("account", "chat")
            advanceUntilIdle()
            assertEquals(listOf(0L, 500L, 1_500L, 1_500L, 2_500L), times)
        }

    /** Every worker belongs to the receipt-loop scope, including one suspended in its current-state read. */
    @Test
    fun cancellingTheReceiptScopeRetiresItsWorkers() =
        runTest {
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val release = CompletableDeferred<Unit>()
            val coordinator = NotificationReadReconciliationCoordinator(scope, 1_000) { _, _ -> release.await() }
            try {
                coordinator.request("account", "first")
                coordinator.request("account", "second")
                runCurrent()
                val workers = scope.coroutineContext.job.children.toList()
                assertEquals(2, workers.size)
                scope.cancel()
                runCurrent()
                assertTrue(workers.all { it.isCompleted && it.isCancelled })
                assertTrue(scope.coroutineContext.job.children.none())
                coordinator.request("account", "first")
                runCurrent()
                assertTrue("a stopped listener cannot leave a live worker", scope.coroutineContext.job.children.none())
            } finally {
                scope.cancel()
            }
        }
}
