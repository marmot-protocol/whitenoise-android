@file:Suppress("MagicNumber") // Fixed debug protocol shapes and bounded qualification timeouts.

package dev.ipf.whitenoise.android.media.sandbox

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Disposable debug qualification only. No source descriptor, URL or account input is accepted. */
class NativeMediaSandboxProbeService : Service() {
    private val watchdog = Executors.newSingleThreadScheduledExecutor()
    private var admitted = false
    private val endpoint =
        object : Binder() {
            @Suppress("ReturnCount") // Reject non-isolated, malformed and repeated qualification capabilities.
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (!Process.isIsolated() || code != NativeSandboxProbe.RUN || admitted) return false
                if (flags != 0 || reply == null || data.dataSize() > NativeSandboxProbe.MAX_BYTES) return false
                data.enforceInterface(NativeSandboxProbe.DESCRIPTOR)
                val mode = data.readInt()
                val callback = data.readStrongBinder() ?: return false
                if (mode !in NativeSandboxProbe.THREADS..NativeSandboxProbe.BLOCKED_NATIVE ||
                    data.dataAvail() != 0 ||
                    data.hasFileDescriptors()
                ) {
                    return false
                }
                admitted = true
                watchdog.schedule({
                    try {
                        send(callback, NativeSandboxProbe.TIMED_OUT, mode, emptyList(), oneWay = true)
                    } finally {
                        Process.killProcess(Process.myPid())
                    }
                }, 6L, TimeUnit.SECONDS)
                try {
                    // Synchronous callback lets the parent capture kernel UID/TGID before native loading.
                    send(callback, NativeSandboxProbe.IDENTITY, mode, listOf(capabilities()))
                    if (mode == NativeSandboxProbe.THREADS) {
                        probeThreads(callback, mode)
                    } else if (mode == NativeSandboxProbe.BLOCKED_NATIVE) {
                        blockedRead(callback, mode)
                    } else {
                        NativeMediaEngine.ensureLoaded()
                        send(callback, NativeSandboxProbe.ARMED, mode, emptyList())
                        val errno = NativeMediaEngine.nativeSandboxClone3Errno()
                        send(callback, NativeSandboxProbe.RESULT, mode, listOf(longArrayOf(errno.toLong())))
                    }
                } finally {
                    Process.killProcess(Process.myPid())
                }
                return true
            }
        }

    override fun onBind(intent: Intent): IBinder? = if (Process.isIsolated()) endpoint else null

    @Suppress("TooGenericExceptionCaught") // Every unexpected return/error must fail the watchdog qualification.
    private fun blockedRead(callback: IBinder, mode: Int) {
        NativeMediaEngine.ensureLoaded()
        try {
            val pipe = android.os.ParcelFileDescriptor.createPipe()
            pipe[0].use { read ->
                pipe[1].use {
                    send(callback, NativeSandboxProbe.ARMED, mode, emptyList())
                    val count = android.system.Os.read(read.fileDescriptor, ByteArray(1), 0, 1)
                    send(callback, NativeSandboxProbe.FAILED, mode, listOf(longArrayOf(1, count.toLong())))
                }
            }
        } catch (failure: Throwable) {
            send(callback, NativeSandboxProbe.FAILED, mode, listOf(longArrayOf(2)))
            throw failure
        }
    }

    private fun capabilities(): LongArray {
        val denied =
            try {
                android.system.Os
                    .open(
                        applicationInfo.dataDir + "/files/media-containment-canary",
                        android.system.OsConstants.O_RDONLY,
                        0,
                    ).let { android.system.Os.close(it) }
                0
            } catch (failure: android.system.ErrnoException) {
                failure.errno
            }
        val app = application as dev.ipf.whitenoise.android.WhiteNoiseApplication
        return longArrayOf(
            if (Process.isIsolated()) 1 else 0,
            if (checkSelfPermission(android.Manifest.permission.INTERNET) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                1
            } else {
                0
            },
            if (app.initializedAppState() != null) 1 else 0,
            denied.toLong(),
        )
    }

    private fun probeThreads(
        callback: IBinder,
        mode: Int,
    ) {
        val released = CountDownLatch(1)
        val before = AtomicReference<LongArray?>()
        val existing =
            Thread {
                if (released.await(5L, TimeUnit.SECONDS)) before.set(NativeMediaEngine.nativeSandboxDiagnostics())
            }.apply {
                name = "sandbox-before-install"
                start()
            }
        try {
            NativeMediaEngine.ensureLoaded()
            val installer = NativeMediaEngine.nativeSandboxDiagnostics()
            released.countDown()
            existing.join(2000L)
            check(!existing.isAlive) { "Preexisting thread did not complete" }
            val after = AtomicReference<LongArray?>()
            val created =
                Thread { after.set(NativeMediaEngine.nativeSandboxDiagnostics()) }
                    .apply {
                        name = "sandbox-after-install"
                        start()
                    }
            created.join(2000L)
            check(!created.isAlive) { "New thread did not complete" }
            send(
                callback,
                NativeSandboxProbe.RESULT,
                mode,
                listOf(installer, checkNotNull(before.get()), checkNotNull(after.get())),
            )
        } finally {
            released.countDown()
        }
    }

    private fun send(
        callback: IBinder,
        kind: Int,
        mode: Int,
        rows: List<LongArray>,
        oneWay: Boolean = false,
    ) {
        require(rows.size <= 3 && rows.all { it.size <= NativeSandboxProbe.DIAGNOSTIC_FIELDS })
        val data = Parcel.obtain()
        val response = if (oneWay) null else Parcel.obtain()
        try {
            data.writeInterfaceToken(NativeSandboxProbe.DESCRIPTOR)
            data.writeInt(kind)
            data.writeInt(mode)
            data.writeInt(rows.size)
            rows.forEach { row ->
                data.writeInt(row.size)
                row.forEach(data::writeLong)
            }
            check(
                callback.transact(
                    NativeSandboxProbe.CALLBACK,
                    data,
                    response,
                    if (oneWay) IBinder.FLAG_ONEWAY else 0,
                ),
            )
        } finally {
            response?.recycle()
            data.recycle()
        }
    }

    override fun onDestroy() {
        watchdog.shutdownNow()
        super.onDestroy()
    }
}

internal object NativeSandboxProbe {
    const val DESCRIPTOR = "dev.ipf.whitenoise.native.sandbox.probe.v1"
    const val RUN = IBinder.FIRST_CALL_TRANSACTION
    const val CALLBACK = IBinder.FIRST_CALL_TRANSACTION
    const val IDENTITY = 1
    const val RESULT = 2
    const val ARMED = 3
    const val TIMED_OUT = 4
    const val FAILED = 5
    const val THREADS = 1
    const val CLONE3 = 2
    const val BLOCKED_NATIVE = 3
    const val DIAGNOSTIC_FIELDS = 9
    const val MAX_BYTES = 1024
}
