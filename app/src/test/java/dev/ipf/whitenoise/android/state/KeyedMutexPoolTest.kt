package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A holder can step out of a keyed lock while it waits on something that commits nothing, then take it back. */
@OptIn(ExperimentalCoroutinesApi::class)
class KeyedMutexPoolTest {
    private val pool = KeyedMutexPool()

    /** While the holder waits outside the lock another caller gets in, and once back the holder excludes newcomers. */
    @Test
    fun releasedWhileLetsAnotherCallerInAndHoldsTheKeyAgain() =
        runTest {
            val answer = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val order = mutableListOf<String>()
            val holder =
                async {
                    pool.withLock(KEY) {
                        pool.releasedWhile(KEY) { answer.await() }
                        order += "holder back"
                        finish.await()
                        order += "holder done"
                    }
                }
            runCurrent()

            val other = async { pool.withLock(KEY) { order += "other" } }
            runCurrent()
            assertTrue("the waiting holder kept the lock", other.isCompleted)

            answer.complete(Unit)
            runCurrent()
            val late = async { pool.withLock(KEY) { order += "late" } }
            runCurrent()
            assertFalse("a newcomer got in after the holder took the lock back", late.isCompleted)

            finish.complete(Unit)
            holder.await()
            late.await()
            assertEquals(listOf("other", "holder back", "holder done", "late"), order)
        }

    /** Someone who does not hold the key cannot release another caller's lock. */
    @Test
    fun releasedWhileLeavesAKeyTheCallerDoesNotHold() =
        runTest {
            val finish = CompletableDeferred<Unit>()
            val holder = async { pool.withLock(KEY) { finish.await() } }
            runCurrent()

            assertEquals("ran", pool.releasedWhile(KEY) { "ran" })
            val other = async { pool.withLock(KEY) { "other" } }
            runCurrent()
            assertFalse("the lock was released by a caller that never held it", other.isCompleted)

            finish.complete(Unit)
            holder.await()
            assertEquals("other", other.await())
        }

    /** Inside the released block the key no longer counts as held, so a nested release leaves another holder alone. */
    @Test
    fun aNestedReleaseCannotFreeAnotherCallersLock() =
        runTest {
            val answer = CompletableDeferred<Unit>()
            val otherHolds = CompletableDeferred<Unit>()
            val finishOther = CompletableDeferred<Unit>()
            val holder =
                async {
                    pool.withLock(KEY) {
                        pool.releasedWhile(KEY) {
                            otherHolds.await()
                            pool.releasedWhile(KEY) { "nested" }
                            answer.await()
                        }
                    }
                }
            runCurrent()
            val other =
                async {
                    pool.withLock(KEY) {
                        otherHolds.complete(Unit)
                        finishOther.await()
                    }
                }
            runCurrent()

            val third = async { pool.withLock(KEY) { "third" } }
            runCurrent()
            assertFalse("a nested release freed the lock another caller holds", third.isCompleted)

            finishOther.complete(Unit)
            answer.complete(Unit)
            other.await()
            holder.await()
            assertEquals("third", third.await())
        }

    /** A holder cancelled while outside the lock takes it back before leaving, so the lock ends free, not broken. */
    @Test
    fun cancellationOutsideTheLockStillLeavesItUsable() =
        runTest {
            val holder = async { pool.withLock(KEY) { pool.releasedWhile(KEY) { awaitCancellation() } } }
            runCurrent()

            holder.cancel()
            val failure = runCatching { holder.await() }.exceptionOrNull()

            assertTrue("$failure", failure is CancellationException)
            assertEquals("free", pool.withLock(KEY) { "free" })
        }

    /** A commit that finishes after its caller was cancelled still hands its result back, as a plain lock does. */
    @Test
    fun aBlockFinishingAfterItsCallerWasCancelledStillReturnsItsValue() =
        runTest {
            val release = CompletableDeferred<Unit>()
            var observed: String? = null
            val caller =
                launch {
                    val committed =
                        pool.withLock(KEY) {
                            withContext(NonCancellable) {
                                release.await()
                                "committed"
                            }
                        }
                    observed = committed
                }
            runCurrent()
            caller.cancel()
            release.complete(Unit)
            caller.join()

            assertEquals("the lock turned a finished commit into a cancellation", "committed", observed)
        }

    private companion object {
        const val KEY = "account|group"
    }
}
