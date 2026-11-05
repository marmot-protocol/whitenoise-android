package dev.ipf.whitenoise.android.media.sandbox

import android.os.HandlerThread
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Binding completion must preserve an existing retirement's reason and original deadline. */
@RunWith(RobolectricTestRunner::class)
@Config(
    application = android.app.Application::class,
    instrumentedPackages = ["dev.ipf.whitenoise.android.media.sandbox.MediaWorkerPool"],
)
@LooperMode(LooperMode.Mode.PAUSED)
class MediaWorkerRetirementBindingTest {
    /** A normal retirement begun after connection publication keeps its original cooperative grace. */
    @Test
    fun normalRetirementBeforeBindReturnsDoesNotEscalateImmediately() {
        PendingBind().use { pending ->
            pending.pauseCallbacks()
            pending.pool.retire(pending.worker)
            assertTrue(
                pending.bound.remote.retired
                    .await(2, TimeUnit.SECONDS),
            )
            val retiredAt = pending.worker.retiredAtNanos
            assertEquals(ShadowSystemClock.nanoTime(), retiredAt)
            pending.advanceClock(100)
            pending.advanceClock(MediaWorkerPool.RETIRE_ESCALATION_MS - 101)
            assertFalse(pending.worker.escalationRequested)
            pending.finish()
            pending.idleCallbacks()
            assertEquals(retiredAt, pending.worker.retiredAtNanos)
            assertEquals(TimeUnit.MILLISECONDS.toNanos(249), ShadowSystemClock.nanoTime() - retiredAt)
            assertFalse(
                "The original grace still has one millisecond remaining",
                pending.context.isUnbound(pending.bound),
            )
            pending.advanceClock(1)
            pending.idleCallbacks()
            assertEquals(retiredAt, pending.worker.retiredAtNanos)
            assertEquals(TimeUnit.MILLISECONDS.toNanos(250), ShadowSystemClock.nanoTime() - retiredAt)
            assertTrue("Stop at the original deadline, without a new grace", pending.context.isUnbound(pending.bound))
        }
    }

    /** A served generation detached before its caller sends RETIRE must start one cooperative grace. */
    @Test
    fun servedRetirementMarkedBeforeBindReturnsStartsOnlyOneGrace() {
        PendingBind().use { pending ->
            pending.pauseCallbacks()
            val retiring =
                synchronized(pending.lock) {
                    pending.worker.served = true
                    pending.pool.detachAll()
                }
            assertEquals(listOf(pending.worker), retiring)
            assertFalse(pending.worker.retireSent)
            pending.finish()
            assertTrue(pending.bound.remote.retired.await(2, TimeUnit.SECONDS))
            assertFalse(pending.context.isUnbound(pending.bound))
            val retiredAt = pending.worker.retiredAtNanos
            pending.advanceClock(MediaWorkerPool.RETIRE_ESCALATION_MS - 1)
            retiring.forEach { pending.pool.retire(it) }
            pending.idleCallbacks()
            assertEquals(retiredAt, pending.worker.retiredAtNanos)
            assertFalse(pending.context.isUnbound(pending.bound))
            pending.advanceClock(1)
            pending.idleCallbacks()
            assertEquals(retiredAt, pending.worker.retiredAtNanos)
            assertTrue(pending.context.isUnbound(pending.bound))
        }
    }

    /** An explicit close cannot lose its stop request while bindIsolatedService is still returning. */
    @Test
    fun closeBeforeBindReturnsResumesTheForcedStop() {
        PendingBind().use { pending ->
            pending.pool.close()
            assertFalse(pending.context.isUnbound(pending.bound))
            pending.finish()
            assertTrue(pending.context.isUnbound(pending.bound))
        }
    }

    /** A served worker whose bind has completed still stops immediately on terminal close. */
    @Test
    fun closeAfterBindReturnsStillForcesTheServedWorkerToStop() {
        PendingBind().use { pending ->
            pending.finish()
            synchronized(pending.lock) { pending.worker.served = true }
            pending.pool.close()
            assertTrue(pending.context.isUnbound(pending.bound))
        }
    }

    /** A platform disconnection has the same durable forced-stop semantics as an explicit close. */
    @Test
    fun disconnectBeforeBindReturnsResumesTheForcedStop() {
        PendingBind().use { pending ->
            pending.worker.connection.onServiceDisconnected(pending.context.component)
            assertFalse(pending.context.isUnbound(pending.bound))
            pending.finish()
            assertTrue(pending.context.isUnbound(pending.bound))
        }
    }

    /** Expiry while binding is incomplete cannot restart the grace or strand the abandoned generation. */
    @Test
    fun expiredRetirementBeforeBindReturnsStopsWithoutANewGrace() {
        PendingBind().use { pending ->
            pending.pauseCallbacks()
            pending.pool.retire(pending.worker)
            val retiredAt = pending.worker.retiredAtNanos
            assertEquals(ShadowSystemClock.nanoTime(), retiredAt)
            pending.advanceClock(MediaWorkerPool.RETIRE_ESCALATION_MS)
            // Neither the delayed task nor awaitDeath has pre-selected forced escalation.
            assertFalse(pending.worker.escalationRequested)
            assertFalse(pending.context.isUnbound(pending.bound))
            pending.finish()
            assertEquals(retiredAt, pending.worker.retiredAtNanos)
            assertTrue(
                "Binding completion must use the already expired original grace",
                pending.context.isUnbound(pending.bound),
            )
        }
    }

    /** A throwing bind retains unknown lifetime accounting but must resume an already requested stop. */
    @Test
    fun throwingBindAfterConnectionResumesTheForcedStop() {
        PendingBind(throwAfterBind = true).use { pending ->
            pending.pool.close()
            pending.finish()
            assertTrue(pending.context.isUnbound(pending.bound))
            assertTrue(pending.worker.dead.count != 0L)
        }
    }

    /** Real pool startup held after its synchronous connection callback, before its binding outcome returns. */
    private class PendingBind(
        throwAfterBind: Boolean = false,
    ) : AutoCloseable {
        private val release = CountDownLatch(1)
        private val failure = AtomicReference<Throwable?>()
        val context =
            BindingFixture(
                ApplicationProvider.getApplicationContext(),
                reply = false,
                throwAfterBind = throwAfterBind,
                beforeBindReturn = { check(release.await(2, TimeUnit.SECONDS)) },
            )
        val lock = Any()
        val pool = MediaWorkerPool(context, "retirement_binding", spares = 0, lock = lock, ended = {})
        val worker = invoke("reserve") as MediaWorker
        private val starter =
            Thread {
                runCatching { invoke("start", worker) }.onFailure { failure.set(it) }
            }.apply { start() }
        val bound = context.next()

        /** Releases binding completion and requires the actual startup invocation to finish cleanly. */
        fun finish() {
            release.countDown()
            starter.join(2_000)
            assertFalse(starter.isAlive)
            assertNull(failure.get()?.stackTraceToString(), failure.get())
        }

        /** Pauses only this pool's callback queue so tests choose exactly when its delayed task runs. */
        fun pauseCallbacks() = shadowOf(callbacks().looper).pause()

        /** Runs due callbacks without advancing either production clock or the retirement origin. */
        fun idleCallbacks() = shadowOf(callbacks().looper).idle()

        /** Advances the clock intercepted in the pool; the RunWith test itself is not instrumented. */
        fun advanceClock(milliseconds: Long) {
            val before = ShadowSystemClock.nanoTime()
            ShadowSystemClock.advanceBy(Duration.ofMillis(milliseconds))
            assertEquals(before + TimeUnit.MILLISECONDS.toNanos(milliseconds), ShadowSystemClock.nanoTime())
        }

        /** Reads the actual callback thread from the existing lazy pool owner. */
        private fun callbacks(): HandlerThread {
            val callbackOwner = privateField(pool, "callbackThread") as Lazy<*>
            return callbackOwner.value as HandlerThread
        }

        /** Invokes the existing pool boundary without adding production test hooks. */
        private fun invoke(
            name: String,
            vararg arguments: Any,
        ): Any? =
            MediaWorkerPool::class.java.declaredMethods
                .single { it.name == name }
                .apply { isAccessible = true }
                .invoke(pool, *arguments)

        /** Releases only this fixture's fake endpoints and pool on every assertion path. */
        override fun close() {
            release.countDown()
            context.dieAll()
            pool.close()
            starter.join(2_000)
        }
    }
}
