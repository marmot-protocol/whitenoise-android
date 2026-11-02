package dev.ipf.whitenoise.android.media.sandbox

import android.app.Application
import android.content.Context
import android.os.Binder
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real parent IPC/validator over a local Binder fake; no decoding or OS-worker lifetime evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [30])
class AvStreamPreferredProtocolTest {
    @Test
    fun legacyReadWritesExplicitAnySelectorAndRetainsGlobalEofSession() {
        assertEquals("dev.ipf.whitenoise.media.av.stream.v2", AvStreamProtocol.DESCRIPTOR)
        StreamFixture().use { fixture ->
            assertSame(AvStreamFrame.End, fixture.read())
            assertSame(AvStreamFrame.End, fixture.read(AvStreamProtocol.ANY_TRACK))
            assertEquals(listOf(0, 0), fixture.selectors.toList())
            assertFalse(fixture.lifetime.closed)
        }
    }

    @Test
    fun selectedTrackEofIsStrictAndDoesNotRetireTheOtherTrack() {
        StreamFixture().use { fixture ->
            fixture.values = longArrayOf(4, 0, 0, 0, 0, 0, 0)
            assertSame(AvStreamFrame.AudioEnd, fixture.read(AvStreamProtocol.AUDIO_TRACK))
            fixture.values = longArrayOf(3, 0, 0, 0, 0, 0, 0)
            assertSame(AvStreamFrame.VideoEnd, fixture.read(AvStreamProtocol.VIDEO_TRACK))
            fixture.values = LongArray(7)
            assertSame(AvStreamFrame.End, fixture.read())
            assertEquals(listOf(2, 1, 0), fixture.selectors.toList())
            assertFalse(fixture.lifetime.closed)
        }
    }

    @Test
    fun anySelectorRejectsBothSelectedEndKindsAndSelectedReadsRejectGlobalEnd() {
        for ((selector, kind) in listOf(0 to 3L, 0 to 4L, 1 to 0L, 2 to 0L, 1 to 4L, 2 to 3L)) {
            StreamFixture().use { fixture ->
                fixture.values = longArrayOf(kind, 0, 0, 0, 0, 0, 0)
                val failure = readFailure { fixture.read(selector) }
                assertTrue("selector=$selector kind=$kind", failure is IllegalArgumentException)
                assertTrue(fixture.lifetime.closed)
                assertEquals(listOf(selector), fixture.selectors.toList())
            }
        }
    }

    @Test
    fun wrongTrackDataIsRejectedBeforeReadingSharedMemory() {
        val wrongTracks =
            listOf(
                AvStreamProtocol.VIDEO_TRACK to longArrayOf(2, 0, 0, 8_000, 1, 0, 2),
                AvStreamProtocol.AUDIO_TRACK to longArrayOf(1, 1, 1, 0, 0, 0, 4),
            )
        for ((selector, values) in wrongTracks) {
            StreamFixture().use { fixture ->
                fixture.values = values
                assertTrue(readFailure { fixture.read(selector) } is IllegalArgumentException)
                assertTrue(fixture.lifetime.closed)
            }
        }
    }

    @Test
    fun selectedEndRequiresEveryOtherScalarZeroAndRequestedTrack() {
        for (kind in listOf(3L, 4L)) {
            val valid = longArrayOf(kind, 0, 0, 0, 0, 0, 0)
            assertEquals(kind.toInt(), AvStreamOutput.from(valid, 1, true, true).kind)
            assertTrue(runCatching { AvStreamOutput.from(valid, 1, kind != 3L, kind != 4L) }.isFailure)
            for (index in 1..6) {
                val invalid = valid.copyOf().also { it[index] = 1 }
                assertTrue(runCatching { AvStreamOutput.from(invalid, 1, true, true) }.isFailure)
            }
            assertTrue(runCatching { AvStreamOutput.from(valid.copyOf(6), 1, true, true) }.isFailure)
            assertTrue(runCatching { AvStreamOutput.from(valid.copyOf(8), 1, true, true) }.isFailure)
        }
    }

    @Test
    fun malformedWireCountTrailingDataAndOversizedReplyRetireTheExactSession() {
        for (mode in listOf("short", "long", "trailing", "oversized", "tuple")) {
            StreamFixture().use { fixture ->
                fixture.replyMode = mode
                assertTrue("mode=$mode", readFailure { fixture.read() } is IOException)
                assertTrue(fixture.lifetime.closed)
                assertEquals(listOf(0), fixture.selectors.toList())
            }
        }
    }

    @Test
    fun invalidOrUnrequestedSelectorNeverReachesTheBinder() {
        for ((video, audio, selector) in listOf(
            Triple(true, true, -1),
            Triple(true, true, 3),
            Triple(false, true, 1),
            Triple(true, false, 2),
        )) {
            StreamFixture(video, audio).use { fixture ->
                assertTrue(readFailure { fixture.read(selector) } is IllegalArgumentException)
                assertTrue(fixture.selectors.isEmpty())
                assertTrue(fixture.lifetime.closed)
            }
        }
    }

    @Test
    fun retiredSessionCannotIssueAnotherPreferredRead() {
        StreamFixture().use { fixture ->
            fixture.stream.close()
            assertTrue(readFailure { fixture.read(AvStreamProtocol.AUDIO_TRACK) } is IOException)
            assertTrue(fixture.selectors.isEmpty())
        }
    }

    @Test
    fun readyRequiresPriorIdentityAndRefusesOnewayWithoutLosingIdentity() {
        for (flags in listOf(0x10, 0x10 or IBinder.FLAG_ONEWAY)) {
            StreamFixture(ready = false).use { fixture ->
                assertFalse(fixture.deliverIdentity(IBinder.FLAG_ONEWAY))
                assertFalse(fixture.identityCalibrated())
                assertTrue(fixture.deliverIdentity(0x10))
                val accepted = fixture.deliverReady(flags)
                assertEquals(flags and IBinder.FLAG_ONEWAY == 0, accepted)
                assertTrue(fixture.identityCalibrated())
                assertEquals(accepted, fixture.stream.isolation != null)
            }
        }
    }

    @Test
    fun identityRefusesWrongTokenTrailingDataAndDuplicatesBeforeReady() {
        for (mode in listOf("token", "trailing", "fd")) {
            StreamFixture(ready = false).use { fixture ->
                assertFalse(fixture.deliverIdentity(0, mode))
                assertFalse(fixture.identityCalibrated())
                assertTrue(fixture.deliverIdentity(0))
                assertTrue(fixture.identityCalibrated())
                assertFalse(fixture.deliverIdentity(0))
                assertFalse(fixture.lifetime.openMayHaveBeenDelivered)
            }
        }
    }

    @Test
    fun exactLateIdentityAfterCloseOrAccountRevocationOnlyAidsCleanupAndNeverCommitsOpen() {
        for (accountRevoked in listOf(false, true)) {
            StreamFixture(ready = false).use { fixture ->
                if (accountRevoked) fixture.revokeAccount()
                fixture.lifetime.close()
                assertTrue(fixture.deliverIdentity(0))
                assertTrue(fixture.identityCalibrated())
                assertFalse(fixture.lifetime.commitOpen())
                assertFalse(fixture.lifetime.openMayHaveBeenDelivered)
                assertEquals(null, fixture.stream.isolation)
            }
        }
    }

    @Test
    fun readyFromDifferentCallerCannotReplaceTheInputFreeIdentity() {
        StreamFixture(ready = false).use { fixture ->
            assertTrue(fixture.deliverIdentity(0))
            assertFalse(fixture.deliverReady(0, "caller"))
            assertTrue(fixture.identityCalibrated())
            assertTrue(fixture.lifetime.closed)
            assertFalse(fixture.lifetime.openMayHaveBeenDelivered)
            assertEquals(null, fixture.stream.isolation)
        }
    }

    @Test
    fun binderThrowAfterOpenCommitRetainsStickyExposure() {
        StreamFixture().use { fixture ->
            assertTrue(fixture.openFailureAfterCommit() is ExecutionException)
            assertTrue(fixture.lifetime.openMayHaveBeenDelivered)
            fixture.stream.close()
            assertTrue(fixture.lifetime.openMayHaveBeenDelivered)
        }
    }

    @Test
    fun onlyExactOpenContainerRefusalWithReadyRetainsItsTypedCause() {
        for (mode in listOf("container", "container-token", "container-sequence", "container-trailing")) {
            StreamFixture(ready = false).use { fixture ->
                assertTrue(fixture.deliverIdentity(0))
                assertTrue(fixture.deliverReady(0))
                fixture.replyMode = mode
                val failure = fixture.transactionFailure(AvStreamProtocol.OPEN)
                assertTrue(failure is IOException)
                assertEquals(mode == "container", hasContainerCause(failure))
                assertEquals(listOf(AvStreamProtocol.OPEN), fixture.transactions.toList())
                assertTrue(fixture.lifetime.openMayHaveBeenDelivered)
            }
        }
    }

    @Test
    fun containerStatusOnReadOrBeforeReadyNeverBecomesCacheInvalidationEvidence() {
        StreamFixture(ready = false).use { fixture ->
            assertTrue(fixture.deliverIdentity(0))
            fixture.replyMode = "container"
            val failure = fixture.transactionFailure(AvStreamProtocol.OPEN)
            assertTrue(failure is IOException)
            assertFalse(hasContainerCause(failure))
            assertEquals(null, fixture.stream.isolation)
        }
        StreamFixture(ready = false).use { fixture ->
            assertTrue(fixture.deliverIdentity(0))
            assertTrue(fixture.deliverReady(0))
            fixture.replyMode = "container"
            val failure = fixture.transactionFailure(AvStreamProtocol.READ)
            assertTrue(failure is IOException)
            assertFalse(hasContainerCause(failure))
            assertFalse(fixture.lifetime.openMayHaveBeenDelivered)
        }
    }

    @Test
    fun identifyReplyWithoutSynchronousIdentityCannotAuthorizeOpen() {
        StreamFixture(ready = false).use { fixture ->
            fixture.replyMode = "identity-without-callback"
            val failure = fixture.transactionFailure(AvStreamProtocol.IDENTIFY)
            assertTrue(failure is IOException)
            assertFalse(hasContainerCause(failure))
            assertFalse(fixture.identityCalibrated())
            assertFalse(fixture.lifetime.openMayHaveBeenDelivered)
            assertEquals(listOf(AvStreamProtocol.IDENTIFY), fixture.transactions.toList())
        }
    }

    private fun hasContainerCause(failure: Throwable?): Boolean {
        val causes = generateSequence(failure) { it.cause }.take(8)
        return causes.any { it is RawContainerFailure }
    }

    private fun readFailure(action: () -> AvStreamFrame): Throwable? {
        val failure = runCatching(action).exceptionOrNull()
        assertTrue("Expected a completed caller RPC failure, not a test deadline", failure is ExecutionException)
        return failure?.cause
    }

    /** No bind/open/input is performed: the fake endpoint has no framework process or selected descriptor. */
    private class StreamFixture(
        video: Boolean = true,
        audio: Boolean = true,
        ready: Boolean = true,
    ) : AutoCloseable {
        val selectors = CopyOnWriteArrayList<Int>()
        val transactions = CopyOnWriteArrayList<Int>()
        var values = LongArray(7)
        var replyMode = "valid"
        private val caller = Executors.newSingleThreadExecutor()
        private val admissions: AvStreamAdmissions<IsolatedAvStream>
        private val epoch: Long
        val stream: IsolatedAvStream
        val lifetime: AvStreamLifetime
        private val uid = android.os.Process.myUid() + 1
        private val pid = android.os.Process.myPid() + 1
        private val witness =
            KernelProcessWitness { _, _ ->
                throw android.system.ErrnoException("kill", android.system.OsConstants.EPERM)
            }

        init {
            @Suppress("UNCHECKED_CAST")
            val current = field(null, "admissions") as AvStreamAdmissions<IsolatedAvStream>
            admissions = current
            epoch = admissions.epoch()
            assertEquals(null, admissions.current(AvStreamLane.Analysis, admissions.epoch()))
            val constructor =
                IsolatedAvStream::class.java.declaredConstructors
                    .single { it.parameterCount == 6 }
                    .apply { isAccessible = true }
            val context = ApplicationProvider.getApplicationContext<Context>()
            stream = constructor.newInstance(context, video, audio, 1, epoch, AvStreamLane.Analysis) as IsolatedAvStream
            lifetime = field(stream, "lifetime") as AvStreamLifetime
            // This fixture created no AMS binding; preserve normal cleanup without asserting kernel death.
            lifetime.neverBound()
            if (ready) {
                setField("readyIdentity", uid to pid)
                setField("identity", uid to pid)
            }
            setField("processWitness", witness)
            setField("deathRegistered", true)
            setField("endpoint", endpoint())
            assertSame(stream, admissions.reserve(AvStreamLane.Analysis, epoch) { stream })
        }

        private fun endpoint(): Binder =
            object : Binder() {
                override fun onTransact(
                    code: Int,
                    data: Parcel,
                    reply: Parcel?,
                    flags: Int,
                ): Boolean {
                    if (code == AvStreamProtocol.RETIRE) return true
                    transactions += code
                    if (code == AvStreamProtocol.OPEN && replyMode == "valid") throw android.os.DeadObjectException()
                    assertTrue(code in listOf(AvStreamProtocol.OPEN, AvStreamProtocol.IDENTIFY, AvStreamProtocol.READ))
                    data.enforceInterface(AvStreamProtocol.DESCRIPTOR)
                    val tuple = AvStreamProtocol.readTuple(data)
                    if (code == AvStreamProtocol.READ) {
                        assertEquals(Int.SIZE_BYTES, data.dataAvail())
                        selectors += data.readInt()
                    }
                    assertEquals(0, data.dataAvail())
                    val result = checkNotNull(reply)
                    if (replyMode.startsWith("container")) {
                        result.writeInt(AvStreamProtocol.CONTAINER_MALFORMED)
                        AvStreamProtocol.writeTuple(
                            result,
                            if (replyMode == "container-token") "f".repeat(32) else tuple.first,
                            tuple.second + if (replyMode == "container-sequence") 1 else 0,
                        )
                        if (replyMode == "container-trailing") result.writeInt(0)
                        return true
                    }
                    result.writeInt(0)
                    if (code == AvStreamProtocol.IDENTIFY) {
                        AvStreamProtocol.writeTuple(result, tuple.first, tuple.second)
                        AvStreamProtocol.writeValues(result, longArrayOf())
                        return true
                    }
                    AvStreamProtocol.writeTuple(result, tuple.first, tuple.second + if (replyMode == "tuple") 1 else 0)
                    val output =
                        when (replyMode) {
                            "short" -> values.copyOf(6)
                            "long" -> values.copyOf(8)
                            else -> values
                        }
                    AvStreamProtocol.writeValues(result, output)
                    if (replyMode == "trailing") result.writeInt(0)
                    if (replyMode == "oversized") result.writeByteArray(ByteArray(AvStreamProtocol.MAX_REPLY_BYTES))
                    return true
                }
            }

        fun revokeAccount() {
            assertTrue(admissions.invalidateAll().any { it === stream })
        }

        fun identityCalibrated(): Boolean = witness.acceptsCaller(uid, pid)

        fun deliverReady(
            flags: Int,
            mode: String = "valid",
        ): Boolean = deliverCallback(AvStreamProtocol.READY, flags, mode)

        fun deliverIdentity(
            flags: Int,
            mode: String = "valid",
        ): Boolean = deliverCallback(AvStreamProtocol.IDENTITY, flags, mode)

        private fun deliverCallback(
            code: Int,
            flags: Int,
            mode: String = "valid",
        ): Boolean {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            val pipe = if (mode == "fd") android.os.ParcelFileDescriptor.createPipe() else null
            val identity =
                org.robolectric.shadows.ShadowBinder.CallingIdentity
                    .newBuilder()
                    .setUid(if (mode == "caller") uid + 1 else uid)
                    .setPid(if (mode == "caller") pid + 1 else pid)
                    .setUserHandle(android.os.Process.myUserHandle())
                    .build()
            val previous =
                org.robolectric.shadows.ShadowBinder
                    .setCallingIdentityForCurrentThread(identity)
            return try {
                data.writeInterfaceToken(AvStreamProtocol.READY_DESCRIPTOR)
                data.writeString(if (mode == "token") "0".repeat(32) else field(stream, "token") as String)
                data.writeInt(1)
                data.writeInt(0)
                data.writeInt(0)
                if (code == AvStreamProtocol.READY) {
                    data.writeInt(android.system.OsConstants.EACCES)
                    data.writeInt(android.system.OsConstants.EBADF)
                }
                if (mode == "trailing") data.writeInt(0)
                pipe?.let { data.writeFileDescriptor(it[0].fileDescriptor) }
                val accepted =
                    (field(stream, "readyCallback") as IBinder).transact(code, data, reply, flags)
                if (accepted) assertEquals(1, reply.readInt())
                accepted
            } finally {
                org.robolectric.shadows.ShadowBinder
                    .setCallingIdentityForCurrentThread(previous)
                reply.recycle()
                data.recycle()
                pipe?.forEach { it.close() }
            }
        }

        /** Executes the real parent RPC validator; only the remote transport is a local Binder fake. */
        fun transactionFailure(code: Int): Throwable? =
            caller
                .submit<Throwable?> {
                    val transaction = IsolatedAvStream::class.java.declaredMethods.single { it.name == "transact" }
                    transaction.isAccessible = true
                    val writer: (Parcel) -> Unit = { data ->
                        if (code == AvStreamProtocol.READ) data.writeInt(AvStreamProtocol.ANY_TRACK)
                    }
                    try {
                        transaction.invoke(stream, code, if (code == AvStreamProtocol.IDENTIFY) 0 else 6, writer)
                        null
                    } catch (failure: java.lang.reflect.InvocationTargetException) {
                        failure.targetException
                    }
                }.get(2, TimeUnit.SECONDS)

        fun openFailureAfterCommit(): Throwable? =
            runCatching {
                caller
                    .submit {
                        val transaction = IsolatedAvStream::class.java.declaredMethods.single { it.name == "transact" }
                        transaction.isAccessible = true
                        val writer: (Parcel) -> Unit = {}
                        transaction.invoke(stream, AvStreamProtocol.OPEN, 6, writer)
                    }.get(2, TimeUnit.SECONDS)
            }.exceptionOrNull()

        fun read(selector: Int? = null): AvStreamFrame =
            caller
                .submit<AvStreamFrame> {
                    if (selector == null) stream.read() else stream.read(selector)
                }.get(2, TimeUnit.SECONDS)

        override fun close() {
            stream.close()
            caller.shutdownNow()
            val released = field(stream, "released") as CountDownLatch
            val callbacks = field(stream, "callbacks") as HandlerThread
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (released.count != 0L && System.nanoTime() < deadline) {
                try {
                    shadowOf(callbacks.looper).idleFor(Duration.ofMillis(50))
                } catch (failure: IllegalStateException) {
                    // Cleanup quits this Looper before its final released latch acknowledgment.
                    val cleaned = field(stream, "cleaned") as AtomicBoolean
                    val remaining = deadline - System.nanoTime()
                    if (!cleaned.get() || remaining <= 0 || !released.await(remaining, TimeUnit.NANOSECONDS)) {
                        throw failure
                    }
                }
                released.await(5, TimeUnit.MILLISECONDS)
            }
            assertEquals("Only fixture-owned resources may remain", 0L, released.count)
            assertEquals(null, admissions.current(AvStreamLane.Analysis, admissions.epoch()))
        }

        private fun setField(
            name: String,
            value: Any,
        ) {
            IsolatedAvStream::class.java
                .getDeclaredField(name)
                .apply { isAccessible = true }
                .set(stream, value)
        }

        private fun field(
            owner: IsolatedAvStream?,
            name: String,
        ): Any? =
            IsolatedAvStream::class.java
                .getDeclaredField(name)
                .apply { isAccessible = true }
                .get(owner)
    }
}
