package dev.ipf.whitenoise.android.ui.conversation.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SingleFlightTest {
    private companion object {
        private const val TEST_HANG_GUARD_MS = 30_000L
    }

    @Test
    fun activeFlightWinsBeforeWaiterChecksItsFastPath() {
        runBlocking {
            withTimeout(TEST_HANG_GUARD_MS) {
                val singleFlight = SingleFlight<String, String>()
                val ownerEntered = CompletableDeferred<Unit>()
                val releaseOwner = CompletableDeferred<Unit>()
                var cachedValue = "missing"
                var waiterBlockRan = false

                val owner =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        singleFlight.run("attachment") {
                            if (cachedValue != "missing") return@run cachedValue
                            ownerEntered.complete(Unit)
                            releaseOwner.await()
                            "complete".also { cachedValue = it }
                        }
                    }
                val children = mutableListOf(owner)
                try {
                    ownerEntered.await()
                    cachedValue = "partial"

                    val waiter =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            singleFlight.run("attachment") {
                                waiterBlockRan = true
                                cachedValue
                            }
                        }
                    children += waiter

                    assertFalse("waiter must await the active owner", waiter.isCompleted)
                    assertFalse("waiter must not inspect a partial fast-path value", waiterBlockRan)

                    releaseOwner.complete(Unit)

                    assertEquals("complete", owner.await())
                    assertEquals("complete", waiter.await())
                    assertFalse(waiterBlockRan)
                } finally {
                    releaseOwner.complete(Unit)
                    children.forEach { it.cancel() }
                }
            }
        }
    }

    @Test
    fun differentKeysRunIndependently() {
        runBlocking {
            withTimeout(TEST_HANG_GUARD_MS) {
                val singleFlight = SingleFlight<String, String>()
                val firstEntered = CompletableDeferred<Unit>()
                val releaseFirst = CompletableDeferred<Unit>()

                val first =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        singleFlight.run("first") {
                            firstEntered.complete(Unit)
                            releaseFirst.await()
                            "first-result"
                        }
                    }
                val children = mutableListOf(first)
                try {
                    firstEntered.await()

                    val second =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            singleFlight.run("second") { "second-result" }
                        }
                    children += second

                    assertEquals("second-result", second.await())
                    assertFalse(first.isCompleted)
                    releaseFirst.complete(Unit)
                    assertEquals("first-result", first.await())
                } finally {
                    releaseFirst.complete(Unit)
                    children.forEach { it.cancel() }
                }
            }
        }
    }

    @Test
    fun failureIsSharedAndNextCallerCanRetry() {
        runBlocking {
            withTimeout(TEST_HANG_GUARD_MS) {
                supervisorScope {
                    val singleFlight = SingleFlight<String, String>()
                    val ownerEntered = CompletableDeferred<Unit>()
                    val releaseOwner = CompletableDeferred<Unit>()
                    val failure = IllegalStateException("load failed")
                    val calls = AtomicInteger(0)

                    val owner =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            singleFlight.run("attachment") {
                                calls.incrementAndGet()
                                ownerEntered.complete(Unit)
                                releaseOwner.await()
                                throw failure
                            }
                        }
                    ownerEntered.await()
                    val waiter =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            singleFlight.run("attachment") {
                                calls.incrementAndGet()
                                "unexpected"
                            }
                        }

                    releaseOwner.complete(Unit)

                    val ownerFailure = runCatching { owner.await() }.exceptionOrNull()
                    val waiterFailure = runCatching { waiter.await() }.exceptionOrNull()
                    assertTrue(ownerFailure is IllegalStateException)
                    assertTrue(waiterFailure is IllegalStateException)
                    assertEquals(failure.message, ownerFailure?.message)
                    assertEquals(failure.message, waiterFailure?.message)
                    assertEquals(1, calls.get())

                    assertEquals(
                        "recovered",
                        singleFlight.run("attachment") {
                            calls.incrementAndGet()
                            "recovered"
                        },
                    )
                    assertEquals(2, calls.get())
                }
            }
        }
    }

    @Test
    fun cancellingOwnerDoesNotAbortWorkSharedWithWaiters() {
        runBlocking {
            withTimeout(TEST_HANG_GUARD_MS) {
                val singleFlight = SingleFlight<String, String>()
                val ownerEntered = CompletableDeferred<Unit>()
                val releaseOwner = CompletableDeferred<Unit>()

                val owner =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        singleFlight.run("attachment") {
                            ownerEntered.complete(Unit)
                            releaseOwner.await()
                            "complete"
                        }
                    }
                ownerEntered.await()
                val waiter =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        singleFlight.run("attachment") { error("waiter must not become the owner") }
                    }

                owner.cancel(CancellationException("first caller left the UI"))
                releaseOwner.complete(Unit)

                assertEquals("complete", waiter.await())
                owner.join()
                assertTrue(owner.isCancelled)
            }
        }
    }
}
