package dev.ipf.whitenoise.android.media.sandbox

import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.HandlerThread
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Startup completion must preserve the grace of an input admitted through an earlier connection callback. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class MediaWorkerStartupRetirementTest {
    /** A bind return delayed beyond input admission and retirement cannot escalate that retirement early. */
    @Test
    fun delayedBindReturnPreservesTheServedWorkersRetirementGrace() {
        assertDelayedBindRetirement(expireBeforeReturn = false)
    }

    /** An escalation deferred by an unresolved bind is caught up as soon as its successful return arrives. */
    @Test
    fun bindReturnAfterTheGraceStillStopsTheServedWorker() {
        assertDelayedBindRetirement(expireBeforeReturn = true)
    }

    /** Cancellation cannot strand a dead generation when its bind outcome arrives after the death callback. */
    @Test
    fun interruptedBindReturnDisposesTheAlreadyDeadWorker() {
        val binding = BindingFixture(ApplicationProvider.getApplicationContext(), reply = false)
        val context = DelayedBindReturn(binding, interruptOnReturn = true)
        val lock = Any()
        val pool = MediaWorkerPool(context, "startup_cancel", spares = 0, lock = lock, ended = {})
        val current = AtomicBoolean(true)
        val failure = AtomicReference<Throwable>()
        val interrupted = AtomicBoolean()
        val starter =
            Thread {
                try {
                    pool.claim(Any(), false, WAIT_MS) { current.get() }
                } catch (cause: Throwable) {
                    failure.set(cause)
                } finally {
                    interrupted.set(Thread.currentThread().isInterrupted)
                }
            }
        starter.start()
        try {
            assertTrue(context.connected.await(WAIT_MS, TimeUnit.MILLISECONDS))
            val bound = binding.next()
            val worker = checkNotNull(pool.claim(Any(), false, WAIT_MS) { true })
            synchronized(lock) { worker.served = true }
            bound.remote.die()
            assertEquals("The unresolved bind still holds the dead generation", 1, pool.liveGenerationsForTest())
            current.set(false)
            context.release.countDown()
            starter.join(WAIT_MS)
            assertFalse("The interrupted starter finished", starter.isAlive)
            assertNull("Bind completion must not introduce an interruptible wait", failure.get())
            assertTrue("Bind completion preserves cancellation", interrupted.get())
            assertEquals(0, pool.liveGenerationsForTest())
            assertTrue("The dead worker's binding was released", binding.isUnbound(bound))
        } finally {
            current.set(false)
            context.release.countDown()
            starter.join(WAIT_MS)
            pool.close()
            binding.dieAll()
        }
    }

    /** Drives a connected worker through admission and retirement while its original bind return is held. */
    private fun assertDelayedBindRetirement(expireBeforeReturn: Boolean) {
        val binding = BindingFixture(ApplicationProvider.getApplicationContext(), reply = false)
        val context = DelayedBindReturn(binding)
        val lock = Any()
        val pool = MediaWorkerPool(context, "startup_grace", spares = 0, lock = lock, ended = {})
        val current = AtomicBoolean(true)
        val starter = Thread { pool.claim(Any(), false, WAIT_MS) { current.get() } }
        starter.start()
        try {
            assertTrue(context.connected.await(WAIT_MS, TimeUnit.MILLISECONDS))
            val bound = binding.next()
            val worker = checkNotNull(pool.claim(Any(), false, WAIT_MS) { true })
            // The connection callback admits a request before bindIsolatedService returns on the starter thread.
            synchronized(lock) { worker.served = true }
            pool.retire(worker)
            assertTrue(bound.remote.retired.await(WAIT_MS, TimeUnit.MILLISECONDS))
            if (expireBeforeReturn) {
                advancePastGrace(pool)
                assertFalse("An unresolved bind cannot be unbound yet", binding.isUnbound(bound))
            }
            current.set(false)
            context.release.countDown()
            starter.join(WAIT_MS)
            assertFalse("The original claim finished", starter.isAlive)
            if (!expireBeforeReturn) {
                assertFalse("Bind completion must preserve the cooperative grace", binding.isUnbound(bound))
                advancePastGrace(pool)
            }
            assertTrue("The parent escalates once the grace and bind finish", binding.isUnbound(bound))
        } finally {
            current.set(false)
            context.release.countDown()
            starter.join(WAIT_MS)
            pool.close()
            binding.dieAll()
        }
    }

    /** Advances the retirement clock and drains the callback queue before releasing the pending bind. */
    private fun advancePastGrace(pool: MediaWorkerPool) {
        org.robolectric.shadows.ShadowSystemClock
            .advanceBy(java.time.Duration.ofMillis(MediaWorkerPool.RETIRE_ESCALATION_MS + 1))
        val callbacks = (privateField(pool, "callbackThread") as Lazy<*>).value as HandlerThread
        val drained = CountDownLatch(1)
        Handler(callbacks.looper).post { drained.countDown() }
        assertTrue("The escalation callback ran", drained.await(WAIT_MS, TimeUnit.MILLISECONDS))
    }

    /** Delivers the real fixture callback, then holds the bind return independently of subsequent claims. */
    private class DelayedBindReturn(
        private val binding: BindingFixture,
        private val interruptOnReturn: Boolean = false,
    ) : ContextWrapper(binding) {
        val connected = CountDownLatch(1)
        val release = CountDownLatch(1)

        /** Holds the bind's outcome after its connection callback, independently of the pool's callback queue. */
        override fun bindIsolatedService(
            service: Intent,
            flags: Int,
            instanceName: String,
            executor: Executor,
            conn: ServiceConnection,
        ): Boolean {
            val accepted = binding.bindIsolatedService(service, flags, instanceName, executor, conn)
            connected.countDown()
            check(release.await(WAIT_MS, TimeUnit.MILLISECONDS)) { "Bind return was never released" }
            if (interruptOnReturn) Thread.currentThread().interrupt()
            return accepted
        }
    }

    private companion object {
        const val WAIT_MS = 2_000L
    }
}
