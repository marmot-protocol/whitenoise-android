package dev.ipf.whitenoise.android.media.sandbox

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Parcel
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.core.twoFrameGif
import dev.ipf.whitenoise.android.media.AnimationStartupException
import dev.ipf.whitenoise.android.media.AnimationTermination
import kotlinx.coroutines.async
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

/** Exercises actual session startup/status/termination rather than a parallel outcome model. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AnimationSessionTerminationTest {
    @Test
    fun bindRefusalIsTypedAndItsProvedNoBindLeaseCanBeReused() {
        val context =
            object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
                override fun getApplicationContext(): Context = this

                override fun bindIsolatedService(
                    intent: Intent,
                    flags: Int,
                    instanceName: String,
                    executor: Executor,
                    connection: ServiceConnection,
                ): Boolean = false
            }
        repeat(2) {
            val failure = AtomicReference<Throwable?>()
            val worker =
                Thread {
                    try {
                        IsolatedAnimationSession.create(context, twoFrameGif(), 2, true, true)
                    } catch (thrown: Throwable) {
                        failure.set(thrown)
                    }
                }
            worker.start()
            worker.join(2000)
            assertFalse(worker.isAlive)
            assertEquals(
                AnimationTermination.TransportUnavailable,
                (failure.get() as AnimationStartupException).reason,
            )
        }
    }

    @Test
    fun authenticatedCodecStatusIsTerminalButCannotReleaseAWorker() {
        val admission = AnimationAdmission()
        val lease = checkNotNull(admission.acquire(true, 100))
        val session = session(lease)
        val uid = Process.myUid() + 1000
        try {
            set(session, "workerUid", uid)
            val id = field(session, "id") as Int
            assertFalse(status(session, id + 1, uid))
            assertFalse(status(session, id, uid + 1))
            assertFalse(status(session, id, Process.myUid()))
            assertNull(session.termination.value)
            assertTrue(status(session, id, uid))
            assertEquals(AnimationTermination.CodecRejected, session.termination.value)
            assertNull(admission.acquire(true, 0)) // A status is not proof of process death.
            invoke(session, "workerDied")
            assertEquals(AnimationTermination.CodecRejected, session.termination.value)
            checkNotNull(admission.acquire(true, 0)).release()
        } finally {
            session.close()
            invoke(session, "workerDied")
        }
    }

    @Test
    fun firstOwnerCloseAndPreemptionReasonsSurviveLaterDeath() {
        for ((first, expected) in listOf(
            "close" to AnimationTermination.OwnerClosed,
            "yieldToViewer" to AnimationTermination.Preempted,
        )) {
            val admission = AnimationAdmission()
            val session = session(checkNotNull(admission.acquire(true, 100)))
            invoke(session, first)
            assertNull(admission.acquire(true, 0))
            invoke(session, "workerDied")
            assertEquals(expected, session.termination.value)
            checkNotNull(admission.acquire(true, 0)).release()
        }
    }

    @Test
    fun unknownDeathAndTransportRetirementWakeTheDeliveredDrawableWithoutInventingDeath() =
        kotlinx.coroutines.runBlocking {
            for (reason in listOf(AnimationTermination.TransportUnavailable, AnimationTermination.WorkerLostUnknown)) {
                val admission = AnimationAdmission()
                val session = session(checkNotNull(admission.acquire(true, 100)))
                val drawable =
                    IsolatedAnimationDrawable(
                        android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888),
                        session,
                    )
                val observed =
                    async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                        drawable.awaitTermination()
                    }
                try {
                    if (reason == AnimationTermination.WorkerLostUnknown) {
                        invoke(session, "workerDied")
                    } else {
                        IsolatedAnimationSession::class.java
                            .getDeclaredMethod(
                                "closeWithReason",
                                AnimationTermination::class.java,
                            ).apply { isAccessible = true }
                            .invoke(session, reason)
                    }
                    assertEquals(reason, observed.await())
                    if (reason == AnimationTermination.TransportUnavailable) assertNull(admission.acquire(true, 0))
                    invoke(session, "workerDied")
                    checkNotNull(admission.acquire(true, 0)).release()
                } finally {
                    observed.cancel()
                    drawable.close()
                    invoke(session, "workerDied")
                }
            }
        }

    private fun session(lease: AnimationAdmission.Lease): IsolatedAnimationSession =
        IsolatedAnimationSession::class.java
            .getDeclaredConstructor(
                Context::class.java,
                Int::class.javaPrimitiveType,
                AnimationAdmission.Lease::class.java,
                kotlin.jvm.functions.Function0::class.java,
                kotlin.jvm.functions.Function2::class.java,
            ).apply { isAccessible = true }
            .newInstance(
                ApplicationProvider.getApplicationContext<Context>(),
                2,
                lease,
                {},
                { signal: java.util.concurrent.CountDownLatch, remaining: Long ->
                    signal.await(remaining, java.util.concurrent.TimeUnit.NANOSECONDS)
                },
            )

    private fun field(
        session: IsolatedAnimationSession,
        name: String,
    ): Any? =
        IsolatedAnimationSession::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .get(session)

    private fun set(
        session: IsolatedAnimationSession,
        name: String,
        value: Any,
    ) {
        IsolatedAnimationSession::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .set(session, value)
    }

    private fun invoke(
        session: IsolatedAnimationSession,
        name: String,
    ) {
        IsolatedAnimationSession::class.java
            .getDeclaredMethod(name)
            .apply { isAccessible = true }
            .invoke(session)
    }

    private fun status(
        session: IsolatedAnimationSession,
        id: Int,
        uid: Int,
    ): Boolean {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeInterfaceToken(AnimationFrameProtocol.DESCRIPTOR)
            parcel.writeInt(id)
            parcel.writeInt(AnimationFrameProtocol.CODEC_REJECTED)
            parcel.writeInt(1)
            parcel.writeInt(0)
            parcel.writeInt(0)
            parcel.setDataPosition(0)
            IsolatedAnimationSession::class.java
                .getDeclaredMethod(
                    "receiveStatus",
                    Parcel::class.java,
                    Int::class.javaPrimitiveType,
                ).apply { isAccessible = true }
                .invoke(session, parcel, uid) as Boolean
        } finally {
            parcel.recycle()
        }
    }
}
