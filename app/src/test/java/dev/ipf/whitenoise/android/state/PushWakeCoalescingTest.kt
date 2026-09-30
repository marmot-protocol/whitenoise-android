package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the real process-owned native lane used by service, worker and foreground recovery. */
@OptIn(ExperimentalCoroutinesApi::class)
class PushWakeCoalescingTest {
    private val key = AccountCatchUpKey("test", 1, 1)

    /** One held fetch plus 100 overlapping wakes yields one fresh successor acknowledging the newest wake. */
    @Test
    fun hundredWakesUseOneSuccessorAndAcknowledgeNewestGeneration() =
        runTest {
            val coordinator = AccountCatchUpCoordinator(this)
            val release = CompletableDeferred<Unit>()
            var calls = 0
            var active = 0
            var maxActive = 0
            var generation = 1L
            var acknowledged = 0L
            val first =
                coordinator.launch(key) {
                    calls++
                    active++
                    maxActive = maxOf(maxActive, active)
                    release.await()
                    active--
                    true
                }
            runCurrent()
            val waiters =
                (1..100).map {
                    generation++
                    val observed = generation
                    val after = coordinator.captureStartSequence()
                    async {
                        runCatchUpAfterTrigger(after, { sequence ->
                            coordinator.launchAfter(sequence, key) {
                                calls++
                                active++
                                maxActive = maxOf(maxActive, active)
                                active--
                                true
                            }
                        }, {
                            acknowledged = maxOf(acknowledged, observed)
                            true
                        })
                    }.also { runCurrent() }
                }
            assertEquals(1, calls)
            release.complete(Unit)
            first.await()
            waiters.forEach { assertTrue(it.await().succeeded) }
            assertEquals(2, calls)
            assertEquals(1, maxActive)
            assertEquals(101L, acknowledged)
        }

    /** Cancelling a worker waiter cannot release the native lane while its underlying operation is still held. */
    @Test
    fun cancelledWaiterDoesNotPermitNativeOverlap() =
        runTest {
            val coordinator = AccountCatchUpCoordinator(this)
            val release = CompletableDeferred<Unit>()
            var calls = 0
            val native =
                coordinator.launch(key) {
                    calls++
                    release.await()
                    true
                }
            val waiter = async { native.await() }
            runCurrent()
            waiter.cancelAndJoin()
            val successor =
                coordinator.launchAfter(coordinator.captureStartSequence(), key) {
                    calls++
                    true
                }
            runCurrent()
            assertEquals(1, calls)
            assertFalse(successor.isCompleted)
            release.complete(Unit)
            assertTrue(successor.await().succeeded)
            assertEquals(2, calls)
        }

    /** New account/network owners supersede queued work without starting beside the old native operation. */
    @Test
    fun switchingOwnersKeepsOnlyNewestQueuedIdentity() =
        runTest {
            val coordinator = AccountCatchUpCoordinator(this)
            val release = CompletableDeferred<Unit>()
            val first =
                coordinator.launch(key) {
                    release.await()
                    true
                }
            runCurrent()
            var staleCalls = 0
            val stale =
                coordinator.launch(key.copy(runtimeGeneration = 2)) {
                    staleCalls++
                    true
                }
            val newest = coordinator.launch(key.copy(runtimeGeneration = 3)) { true }
            assertEquals(AccountCatchUpOutcome.Superseded, stale.await().outcome)
            release.complete(Unit)
            first.await()
            assertTrue(newest.await().succeeded)
            assertEquals(0, staleCalls)
        }
}
