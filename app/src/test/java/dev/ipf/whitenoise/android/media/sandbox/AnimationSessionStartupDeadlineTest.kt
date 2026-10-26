package dev.ipf.whitenoise.android.media.sandbox

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Message
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.core.twoFrameGif
import dev.ipf.whitenoise.android.media.AnimationTermination
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Actual ServiceConnection, source staging and Messenger OPEN boundaries share admission's clock. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AnimationSessionStartupDeadlineTest {
    @Test
    fun nullBindingUnbindsExactlyOnceIncludingAfterOwnerCancellation() {
        for (cancelFirst in listOf(false, true)) {
            val admission = AnimationAdmission()
            val fixture = BindingFixture()
            val session = session(fixture, checkNotNull(admission.acquire(true, 8000)))
            val running = open(session)
            try {
                val connection = fixture.next()
                if (cancelFirst) {
                    session.close()
                    assertEquals(0, fixture.unbinds.get())
                    assertNull(admission.acquire(true, 0))
                }
                connection.onNullBinding(fixture.component)
                running.finish()
                connection.onNullBinding(fixture.component)
                assertEquals(1, fixture.unbinds.get())
                assertEquals(0, fixture.remote.opens.get())
                assertEquals(
                    if (cancelFirst) AnimationTermination.OwnerClosed else AnimationTermination.TransportUnavailable,
                    session.termination.value,
                )
                checkNotNull(admission.acquire(true, 0)).release()
            } finally {
                session.close()
                running.cancel()
                fixture.close()
            }
        }
    }

    @Test
    fun nullBindingRetainsFirstReasonBeforePublishingFreeCapacity() =
        runBlocking {
            for (cancelFirst in listOf(false, true)) {
                val admission = AnimationAdmission()
                val fixture = BindingFixture()
                val session = session(fixture, checkNotNull(admission.acquire(true, 8000)))
                val running = open(session)
                val availability = availability(admission)
                val generation = availability.value
                // Observe synchronously inside the real capacity notification,
                // before the null-binding callback continues.
                val reasonAtRelease =
                    async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                        availability.first { it != generation }
                        session.termination.value
                    }
                try {
                    val connection = fixture.next()
                    if (cancelFirst) session.close()
                    connection.onNullBinding(fixture.component)
                    val expected =
                        if (cancelFirst) {
                            AnimationTermination.OwnerClosed
                        } else {
                            AnimationTermination.TransportUnavailable
                        }
                    assertEquals(expected, reasonAtRelease.await())
                    running.finish()
                    assertEquals(expected, session.termination.value)
                    assertEquals(1, fixture.unbinds.get())
                    assertEquals(0, fixture.remote.opens.get())
                    checkNotNull(admission.acquire(true, 0)).release()
                } finally {
                    reasonAtRelease.cancel()
                    session.close()
                    running.cancel()
                    fixture.close()
                }
            }
        }

    @Test
    fun alreadyDeadBinderUnbindsOnceWithoutOpenAndPreservesPriorCancellation() {
        for (cancelFirst in listOf(false, true)) {
            val admission = AnimationAdmission()
            val fixture = BindingFixture(failLink = true)
            val session = session(fixture, checkNotNull(admission.acquire(true, 8000)))
            val running = open(session)
            try {
                val connection = fixture.next()
                if (cancelFirst) session.close()
                connection.onServiceConnected(fixture.component, fixture.remote)
                running.finish()
                connection.onServiceConnected(fixture.component, fixture.remote) // Duplicate death proof is harmless.
                assertEquals(1, fixture.unbinds.get())
                assertEquals(0, fixture.remote.opens.get())
                assertEquals(
                    if (cancelFirst) {
                        AnimationTermination.OwnerClosed
                    } else {
                        AnimationTermination.WorkerLostUnknown
                    },
                    session.termination.value,
                )
                val next = checkNotNull(admission.acquire(true, 0))
                assertNull(admission.acquire(true, 0))
                next.release()
            } finally {
                session.close()
                running.cancel()
                fixture.close()
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun availability(admission: AnimationAdmission): StateFlow<Long> =
        AnimationAdmission::class.java
            .getDeclaredField("availability")
            .apply { isAccessible = true }
            .get(admission) as StateFlow<Long>

    @Test
    fun sevenSecondsAdmissionAndTwoSecondsConnectionCannotSendLateOpen() {
        val now = AtomicLong()
        val admission = AnimationAdmission(now::get)
        val viewer = checkNotNull(admission.acquire(false, 8000))
        val demand = admission.groupDemand()
        val fixture = BindingFixture()
        val owned = AtomicReference<IsolatedAnimationSession?>()
        val running =
            Running {
                admission.decode(true, true, 8000, demand) { lease ->
                    session(fixture, lease).also(owned::set).let { invokeOpen(it) }
                }
            }
        try {
            // Group demand is registered before release; the absolute deadline was fixed at time zero.
            waitForBlocked(running.thread)
            now.set(TimeUnit.SECONDS.toNanos(7))
            viewer.release()
            val connection = fixture.next()
            now.set(TimeUnit.SECONDS.toNanos(9))
            connection.onServiceConnected(fixture.component, fixture.remote)
            running.finish()
            assertEquals(0, fixture.remote.opens.get())
            assertEquals(AnimationTermination.TransportUnavailable, owned.get()!!.termination.value)
            assertNull(admission.acquire(true, 0)) // Expiry/RETIRE/unbind do not prove worker death.
            fixture.remote.die()
            demand.close()
            checkNotNull(admission.acquire(true, 0)).release()
        } finally {
            viewer.release()
            demand.close()
            owned.get()?.close()
            running.cancel()
            fixture.close()
        }
    }

    @Test
    fun expiryDuringStagingNeverSendsOpenOrReleasesTheBoundWorker() {
        val now = AtomicLong()
        val admission = AnimationAdmission(now::get)
        val fixture = BindingFixture()
        val session =
            session(
                fixture,
                checkNotNull(admission.acquire(true, 8000)),
                staged = { now.set(TimeUnit.SECONDS.toNanos(8)) },
            )
        val running = open(session)
        try {
            fixture.next().onServiceConnected(fixture.component, fixture.remote)
            running.finish()
            assertEquals(0, fixture.remote.opens.get())
            assertEquals(AnimationTermination.TransportUnavailable, session.termination.value)
            assertNull(admission.acquire(true, 0))
            fixture.remote.die()
            checkNotNull(admission.acquire(true, 0)).release()
        } finally {
            session.close()
            running.cancel()
            fixture.close()
        }
    }

    @Test
    fun firstFrameWaitReceivesOnlyRemainingBudgetAndCannotRenewEightSeconds() {
        val now = AtomicLong()
        val admission = AnimationAdmission(now::get)
        val fixture = BindingFixture()
        val waits = mutableListOf<Long>()
        val session =
            session(
                fixture,
                checkNotNull(admission.acquire(true, 8000)),
                staged = { now.set(TimeUnit.MILLISECONDS.toNanos(7750)) },
                await = { signal, remaining ->
                    waits += remaining
                    if (waits.size == 1) {
                        signal.await(remaining, TimeUnit.NANOSECONDS)
                    } else {
                        now.set(TimeUnit.SECONDS.toNanos(8))
                        false
                    } // Controlled first-frame timeout.
                },
            )
        val running = open(session)
        try {
            fixture.next().onServiceConnected(fixture.component, fixture.remote)
            running.finish()
            assertEquals(1, fixture.remote.opens.get())
            assertEquals(TimeUnit.MILLISECONDS.toNanos(250), waits.last())
            assertEquals(AnimationTermination.TransportUnavailable, session.termination.value)
            assertNull(admission.acquire(true, 0))
            fixture.remote.die()
            checkNotNull(admission.acquire(true, 0)).release()
        } finally {
            session.close()
            running.cancel()
            fixture.close()
        }
    }

    @Test
    fun cancellationDuringFirstFrameWaitRejectsLateReplyAndRetainsCapacityUntilDeath() {
        val admission = AnimationAdmission()
        val fixture = BindingFixture()
        val session = session(fixture, checkNotNull(admission.acquire(true, 8000)))
        val running = open(session)
        try {
            fixture.next().onServiceConnected(fixture.component, fixture.remote)
            assertTrue(fixture.remote.opened.await(2, TimeUnit.SECONDS))
            running.thread.interrupt()
            running.finish(InterruptedException::class.java)
            val message = checkNotNull(fixture.remote.message)
            assertThrows(IllegalStateException::class.java) {
                writeAnimationFrame(
                    message.replyTo.binder,
                    AnimationFrameInfo(message.arg1, 1, 2, 2, true, false, false, -1),
                )
            }
            assertEquals(AnimationTermination.OwnerClosed, session.termination.value)
            assertNull(admission.acquire(true, 0))
            fixture.remote.die()
            checkNotNull(admission.acquire(true, 0)).release()
        } finally {
            session.close()
            running.cancel()
            fixture.close()
        }
    }

    private fun session(
        context: Context,
        lease: AnimationAdmission.Lease,
        staged: () -> Unit = {},
        await: (CountDownLatch, Long) -> Boolean = { signal, remaining ->
            signal.await(remaining, TimeUnit.NANOSECONDS)
        },
    ): IsolatedAnimationSession =
        IsolatedAnimationSession::class.java
            .getDeclaredConstructor(
                Context::class.java,
                Int::class.javaPrimitiveType,
                AnimationAdmission.Lease::class.java,
                kotlin.jvm.functions.Function0::class.java,
                kotlin.jvm.functions.Function2::class.java,
            ).apply { isAccessible = true }
            .newInstance(context, 2, lease, staged, await) as IsolatedAnimationSession

    private fun open(session: IsolatedAnimationSession) =
        Running {
            try {
                invokeOpen(session)
            } finally {
                session.close()
            }
        }

    private fun invokeOpen(session: IsolatedAnimationSession): IsolatedAnimationDrawable? =
        try {
            IsolatedAnimationSession::class.java
                .getDeclaredMethod("open", ByteArray::class.java)
                .apply { isAccessible = true }
                .invoke(session, twoFrameGif()) as IsolatedAnimationDrawable?
        } catch (wrapped: InvocationTargetException) {
            throw (wrapped.cause ?: wrapped)
        }

    private fun waitForBlocked(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (
            thread.state !in setOf(Thread.State.WAITING, Thread.State.TIMED_WAITING) &&
            System.nanoTime() < deadline
        ) {
            Thread.yield()
        }
        assertTrue(thread.state in setOf(Thread.State.WAITING, Thread.State.TIMED_WAITING))
    }

    private class Running(
        block: () -> Any?,
    ) {
        private val failure = AtomicReference<Throwable?>()
        val thread =
            Thread {
                try {
                    block()
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }.apply { start() }

        fun finish(expected: Class<out Throwable>? = null) {
            thread.join(2000)
            assertFalse("startup must finish at the owned callback", thread.isAlive)
            if (expected == null) assertNull(failure.get()) else assertTrue(expected.isInstance(failure.get()))
        }

        fun cancel() {
            thread.interrupt()
            thread.join(2000)
        }
    }

    private class BindingFixture(
        failLink: Boolean = false,
    ) : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()),
        java.io.Closeable {
        val component = ComponentName(baseContext, MediaAnimationService::class.java)
        val remote = RecordingBinder(failLink)
        val unbinds = AtomicInteger()
        private val bindings = LinkedBlockingQueue<ServiceConnection>()

        override fun getApplicationContext(): Context = this

        override fun bindIsolatedService(
            service: Intent,
            flags: Int,
            instanceName: String,
            executor: java.util.concurrent.Executor,
            conn: ServiceConnection,
        ): Boolean {
            bindings.put(conn)
            return true
        }

        fun next(): ServiceConnection = checkNotNull(bindings.poll(2, TimeUnit.SECONDS))

        override fun unbindService(conn: ServiceConnection) {
            unbinds.incrementAndGet()
        }

        override fun close() {
            remote.die()
            remote.closeReceivedCapabilities()
        }
    }

    private class RecordingBinder(
        private val failLink: Boolean = false,
    ) : Binder() {
        val opens = AtomicInteger()
        val opened = CountDownLatch(1)
        var message: Message? = null
        private val recipients = mutableListOf<IBinder.DeathRecipient>()

        override fun linkToDeath(
            recipient: IBinder.DeathRecipient,
            flags: Int,
        ) {
            if (failLink) throw android.os.RemoteException("Binder already dead")
            synchronized(recipients) { recipients += recipient }
        }

        fun die() = synchronized(recipients) { recipients.toList() }.forEach { it.binderDied() }

        override fun onTransact(
            code: Int,
            data: Parcel,
            reply: Parcel?,
            flags: Int,
        ): Boolean {
            if (code != IBinder.FIRST_CALL_TRANSACTION) return false
            data.enforceInterface("android.os.IMessenger")
            if (data.readInt() != 0) {
                val received = Message.CREATOR.createFromParcel(data)
                if (received.what == AnimationFrameProtocol.OPEN) {
                    message = received
                    opens.incrementAndGet()
                    opened.countDown()
                }
            }
            return true
        }

        @Suppress("DEPRECATION")
        fun closeReceivedCapabilities() {
            message?.data?.getParcelable<ParcelFileDescriptor>("input")?.close()
            message?.data?.getParcelable<SharedMemory>("output")?.close()
            message = null
        }
    }
}
