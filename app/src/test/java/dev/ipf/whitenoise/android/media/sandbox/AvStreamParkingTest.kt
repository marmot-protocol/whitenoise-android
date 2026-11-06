package dev.ipf.whitenoise.android.media.sandbox

import android.content.Context
import android.os.HandlerThread
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicIntegerArray

/** A paused worker may serve only its own next activation and must yield to expiry, waiters and account reset. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class AvStreamParkingTest {
    /** Parked Worker Is Claimed Once By Its Owner. */
    @Test
    fun parkedWorkerIsClaimedOnceByItsOwner() =
        withStream { stream, lifetime ->
            assertFalse("An active stream is not claimable", stream.unpark())
            stream.park(60_000)
            assertTrue(stream.unpark())
            assertFalse("A claimed worker cannot be claimed twice", stream.unpark())
            assertFalse(lifetime.closed)
        }

    /** Idle Expiry Retires A Parked Worker But Never An Active One. */
    @Test
    fun idleExpiryRetiresAParkedWorkerButNeverAnActiveOne() =
        withStream { stream, lifetime ->
            val looper = shadowOf((field(stream, "callbacks") as HandlerThread).looper)
            stream.park(1_000)
            assertTrue(stream.unpark())
            looper.idleFor(Duration.ofMillis(2_000))
            assertFalse("A reclaimed worker keeps running", lifetime.closed)
            stream.park(1_000)
            looper.idleFor(Duration.ofMillis(2_000))
            assertTrue(lifetime.closed)
            assertFalse(stream.unpark())
        }

    /** Parking While An Opener Waits Yields The Lane Immediately. */
    @Test
    fun parkingWhileAnOpenerWaitsYieldsTheLaneImmediately() =
        withStream { stream, lifetime ->
            val waiters = companionField("reserveWaiters") as AtomicIntegerArray
            waiters.incrementAndGet(AvStreamLane.Playback.ordinal)
            try {
                stream.park(60_000)
            } finally {
                waiters.decrementAndGet(AvStreamLane.Playback.ordinal)
            }
            assertTrue(lifetime.closed)
            assertFalse(stream.unpark())
        }

    /** Account Retirement Prevents Reuse Of A Parked Worker. */
    @Test
    fun accountRetirementPreventsReuseOfAParkedWorker() =
        withStream { stream, lifetime ->
            stream.park(60_000)
            IsolatedAvStream.retireActive()
            assertTrue(lifetime.closed)
            assertFalse(stream.unpark())
        }

    /** Builds one never-bound playback stream holding the lane, then waits for its exact release. */
    private fun withStream(block: (IsolatedAvStream, AvStreamLifetime) -> Unit) {
        @Suppress("UNCHECKED_CAST")
        val admissions = companionField("admissions") as AvStreamAdmissions<IsolatedAvStream>
        val epoch = admissions.epoch()
        assertNull(admissions.current(AvStreamLane.Playback, epoch))
        val constructor =
            IsolatedAvStream::class.java.declaredConstructors
                .single { it.parameterCount == 6 }
                .apply { isAccessible = true }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val stream = constructor.newInstance(context, true, true, 1, epoch, AvStreamLane.Playback) as IsolatedAvStream
        val lifetime = field(stream, "lifetime") as AvStreamLifetime
        lifetime.neverBound()
        admissions.reserve(AvStreamLane.Playback, epoch) { stream }
        try {
            block(stream, lifetime)
        } finally {
            // The callback thread runs freely and quits itself once cleanup releases the lane.
            stream.close()
            assertTrue((field(stream, "released") as CountDownLatch).await(2, TimeUnit.SECONDS))
        }
    }

    /** Reads a private owner field so assertions inspect the exact stream rather than a replacement. */
    private fun field(
        owner: IsolatedAvStream,
        name: String,
    ): Any? =
        IsolatedAvStream::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .get(owner)

    /** Reads a private static owner field; Kotlin compiles companion state onto the outer class. */
    private fun companionField(name: String): Any? =
        IsolatedAvStream::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .get(null)
}
