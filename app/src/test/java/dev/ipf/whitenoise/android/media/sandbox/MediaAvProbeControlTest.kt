package dev.ipf.whitenoise.android.media.sandbox

import android.os.Parcel
import android.os.ParcelFileDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Simulated caller values test admission rules, never real Binder UID or process-death qualification. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class MediaAvProbeControlTest {
    private val tuple = AvProbeTuple("a".repeat(32), "video_construct", 1)

    @Test
    fun firstReadyBindsTupleAndTerminalUidExactlyOnce() {
        val control = AvProbeControl(tuple)
        assertFalse(control.terminal(1, 99_001))
        assertFalse(control.ready(tuple, 100, 42, 100))
        assertFalse(control.ready(tuple, 99_001, 0, 100))
        assertTrue(control.ready(tuple, 99_001, 42, 100))
        assertEquals(AvProbeIdentity(99_001, 42), control.identity)
        assertFalse(control.ready(tuple, 99_002, 43, 100))
        assertFalse(control.terminal(2, 99_001))
        assertFalse(control.terminal(1, 99_002))
        assertTrue(control.terminal(1, 99_001))
        assertFalse(control.terminal(1, 99_001))
    }

    @Test
    fun currentProtocolAbortCannotBecomeDeadlineContinuation() {
        val control = AvProbeControl(tuple)
        assertTrue(control.ready(tuple, 99_001, 42, 100))
        assertTrue(control.abortProtocol())
        assertFalse(control.terminal(1, 99_001))
        assertFalse(control.canDispatch())
        control.revoke()
        assertFalse(control.canReleaseCase(AvProbeCleanup.State.SUCCESS, true, true, true))
        assertFalse(control.abortProtocol())
    }

    @Test
    fun wrongTokenCaseOrRequestCannotInstallIdentity() {
        val frames =
            listOf(
                tuple.copy(token = "b".repeat(32)),
                tuple.copy(case = "video_tracks"),
                tuple.copy(request = 2),
            )
        for (frame in frames) {
            val control = AvProbeControl(tuple)
            assertFalse(control.ready(frame, 99_001, 42, 100))
            assertEquals(null, control.identity)
        }
    }

    @Test
    fun revocationRacingReadyNeverAllowsALaterTerminal() {
        val control = AvProbeControl(tuple)
        val start = CountDownLatch(1)
        val ready =
            Thread {
                check(start.await(2, TimeUnit.SECONDS))
                control.ready(tuple, 99_001, 42, 100)
            }
        ready.start()
        start.countDown()
        control.revoke()
        ready.join(2_000)
        assertFalse(ready.isAlive)
        assertFalse(control.ready(tuple, 99_001, 42, 100))
        assertFalse(control.terminal(1, 99_001))
        assertFalse(control.canDispatch())
    }

    @Test
    fun deadlineContinuationRequiresEveryExactReceipt() {
        val control = AvProbeControl(tuple)
        assertTrue(control.ready(tuple, 99_001, 42, 100))
        val success = AvProbeCleanup.State.SUCCESS
        assertFalse(control.canReleaseCase(success, true, true, true))
        control.revoke()
        assertFalse(control.canReleaseCase(success, false, true, true))
        assertFalse(control.canReleaseCase(success, true, false, true))
        assertFalse(control.canReleaseCase(success, true, true, false))
        assertFalse(control.canReleaseCase(AvProbeCleanup.State.HELD, true, true, true))
        assertFalse(control.canReleaseCase(AvProbeCleanup.State.PENDING, true, true, true))
        assertTrue(control.canReleaseCase(success, true, true, true))
        val missingReady = AvProbeControl(tuple)
        missingReady.revoke()
        assertFalse(missingReady.canReleaseCase(success, true, true, true))
    }

    @Test
    fun cleanupCannotSucceedFromAnAttemptOrMissingHandleClose() {
        for (closeInput in listOf(false, true)) {
            val cleanup = readyCleanup()
            cleanup.inputScopeClosed(false)
            cleanup.outputScopeClosed(false)
            if (closeInput) cleanup.inputScopeClosed(true) else cleanup.outputScopeClosed(true)
            assertTrue(cleanup.beginUnbind())
            assertEquals(AvProbeCleanup.State.PENDING, cleanup.state())
            cleanup.unbound(true)
            assertEquals(AvProbeCleanup.State.HELD, cleanup.state())
            assertFalse(cleanup.beginUnbind())
        }
    }

    @Test
    fun failedUnbindRemainsHeldAndCannotBeRetried() {
        val cleanup = readyCleanup()
        assertTrue(cleanup.beginUnbind())
        cleanup.unbound(false)
        assertEquals(AvProbeCleanup.State.HELD, cleanup.state())
        assertFalse(cleanup.beginUnbind())
    }

    @Test
    fun cleanupWaitsForOperationAndDeathBeforeExactlyOneUnbind() {
        val cleanup = AvProbeCleanup()
        cleanup.inputScopeClosed(false)
        cleanup.outputScopeClosed(false)
        cleanup.revoked()
        cleanup.bindComplete()
        cleanup.finished()
        cleanup.inputScopeClosed(true)
        cleanup.outputScopeClosed(true)
        assertFalse(cleanup.beginUnbind())
        cleanup.lifetimeProven()
        assertTrue(cleanup.beginUnbind())
        assertFalse(cleanup.beginUnbind())
        cleanup.unbound(true)
        assertEquals(AvProbeCleanup.State.SUCCESS, cleanup.state())
    }

    @Test
    fun readyFrameIsPrimitiveBoundedAndRejectsTrailingBytes() {
        val parcel = frame()
        try {
            assertEquals(tuple, readAvProbeReady(parcel))
        } finally {
            parcel.recycle()
        }
        val trailing = frame(trailing = true)
        try {
            assertTrue(runCatching { readAvProbeReady(trailing) }.isFailure)
        } finally {
            trailing.recycle()
        }
    }

    @Test
    fun readyFrameRejectsAnAttachedDescriptor() {
        val descriptors = ParcelFileDescriptor.createPipe()
        val parcel = frame()
        try {
            parcel.setDataPosition(parcel.dataSize())
            parcel.writeFileDescriptor(descriptors[0].fileDescriptor)
            parcel.setDataPosition(0)
            assertTrue(runCatching { readAvProbeReady(parcel) }.isFailure)
        } finally {
            parcel.recycle()
            descriptors.forEach { it.close() }
        }
    }

    private fun readyCleanup(): AvProbeCleanup =
        AvProbeCleanup().apply {
            revoked()
            finished()
            bindComplete()
            lifetimeProven()
        }

    private fun frame(trailing: Boolean = false): Parcel =
        Parcel.obtain().apply {
            writeInterfaceToken(AV_READY_DESCRIPTOR)
            writeInt(1)
            writeString(tuple.token)
            writeString(tuple.case)
            writeInt(tuple.request)
            if (trailing) writeInt(7)
            setDataPosition(0)
        }
}
