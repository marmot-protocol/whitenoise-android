package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class UnreadRefreshSchedulerTest {
    /**
     * Records the accounts the scheduler refreshed and lets a test block until a
     * given number of refreshes have run. Using a latch keyed on observed work
     * (rather than counting internal drain waves) keeps the tests robust against
     * the inherent timing of the empty trailing wave that returns the drain to
     * idle.
     */
    private class RefreshRecorder {
        val refreshes = ConcurrentLinkedQueue<String>()
        private val total = AtomicInteger(0)
        private val latches = ConcurrentLinkedQueue<Pair<Int, CountDownLatch>>()

        val refresh: suspend (String) -> Unit = { ref ->
            refreshes += ref
            val now = total.incrementAndGet()
            latches.forEach { (target, latch) -> if (now >= target) latch.countDown() }
        }

        /** Blocks until at least [count] refreshes have been recorded. */
        fun awaitRefreshes(count: Int) {
            val latch = CountDownLatch(1)
            latches += count to latch
            if (total.get() >= count) latch.countDown()
            assertTrue(
                "expected at least $count refreshes, saw ${refreshes.toList()}",
                latch.await(2, TimeUnit.SECONDS),
            )
        }
    }

    /**
     * Holds the first drain wave open until [openFirstWindow] is called so a test
     * can pile a whole burst into one window; every later wave proceeds without
     * delay. Replaces real time so tests stay deterministic and fast.
     */
    private class FirstWindowGate {
        private val firstWindow = CompletableDeferred<Unit>()
        private val firstSeen = CompletableDeferred<Unit>()

        val sleep: suspend (Long) -> Unit = {
            if (!firstSeen.isCompleted) firstSeen.complete(Unit)
            if (!firstWindow.isCompleted) firstWindow.await()
        }

        suspend fun awaitFirstWindow() = withTimeout(2_000) { firstSeen.await() }

        fun openFirstWindow() {
            if (!firstWindow.isCompleted) firstWindow.complete(Unit)
        }
    }

    @Test
    fun coalescesBurstForSameAccountIntoSingleRefresh() {
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val gate = FirstWindowGate()
            val recorder = RefreshRecorder()
            val scheduler =
                UnreadRefreshScheduler(scope = scope, sleep = gate.sleep, refresh = recorder.refresh)

            scheduler.schedule("alice")
            gate.awaitFirstWindow()
            repeat(20) { scheduler.schedule("alice") }
            gate.openFirstWindow()

            recorder.awaitRefreshes(1)
            // Give any erroneous extra wave a chance to double-refresh before we
            // assert the burst collapsed to one.
            Thread.sleep(50)
            assertEquals(listOf("alice"), recorder.refreshes.toList())
            scope.cancel()
        }
    }

    @Test
    fun refreshesEachDistinctAccountOncePerWindow() {
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val gate = FirstWindowGate()
            val recorder = RefreshRecorder()
            val scheduler =
                UnreadRefreshScheduler(scope = scope, sleep = gate.sleep, refresh = recorder.refresh)

            scheduler.schedule("alice")
            gate.awaitFirstWindow()
            scheduler.schedule("bob")
            scheduler.schedule("alice")
            scheduler.schedule("carol")
            gate.openFirstWindow()

            recorder.awaitRefreshes(3)
            Thread.sleep(50)
            assertEquals(setOf("alice", "bob", "carol"), recorder.refreshes.toSet())
            assertEquals(3, recorder.refreshes.size)
            scope.cancel()
        }
    }

    @Test
    fun accountsScheduledDuringDrainAreNotLost() {
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val gate = FirstWindowGate()
            val refreshes = ConcurrentLinkedQueue<String>()
            val started = AtomicInteger(0)
            val firstRefreshStarted = CompletableDeferred<Unit>()
            val releaseFirstRefresh = CompletableDeferred<Unit>()
            val bobRefreshed = CountDownLatch(1)
            val scheduler =
                UnreadRefreshScheduler(scope = scope, sleep = gate.sleep) { ref ->
                    if (started.incrementAndGet() == 1) {
                        firstRefreshStarted.complete(Unit)
                        withTimeout(2_000) { releaseFirstRefresh.await() }
                    }
                    refreshes += ref
                    if (ref == "bob") bobRefreshed.countDown()
                }

            scheduler.schedule("alice")
            gate.awaitFirstWindow()
            gate.openFirstWindow()

            // While refresh("alice") is blocked, schedule a new account. A
            // parallel drain must not start, and a follow-on wave must still
            // pick "bob" up.
            withTimeout(2_000) { firstRefreshStarted.await() }
            scheduler.schedule("bob")
            releaseFirstRefresh.complete(Unit)

            assertTrue("bob was lost", bobRefreshed.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("alice", "bob"), refreshes.toList())
            scope.cancel()
        }
    }

    @Test
    fun blankAccountRefIsIgnored() {
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val gate = FirstWindowGate()
            val recorder = RefreshRecorder()
            val scheduler =
                UnreadRefreshScheduler(scope = scope, sleep = gate.sleep, refresh = recorder.refresh)

            scheduler.schedule("   ")
            scheduler.schedule("")
            scheduler.schedule("alice")
            gate.awaitFirstWindow()
            gate.openFirstWindow()

            recorder.awaitRefreshes(1)
            Thread.sleep(50)
            assertEquals(listOf("alice"), recorder.refreshes.toList())
            scope.cancel()
        }
    }

    @Test
    fun newBurstAfterIdleStartsAnotherDrain() {
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val recorder = RefreshRecorder()
            // No held window: every drain wave proceeds immediately, so the
            // scheduler returns to idle between bursts on its own.
            val scheduler =
                UnreadRefreshScheduler(
                    scope = scope,
                    drainWindowMillis = 0L,
                    sleep = { /* no delay */ },
                    refresh = recorder.refresh,
                )

            scheduler.schedule("alice")
            recorder.awaitRefreshes(1)

            // A later notification, after the first drain has gone idle, must
            // start a brand-new drain.
            scheduler.schedule("bob")
            recorder.awaitRefreshes(2)

            assertTrue(recorder.refreshes.contains("alice"))
            assertTrue(recorder.refreshes.contains("bob"))
            scope.cancel()
        }
    }
}
