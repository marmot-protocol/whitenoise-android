package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CoroutineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Deterministic concurrency coverage for the notification bootstrap fixture's one-shot dispatch gate. */
class PostStartNotificationDispatchGateTest {
    /** Holds the first post-start dispatch, then lets it and every later dispatch run after release. */
    @Test
    fun firstPostStartDispatchWaitsUntilReleaseAndThenTheGateStaysOpen() {
        val executions = AtomicInteger(0)
        val gate = gate(runtimeStarted = true)

        gate.dispatch(EmptyCoroutineContext, Runnable { executions.incrementAndGet() })
        assertEquals(0, executions.get())

        gate.release()
        assertEquals(1, executions.get())

        gate.dispatch(EmptyCoroutineContext, Runnable { executions.incrementAndGet() })
        assertEquals(2, executions.get())
    }

    /** Keeps release effective when it happens before the first qualifying post-start dispatch. */
    @Test
    fun releaseBeforeFirstPostStartDispatchLeavesTheGateOpen() {
        val executions = AtomicInteger(0)
        val gate = gate(runtimeStarted = true)

        gate.release()
        gate.dispatch(EmptyCoroutineContext, Runnable { executions.incrementAndGet() })

        assertEquals(1, executions.get())
    }

    /** Atomically drains a held dispatch when release races its pending-publication boundary. */
    @Test
    fun releaseDuringPendingPublicationCannotStrandTheDispatch() {
        val publicationEntered = CountDownLatch(1)
        val releaseStarted = CountDownLatch(1)
        val hookCompleted = AtomicBoolean(false)
        val executions = AtomicInteger(0)
        val threadFailure = AtomicReference<Throwable?>()
        val gate =
            gate(
                runtimeStarted = true,
                beforePendingPublication = {
                    publicationEntered.countDown()
                    hookCompleted.set(releaseStarted.await(5L, TimeUnit.SECONDS))
                },
            )
        val dispatchThread =
            guardedThread(threadFailure) {
                gate.dispatch(EmptyCoroutineContext, Runnable { executions.incrementAndGet() })
            }
        val releaseThread =
            guardedThread(threadFailure) {
                releaseStarted.countDown()
                gate.release()
            }

        try {
            dispatchThread.start()
            assertTrue(publicationEntered.await(5L, TimeUnit.SECONDS))
            releaseThread.start()
            dispatchThread.join(5_000L)
            releaseThread.join(5_000L)
        } finally {
            releaseStarted.countDown()
            gate.release()
            dispatchThread.join(5_000L)
            if (releaseThread.state != Thread.State.NEW) releaseThread.join(5_000L)
        }

        assertFalse(dispatchThread.isAlive)
        assertFalse(releaseThread.isAlive)
        assertTrue(hookCompleted.get())
        assertNull(threadFailure.get())
        assertEquals(1, executions.get())
    }

    /** Builds a gate whose delegate runs synchronously so dispatch ownership is directly observable. */
    private fun gate(
        runtimeStarted: Boolean,
        beforePendingPublication: (() -> Unit)? = null,
    ): NotificationBootstrapTestFixture.PostStartNotificationDispatchGate =
        NotificationBootstrapTestFixture.PostStartNotificationDispatchGate(
            runtimeStarted = AtomicBoolean(runtimeStarted),
            delegate = ImmediateDispatcher,
            beforePendingPublication = beforePendingPublication,
        )

    /** Captures a worker failure so it remains visible to the owning JUnit thread. */
    private fun guardedThread(
        failure: AtomicReference<Throwable?>,
        block: () -> Unit,
    ): Thread =
        thread(start = false) {
            runCatching(block).exceptionOrNull()?.let { throwable ->
                failure.compareAndSet(null, throwable)
            }
        }

    /** Executes a dispatched continuation immediately for deterministic gate assertions. */
    private object ImmediateDispatcher : CoroutineDispatcher() {
        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) = block.run()
    }
}
