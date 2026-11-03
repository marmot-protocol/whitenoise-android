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
    /** Legacy Read Writes Explicit Any Selector And Retains Global Eof Session. */
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

    /** Selected Track Eof Is Strict And Does Not Retire The Other Track. */
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

    /** Any Selector Rejects Both Selected End Kinds And Selected Reads Reject Global End. */
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

    /** Wrong Track Data Is Rejected Before Reading Shared Memory. */
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

    /** Selected End Requires Every Other Scalar Zero And Requested Track. */
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

    /** Malformed Wire Count Trailing Data And Oversized Reply Retire The Exact Session. */
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

    /** Invalid Or Unrequested Selector Never Reaches The Binder. */
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

    /** Retired Session Cannot Issue Another Preferred Read. */
    @Test
    fun retiredSessionCannotIssueAnotherPreferredRead() {
        StreamFixture().use { fixture ->
            fixture.stream.close()
            assertTrue(readFailure { fixture.read(AvStreamProtocol.AUDIO_TRACK) } is IOException)
            assertTrue(fixture.selectors.isEmpty())
        }
    }

    /** Ready Requires Prior Identity And Refuses Oneway Without Losing Identity. */
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

    /** Identity Refuses Wrong Token Trailing Data And Duplicates Before Ready. */
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

    /** Exact Late Identity After Close Or Account Revocation Only Aids Cleanup And Never Commits Open. */
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

    /** Ready From Different Caller Cannot Replace The Input Free Identity. */
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

    /** Binder Throw After Open Commit Retains Sticky Exposure. */
    @Test
    fun binderThrowAfterOpenCommitRetainsStickyExposure() {
        StreamFixture().use { fixture ->
            assertTrue(fixture.openFailureAfterCommit() is ExecutionException)
            assertTrue(fixture.lifetime.openMayHaveBeenDelivered)
            fixture.stream.close()
            assertTrue(fixture.lifetime.openMayHaveBeenDelivered)
        }
    }

    /** Only Exact Open Container Refusal With Ready Retains Its Typed Cause. */
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
    fun containerStatusBeforeReadyOrOpenNeverBecomesCacheInvalidationEvidence() {
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

    /** Identify Reply Without Synchronous Identity Cannot Authorize Open. */
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

    /** READ invalidation needs a prior OPEN exposure and the exact admitted endpoint tuple. */
    @Test
    fun onlyExactReadContainerRefusalAfterOpenRetainsItsTypedCause() {
        for (mode in listOf("container", "container-token", "container-sequence", "container-trailing")) {
            StreamFixture(ready = false).use { fixture ->
                assertTrue(fixture.deliverIdentity(0))
                assertTrue(fixture.deliverReady(0))
                assertTrue(fixture.lifetime.commitOpen())
                fixture.replyMode = mode
                val failure = fixture.transactionFailure(AvStreamProtocol.READ)
                assertTrue(failure is IOException)
                assertEquals(mode == "container", hasContainerCause(failure))
                assertTrue(fixture.lifetime.openMayHaveBeenDelivered)
            }
        }
    }

    /** A late trusted endpoint can provide its PID before retirement without receiving OPEN or any FD. */
    @Test
    fun lateUnexposedConnectionIdentifiesBeforeRetiring() {
        StreamFixture(ready = false, retirementIdentity = true, pendingBinding = true).use { fixture ->
            fixture.closeAndDrainBeforeConnecting()
            assertTrue(fixture.awaitRetire())
            assertEquals(
                listOf(AvStreamProtocol.IDENTIFY, AvStreamProtocol.RETIRE),
                fixture.retirementTransactions.toList(),
            )
            assertTrue(fixture.identityCalibrated())
            assertFalse(fixture.lifetime.openMayHaveBeenDelivered)
            assertEquals(null, fixture.stream.isolation)
            fixture.finishRetiredBinding()
        }
    }

    /** Failure of cleanup-only identity cannot skip RETIRE or invent a process identity. */
    @Test
    fun refusedLateIdentityStillRetiresWithoutInventingWitness() {
        StreamFixture(ready = false, pendingBinding = true).use { fixture ->
            fixture.replyMode = "retirement-identity-refused"
            fixture.closeAndDrainBeforeConnecting()
            assertTrue(fixture.awaitRetire())
            assertEquals(
                listOf(AvStreamProtocol.IDENTIFY, AvStreamProtocol.RETIRE),
                fixture.retirementTransactions.toList(),
            )
            assertFalse(fixture.identityCalibrated())
            assertFalse(fixture.lifetime.openMayHaveBeenDelivered)
            assertFalse(fixture.lifetime.releasable())
        }
    }

    /** A possibly exposed endpoint stays on the existing immediate-retirement path. */
    @Test
    fun possibleOpenExposureNeverTriggersCleanupIdentification() {
        StreamFixture(ready = false).use { fixture ->
            assertTrue(fixture.lifetime.commitOpen())
            fixture.connectAfterClose()
            assertTrue(fixture.awaitRetire())
            assertEquals(listOf(AvStreamProtocol.RETIRE), fixture.retirementTransactions.toList())
            assertFalse(fixture.identityCalibrated())
            assertTrue(fixture.lifetime.openMayHaveBeenDelivered)
        }
    }

    /** Inspects the causal chain for the typed container failure used by cache invalidation policy. */
    private fun hasContainerCause(failure: Throwable?): Boolean {
        val causes = generateSequence(failure) { it.cause }.take(8)
        return causes.any { it is RawContainerFailure }
    }

    /** Exercises a malformed read reply and returns the resulting failure for precise policy assertions. */
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
        private val retirementIdentity: Boolean = false,
        private val pendingBinding: Boolean = false,
    ) : AutoCloseable {
        val selectors = CopyOnWriteArrayList<Int>()
        val transactions = CopyOnWriteArrayList<Int>()
        val retirementTransactions = CopyOnWriteArrayList<Int>()
        private val retired = CountDownLatch(1)
        private val forgotten = AtomicBoolean()
        private val unbindCalls =
            java.util.concurrent.atomic
                .AtomicInteger()
        private val workerGone = AtomicBoolean()
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
                val error = if (workerGone.get()) android.system.OsConstants.ESRCH else android.system.OsConstants.EPERM
                throw android.system.ErrnoException("kill", error)
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
            val context =
                object : android.content.ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
                    /** Models dispatcher forget so a queued late callback cannot bypass successful unbind. */
                    override fun unbindService(conn: android.content.ServiceConnection) {
                        // Android's ServiceDispatcher drops all later callbacks once it is forgotten.
                        unbindCalls.incrementAndGet()
                        forgotten.set(true)
                    }
                }
            stream = constructor.newInstance(context, video, audio, 1, epoch, AvStreamLane.Analysis) as IsolatedAvStream
            lifetime = field(stream, "lifetime") as AvStreamLifetime
            // Pending mode models an accepted dispatcher registration; no actual AMS worker is started.
            if (pendingBinding) lifetime.bindCompleted(true) else lifetime.neverBound()
            if (ready) {
                setField("readyIdentity", uid to pid)
                setField("identity", uid to pid)
            }
            setField("processWitness", witness)
            setField("deathRegistered", !pendingBinding)
            setField("endpoint", if (pendingBinding) null else endpoint())
            assertSame(stream, admissions.reserve(AvStreamLane.Analysis, epoch) { stream })
        }

        /** Creates the scripted Binder endpoint used to control protocol reply ordering and identity. */
        private fun endpoint(): Binder =
            object : Binder() {
                @Suppress("ReturnCount") // Each simulated reply mode terminates at its exact protocol boundary.
                override fun onTransact(
                    code: Int,
                    data: Parcel,
                    reply: Parcel?,
                    flags: Int,
                ): Boolean {
                    if (code == AvStreamProtocol.RETIRE) {
                        retirementTransactions += code
                        retired.countDown()
                        return true
                    }
                    if (code == AvStreamProtocol.IDENTIFY && lifetime.closed) {
                        retirementTransactions += code
                        assertFalse(data.hasFileDescriptors())
                        data.enforceInterface(AvStreamProtocol.DESCRIPTOR)
                        val tuple = AvStreamProtocol.readTuple(data)
                        assertEquals(1L, tuple.second)
                        assertSame(field(stream, "readyCallback"), data.readStrongBinder())
                        assertEquals(0, data.dataAvail())
                        if (replyMode == "retirement-identity-refused") throw android.os.DeadObjectException()
                        if (retirementIdentity) assertTrue(deliverIdentity(0))
                        checkNotNull(reply).writeInt(0)
                        return true
                    }
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

        /** Delivers the real ServiceConnection callback after close using this fixture's input-free Binder. */
        fun connectAfterClose() {
            lifetime.beginCall() // Model the startup caller while its late connection callback arrives.
            try {
                setField("endpoint", null)
                setField("deathRegistered", false)
                stream.close()
                val connection = field(stream, "connection") as android.content.ServiceConnection
                connection.onServiceConnected(android.content.ComponentName("test", "worker"), endpoint())
            } finally {
                lifetime.endCall()
                stream.close()
            }
        }

        /** Drains real cleanup before a queued framework callback, rejecting any premature dispatcher forget. */
        fun closeAndDrainBeforeConnecting() {
            check(pendingBinding)
            stream.close()
            val callbacks = field(stream, "callbacks") as HandlerThread
            shadowOf(callbacks.looper).idleFor(Duration.ofMillis(50))
            assertEquals("Closing a pending accepted bind must retain its late callback", 0, unbindCalls.get())
            val connection = field(stream, "connection") as android.content.ServiceConnection
            val callbackHandler = field(stream, "callbackHandler") as android.os.Handler
            callbackHandler.post {
                if (!forgotten.get()) {
                    connection.onServiceConnected(android.content.ComponentName("test", "worker"), endpoint())
                }
            }
            shadowOf(callbacks.looper).idleFor(Duration.ofMillis(50))
        }

        /** Supplies a fake calibrated exit only after IDENTIFY and RETIRE, then requires exactly one unbind. */
        fun finishRetiredBinding() {
            assertTrue(identityCalibrated())
            val retirement = field(stream, "retirement") as java.util.concurrent.ExecutorService
            retirement.submit(Runnable {}).get(2, TimeUnit.SECONDS)
            workerGone.set(true)
            stream.close()
            val released = field(stream, "released") as CountDownLatch
            val callbacks = field(stream, "callbacks") as HandlerThread
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (released.count != 0L && System.nanoTime() < deadline) {
                try {
                    shadowOf(callbacks.looper).idleFor(Duration.ofMillis(50))
                } catch (failure: IllegalStateException) {
                    // Accept a quit race only if real cleanup already won and its release ACK arrives.
                    val cleaned = field(stream, "cleaned") as AtomicBoolean
                    val remaining = deadline - System.nanoTime()
                    if (!cleaned.get() || remaining <= 0 || !released.await(remaining, TimeUnit.NANOSECONDS)) {
                        throw failure
                    }
                }
                released.await(5, TimeUnit.MILLISECONDS)
            }
            assertEquals("Calibrated late-worker exit must complete the real release", 0L, released.count)
            assertEquals(1, unbindCalls.get())
        }

        fun awaitRetire(): Boolean = retired.await(2, TimeUnit.SECONDS)

        /** Invalidates the fixture account epoch while preserving the prior worker witness. */
        fun revokeAccount() {
            assertTrue(admissions.invalidateAll().any { it === stream })
        }

        /** Checks whether the fixture has established the exact worker identity before descriptor admission. */
        fun identityCalibrated(): Boolean = witness.acceptsCaller(uid, pid)

        /** Delivers the scripted READY callback through the same Binder admission path as the worker. */
        fun deliverReady(
            flags: Int,
            mode: String = "valid",
        ): Boolean = deliverCallback(AvStreamProtocol.READY, flags, mode)

        /** Delivers the scripted identity callback with the selected caller UID/PID and transaction flags. */
        fun deliverIdentity(
            flags: Int,
            mode: String = "valid",
        ): Boolean = deliverCallback(AvStreamProtocol.IDENTITY, flags, mode)

        /**
         * Runs the scripted callback transaction so malformed or stale replies exercise the real decoder
         * boundary.
         */
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

        /** Models a failure after OPEN exposure commits, retaining the sticky descriptor-delivery uncertainty. */
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
            // Cleanup-only fixture proof: this test never created an actual AMS process.
            if (pendingBinding) lifetime.processGoneObserved()
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

        /**
         * Installs controlled fixture state through reflection; production visibility and synchronization stay
         * unchanged.
         */
        private fun setField(
            name: String,
            value: Any?,
        ) {
            IsolatedAvStream::class.java
                .getDeclaredField(name)
                .apply { isAccessible = true }
                .set(stream, value)
        }

        /**
         * Reads a private fixture field so assertions can inspect the exact owner rather than a replacement
         * instance.
         */
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
