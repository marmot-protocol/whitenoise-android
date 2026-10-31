package dev.ipf.whitenoise.android.media.sandbox

import android.content.Context
import android.content.ContextWrapper
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.system.ErrnoException
import android.system.OsConstants
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Controlled idle-source facts exercise the real unavailable/cleanup owner, without parsing or kernel evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class AvStreamRetirementTest {
    @Test
    @Suppress("LongMethod") // The blocked-RPC ordering and exact owner cleanup form one causal fixture.
    fun unavailableEndpointUnbindsAndObservesProcessBeforeBlockedRetirementReturns() {
        val unbound = CountDownLatch(1)
        var unbindCalls = 0
        val context =
            object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
                override fun unbindService(conn: ServiceConnection) {
                    unbindCalls++
                    unbound.countDown()
                }
            }
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val absentObserved = CountDownLatch(1)
        val alive = AtomicBoolean(true)
        val endpoint =
            object : Binder() {
                override fun onTransact(
                    code: Int,
                    data: Parcel,
                    reply: Parcel?,
                    flags: Int,
                ): Boolean {
                    assertEquals(AvStreamProtocol.RETIRE, code)
                    assertEquals(IBinder.FLAG_ONEWAY, flags)
                    entered.countDown()
                    assertTrue(unblock.await(20, TimeUnit.SECONDS))
                    return true
                }
            }

        @Suppress("UNCHECKED_CAST")
        val admissions = field(null, "admissions") as AvStreamAdmissions<IsolatedAvStream>
        val epoch = admissions.epoch()
        assertNull(admissions.current(AvStreamLane.Analysis, epoch))
        val constructor =
            IsolatedAvStream::class.java.declaredConstructors
                .single { it.parameterCount == 6 }
                .apply { isAccessible = true }
        val stream = constructor.newInstance(context, false, true, 1, epoch, AvStreamLane.Analysis) as IsolatedAvStream
        val witness =
            KernelProcessWitness(100, 200) { _, _ ->
                if (!alive.get()) absentObserved.countDown()
                throw ErrnoException("kill", if (alive.get()) OsConstants.EPERM else OsConstants.ESRCH)
            }
        assertTrue(witness.admit(101, 201))
        setField(stream, "processWitness", witness)
        setField(stream, "endpoint", endpoint)
        setField(stream, "bindingAttempted", true)
        setField(stream, "deathRegistered", true)
        val lifetime = field(stream, "lifetime") as AvStreamLifetime
        val released = field(stream, "released") as CountDownLatch
        lifetime.bindCompleted(true)
        admissions.reserve(AvStreamLane.Analysis, epoch) { stream }
        try {
            val unavailable =
                IsolatedAvStream::class.java
                    .getDeclaredMethod(
                        "unavailable",
                    ).apply { isAccessible = true }
            unavailable.invoke(stream)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue("AMS cleanup cannot queue behind the hostile endpoint", unbound.await(2, TimeUnit.SECONDS))
            assertEquals(1, unbindCalls)
            alive.set(false)
            assertTrue(
                "Process observer must progress before retirement RPC returns",
                absentObserved.await(2, TimeUnit.SECONDS),
            )
            assertEquals(1L, released.count)
            assertFalse(lifetime.releasable())
            assertTrue(admissions.current(AvStreamLane.Analysis, epoch) === stream)
            unblock.countDown()
            assertTrue(released.await(2, TimeUnit.SECONDS))
            assertNull(admissions.current(AvStreamLane.Analysis, epoch))
            assertEquals(1, unbindCalls)
        } finally {
            alive.set(false)
            unblock.countDown()
            stream.close()
            assertTrue(released.await(2, TimeUnit.SECONDS))
        }
    }

    private fun field(
        owner: IsolatedAvStream?,
        name: String,
    ): Any? =
        IsolatedAvStream::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .get(owner)

    private fun setField(
        owner: IsolatedAvStream,
        name: String,
        value: Any,
    ) {
        IsolatedAvStream::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .set(owner, value)
    }
}
