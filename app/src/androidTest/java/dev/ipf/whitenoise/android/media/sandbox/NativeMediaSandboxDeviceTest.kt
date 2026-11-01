package dev.ipf.whitenoise.android.media.sandbox

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Executes the production installer in disposable isolated workers; it never parses account data. */
@RunWith(AndroidJUnit4::class)
@PullRequestDeviceSmoke
@MediaContainmentAcceptance
class NativeMediaSandboxDeviceTest {
    @Test
    fun installerCoversExistingAndNewJavaAndNativeThreads() {
        val result = runProbe(NativeSandboxProbe.THREADS)
        assertEquals(3, result.rows.size)
        val expected = longArrayOf(1, 1, 1, 2, OsConstants.EPERM.toLong(), 0, 1, 2, OsConstants.EPERM.toLong())
        result.rows.forEach { row -> assertArrayEquals(expected, row) }
    }

    @Test
    fun clone3IsDeniedOrThePlatformTerminatesItsDedicatedWorker() {
        val result = runProbe(NativeSandboxProbe.CLONE3)
        if (result.rows.isNotEmpty()) {
            assertEquals(1, result.rows.size)
            assertArrayEquals(longArrayOf(OsConstants.EPERM.toLong()), result.rows.single())
        } else {
            assertSignalExit(result.pid, OsConstants.SIGSYS, result.startedAtMs)
        }
    }

    @Test
    fun blockedNativeReadEndsWithObservedOsProcessDisappearance() {
        val result = runProbe(NativeSandboxProbe.BLOCKED_NATIVE)
        assertTrue("A native read with no writer cannot produce a success reply", result.rows.isEmpty())
        assertSignalExit(result.pid, OsConstants.SIGKILL, result.startedAtMs)
    }

    private data class Result(
        val pid: Int,
        val rows: List<LongArray>,
        val startedAtMs: Long,
    )

    private class Callback(
        private val mode: Int,
    ) : Binder() {
        val pid = AtomicInteger()
        private val uid = AtomicInteger()
        val rows = AtomicReference<List<LongArray>>(emptyList())
        val failure = AtomicReference<Throwable?>()
        private val received = AtomicBoolean()
        val armed = AtomicBoolean()
        val timerSeen = AtomicBoolean()
        val terminal = CountDownLatch(1)

        // Refuse every malformed primitive callback before retaining qualification evidence.
        @Suppress("ReturnCount", "CyclomaticComplexMethod")
        override fun onTransact(
            code: Int,
            data: Parcel,
            reply: Parcel?,
            flags: Int,
        ): Boolean =
            try {
                check(code == NativeSandboxProbe.CALLBACK)
                check(data.dataSize() <= NativeSandboxProbe.MAX_BYTES && !data.hasFileDescriptors())
                data.enforceInterface(NativeSandboxProbe.DESCRIPTOR)
                val kind = data.readInt()
                val oneWay = kind == NativeSandboxProbe.TIMED_OUT
                check((flags and IBinder.FLAG_ONEWAY != 0) == oneWay)
                check(oneWay || reply != null)
                check(data.readInt() == mode)
                val count = data.readInt()
                check(count in 0..3)
                val values =
                    List(count) {
                        val length = data.readInt()
                        check(length in 1..NativeSandboxProbe.DIAGNOSTIC_FIELDS)
                        LongArray(length) { data.readLong() }
                    }
                check(data.dataAvail() == 0)
                val callerPid = Binder.getCallingPid()
                val callerUid = Binder.getCallingUid()
                check(callerUid != Process.myUid())
                check(oneWay || (callerPid > 0 && callerPid != Process.myPid()))
                if (kind == NativeSandboxProbe.IDENTITY) {
                    check(pid.compareAndSet(0, callerPid))
                    uid.set(callerUid)
                    check(calibrateAlive(callerPid))
                    check(values.size == 1 && values.single().size == 4)
                    val facts = values.single()
                    check(facts[0] == 1L && facts[1] == 0L && facts[2] == 0L)
                    check(
                        facts[3] in
                            setOf(OsConstants.EACCES.toLong(), OsConstants.EPERM.toLong(), OsConstants.ENOENT.toLong()),
                    )
                } else if (kind == NativeSandboxProbe.TIMED_OUT) {
                    check(mode == NativeSandboxProbe.BLOCKED_NATIVE && values.isEmpty() && armed.get())
                    check(pid.get() > 0 && uid.get() == callerUid && timerSeen.compareAndSet(false, true))
                    terminal.countDown()
                } else if (kind == NativeSandboxProbe.FAILED) {
                    check(pid.get() == callerPid && uid.get() == callerUid)
                    failure.set(IllegalStateException("Qualification worker returned or failed before its watchdog"))
                    terminal.countDown()
                } else if (kind == NativeSandboxProbe.ARMED) {
                    check(mode != NativeSandboxProbe.THREADS && values.isEmpty())
                    check(pid.get() == callerPid && uid.get() == callerUid && armed.compareAndSet(false, true))
                } else {
                    check(mode != NativeSandboxProbe.BLOCKED_NATIVE)
                    check(kind == NativeSandboxProbe.RESULT && pid.get() == callerPid && uid.get() == callerUid)
                    check(mode == NativeSandboxProbe.THREADS || armed.get())
                    check(received.compareAndSet(false, true))
                    rows.set(values)
                    terminal.countDown()
                }
                true
            } catch (problem: RuntimeException) {
                failure.set(problem)
                terminal.countDown()
                false
            }
    }

    @Suppress("LongMethod") // One fixture retains its connection, synthetic canary and exact PID through cleanup.
    private fun runProbe(mode: Int): Result {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val startedAtMs = System.currentTimeMillis()
        val callback = Callback(mode)
        val endpoint = AtomicReference<IBinder?>()
        val connected = CountDownLatch(1)
        val death = CountDownLatch(1)
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName,
                    service: IBinder,
                ) {
                    endpoint.set(service)
                    service.linkToDeath({ death.countDown() }, 0)
                    connected.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName) = Unit
            }
        val canary = File(context.filesDir, "media-containment-canary")
        check(canary.createNewFile()) { "Another qualification owns the synthetic canary" }
        var bindingAttempted = false
        try {
            canary.writeText("synthetic-native-sandbox-canary")
            bindingAttempted = true
            check(
                context.bindIsolatedService(
                    Intent(context, NativeMediaSandboxProbeService::class.java),
                    Context.BIND_AUTO_CREATE,
                    workerInstanceName("native_sandbox", sequence.incrementAndGet()),
                    context.mainExecutor,
                    connection,
                ),
            )
            check(connected.await(5, TimeUnit.SECONDS)) { "Qualification worker did not bind" }
            invoke(checkNotNull(endpoint.get()), callback, mode)
            if (mode == NativeSandboxProbe.BLOCKED_NATIVE) {
                check(callback.terminal.await(2, TimeUnit.SECONDS)) { "No watchdog or failure observation" }
                check(callback.timerSeen.get()) { "Process ended without its independent watchdog observation" }
            }
            callback.failure.get()?.let { throw AssertionError("Qualification callback rejected", it) }
            val pid = callback.pid.get()
            check(pid > 0) { "No kernel-authenticated worker PID" }
            check(
                mode == NativeSandboxProbe.THREADS || callback.armed.get(),
            ) { "Native probe never reached its armed stage" }
            assertTrue("Retirement did not deliver endpoint death", death.await(8, TimeUnit.SECONDS))
            awaitAbsent(pid)
            return Result(pid, callback.rows.get(), startedAtMs)
        } finally {
            try {
                if (bindingAttempted) context.unbindService(connection)
            } finally {
                check(canary.delete()) { "Synthetic canary cleanup failed" }
            }
        }
    }

    private fun invoke(
        endpoint: IBinder,
        callback: IBinder,
        mode: Int,
    ) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(NativeSandboxProbe.DESCRIPTOR)
            data.writeInt(mode)
            data.writeStrongBinder(callback)
            endpoint.transact(NativeSandboxProbe.RUN, data, reply, 0)
        } catch (_: RemoteException) {
            // The disposable worker terminates itself after its synchronous callback or fatal syscall.
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun awaitAbsent(pid: Int) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                Os.kill(pid, 0)
            } catch (failure: ErrnoException) {
                if (failure.errno == OsConstants.ESRCH) return
            }
            SystemClock.sleep(20)
        }
        throw AssertionError("Binder death did not establish OS process disappearance")
    }

    private fun assertSignalExit(
        pid: Int,
        signal: Int,
        startedAtMs: Long,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(ActivityManager::class.java)
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (SystemClock.elapsedRealtime() < deadline) {
            val info = manager.getHistoricalProcessExitReasons(context.packageName, pid, 1).firstOrNull()
            if (info != null && info.timestamp >= startedAtMs) {
                if (info.reason == ApplicationExitInfo.REASON_SIGNALED && info.status == signal) return
                // Native crash reporting may replace REASON_SIGNALED; retain the exact signal check below.
            }
            SystemClock.sleep(20)
        }
        if (hasZygoteSignalExit(pid, signal, startedAtMs)) return
        throw AssertionError("No platform exit reason for the exact qualification worker")
    }

    /** Zygote reports waitpid's exact signal; root-UID filtering excludes app-authored lookalike tags. */
    private fun hasZygoteSignalExit(
        pid: Int,
        signal: Int,
        startedAtMs: Long,
    ): Boolean {
        require(pid > 0 && pid != Process.myPid())
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val command = "logcat -d -b main --uid=0 -v epoch -s Zygote:I '*:S'"
        val descriptor = automation.executeShellCommand(command)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            val bytes = ByteArray(NativeSandboxExitEvidence.MAX_BYTES + 1)
            var used = 0
            while (used < bytes.size) {
                val count = input.read(bytes, used, bytes.size - used)
                if (count <= 0) break
                used += count
            }
            if (used > NativeSandboxExitEvidence.MAX_BYTES) return@use false
            NativeSandboxExitEvidence.matches(
                String(bytes, 0, used, Charsets.UTF_8),
                pid,
                signal,
                startedAtMs,
                System.currentTimeMillis(),
            )
        }
    }

    private companion object {
        val sequence = AtomicInteger()

        fun calibrateAlive(pid: Int): Boolean =
            try {
                Os.kill(pid, 0)
                true
            } catch (failure: ErrnoException) {
                failure.errno == OsConstants.EPERM || failure.errno == OsConstants.EACCES
            }
    }
}
