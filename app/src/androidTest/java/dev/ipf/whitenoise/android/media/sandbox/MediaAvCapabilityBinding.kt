package dev.ipf.whitenoise.android.media.sandbox

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One finite test fixture retains uncertain startup; close cannot manufacture a second generation. */
internal class MediaAvCapabilityBinding(
    private val context: Context,
) : AutoCloseable {
    private val lock = Any()
    private val callbacks = Executors.newSingleThreadExecutor()
    private var selected: AvCapabilityWorker? = null
    private var closed = false

    private companion object {
        const val DEATH_GRACE_MS = 2_000L
    }

    fun run(
        file: File,
        request: Bundle,
    ): JSONObject {
        val started = SystemClock.elapsedRealtime()
        val ceiling =
            if (request.getString("probeStage") == "waveform") {
                MediaDecodePolicy.WAVEFORM_DEADLINE_MS
            } else {
                MediaDecodePolicy.DEADLINE_MS
            }
        val worker =
            synchronized(lock) {
                check(!closed && selected == null) { "Prior worker lifetime is unresolved" }
                AvCapabilityWorker(request.getInt("requestId"), started + ceiling).also { selected = it }
            }
        val connection = connection(worker)
        worker.cleanup = { dispose(worker, connection) }
        try {
            bind(worker, connection)
            val connected = worker.connected.await(worker.remaining(), TimeUnit.MILLISECONDS)
            check(connected) { "Unobserved startup remains held" }
            checkNotNull(worker.remote)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { input ->
                val mode = Os.fcntlInt(input.fileDescriptor, OsConstants.F_GETFL, 0) and OsConstants.O_ACCMODE
                check(mode == OsConstants.O_RDONLY)
                val capacity = checkNotNull(MediaDecodePolicy.outputCapacity(128))
                SharedMemory.create("av-capability", capacity).use { output ->
                    exchange(worker, input, output, request)
                    check(worker.remaining() > 0) { "Reply deadline; lifetime held" }
                    val operationElapsed = SystemClock.elapsedRealtime() - started
                    val retirementStarted = SystemClock.elapsedRealtime()
                    retire(worker)
                    check(worker.dead.await(DEATH_GRACE_MS, TimeUnit.MILLISECONDS)) { "Exact Binder death missing" }
                    val retirementElapsed = SystemClock.elapsedRealtime() - retirementStarted
                    checkNotNull(worker.result).let { result ->
                        AvCapabilityEvidence.validate(result, output)
                        return AvCapabilityEvidence.row(worker.id, result, started, operationElapsed, retirementElapsed)
                    }
                }
            }
        } finally {
            worker.accepting.set(false)
            retire(worker)
            worker.finished.set(true)
            worker.releaseIfFinished()
        }
    }

    private fun bind(
        worker: AvCapabilityWorker,
        connection: ServiceConnection,
    ) {
        val intent = Intent().setClassName(context, "dev.ipf.whitenoise.android.media.sandbox.MediaAvCapabilityService")
        try {
            val instance = workerInstanceName("av", worker.id)
            val flags = Context.BIND_AUTO_CREATE
            val accepted = context.bindIsolatedService(intent, flags, instance, callbacks, connection)
            synchronized(lock) {
                worker.bound = worker.bound || accepted
                worker.definitiveRefusal = !accepted && !worker.bound
            }
            check(accepted) { "Binding refused; no capability result" }
        } finally {
            worker.bindComplete = true
        }
    }

    private fun connection(worker: AvCapabilityWorker): ServiceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName,
                service: IBinder,
            ) {
                synchronized(lock) {
                    if (selected !== worker || worker.remote != null) return
                    worker.bound = true
                    worker.remote = service
                }
                worker.deathRegistered =
                    runCatching {
                        service.linkToDeath(worker.death, 0)
                        true
                    }.getOrDefault(false)
                worker.connected.countDown()
                if (!worker.deathRegistered || !worker.accepting.get()) retire(worker)
            }

            override fun onServiceDisconnected(name: ComponentName) = Unit
        }

    private fun exchange(
        worker: AvCapabilityWorker,
        input: ParcelFileDescriptor,
        output: SharedMemory,
        request: Bundle,
    ) {
        check(worker.deathRegistered) { "No registered exact death witness" }
        request.putInt("version", MediaDecodePolicy.VERSION)
        request.putParcelable("input", input)
        request.putParcelable("output", output)
        check(worker.dead.count != 0L && worker.accepting.get()) { "Worker is already retired" }
        val message =
            Message.obtain(null, MediaDecodePolicy.DECODE, worker.id, 0).apply {
                data = request
                replyTo = Messenger(worker.reply)
            }
        Messenger(checkNotNull(worker.remote)).send(message)
        check(worker.replied.await(worker.remaining(), TimeUnit.MILLISECONDS)) { "Reply deadline; lifetime held" }
        worker.accepting.set(false)
    }

    private fun retire(worker: AvCapabilityWorker) {
        worker.accepting.set(false)
        worker.remote?.let { runCatching { Messenger(it).send(Message.obtain(null, MediaDecodePolicy.RETIRE)) } }
    }

    private fun dispose(
        worker: AvCapabilityWorker,
        connection: ServiceConnection,
    ) {
        runCatching { context.unbindService(connection) }
        worker.remote?.let { runCatching { it.unlinkToDeath(worker.death, 0) } }
        val terminal =
            synchronized(lock) {
                if (selected === worker) selected = null
                closed
            }
        if (terminal) callbacks.shutdown()
    }

    override fun close() {
        val worker =
            synchronized(lock) {
                closed = true
                selected
            }
        if (worker == null) callbacks.shutdown() else retire(worker)
        // An uncertain live generation and its callback executor stay retained; no replacement is permitted.
    }
}

private class AvCapabilityWorker(
    val id: Int,
    private val deadline: Long,
) {
    val connected = CountDownLatch(1)
    val replied = CountDownLatch(1)
    val dead = CountDownLatch(1)
    val accepting = AtomicBoolean(true)
    val finished = AtomicBoolean(false)
    private val disposed = AtomicBoolean(false)
    var cleanup: (() -> Unit)? = null

    @Volatile var bindComplete = false
    val death =
        IBinder.DeathRecipient {
            dead.countDown()
            releaseIfFinished()
        }

    @Volatile var remote: IBinder? = null

    @Volatile var bound = false

    @Volatile var definitiveRefusal = false

    @Volatile var deathRegistered = false

    @Volatile var result: Bundle? = null
    val reply =
        object : Binder() {
            override fun onTransact(
                code: Int,
                data: Parcel,
                response: Parcel?,
                flags: Int,
            ): Boolean =
                when {
                    code != IBinder.FIRST_CALL_TRANSACTION -> super.onTransact(code, data, response, flags)
                    !accepting.get() || data.dataAvail() > MediaDecodePolicy.MAX_REPLY_BYTES -> false
                    else -> accept(data)
                }

            private fun accept(data: Parcel): Boolean {
                val parsed =
                    try {
                        data.enforceInterface(MEDIA_REPLY_DESCRIPTOR)
                        require(data.readInt() == id)
                        readMediaDecodeReply(data)
                    } catch (_: RuntimeException) {
                        return false
                    }
                parsed.putInt("uid", Binder.getCallingUid())
                return if (accepting.compareAndSet(true, false)) {
                    result = parsed
                    replied.countDown()
                    true
                } else {
                    false
                }
            }
        }

    fun releaseIfFinished() {
        val proven = dead.count == 0L || definitiveRefusal
        val ready = finished.get() && bindComplete && proven
        if (ready && disposed.compareAndSet(false, true)) cleanup?.invoke()
    }

    fun remaining(): Long = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)
}

/** Consume only the bounded primitive reply and copy pixels after the caller's exact-death barrier. */
private object AvCapabilityEvidence {
    fun validate(
        result: Bundle,
        output: SharedMemory,
    ) {
        val uid = result.getInt("uid")
        check(uid > 0 && uid != android.os.Process.myUid())
        check(result.getBoolean("isolated") && !result.getBoolean("internetGranted"))
        check(!result.getBoolean("runtimeInitialized") && result.getBoolean("privateCanaryDenied"))
        check(result.getInt("inputWriteErrno") == OsConstants.EBADF)
        if (result.getBoolean("pixels")) {
            val width = result.getInt("width")
            val height = result.getInt("height")
            val count = checkNotNull(MediaDecodePolicy.pixelBytes(width, height, 128, output.size))
            val pixels = output.mapReadOnly()
            try {
                pixels.limit(count)
                val config = android.graphics.Bitmap.Config.ARGB_8888
                val bitmap = android.graphics.Bitmap.createBitmap(width, height, config)
                try {
                    bitmap.copyPixelsFromBuffer(pixels)
                } finally {
                    bitmap.recycle()
                }
            } finally {
                SharedMemory.unmap(pixels)
            }
        }
    }

    fun row(
        id: Int,
        result: Bundle,
        started: Long,
        operationElapsed: Long,
        retirementElapsed: Long,
    ): JSONObject =
        JSONObject()
            .put("request", id)
            .put("kernel_reply_uid", result.getInt("uid"))
            .put("success", result.getBoolean("success"))
            .put("death_observed_before_output_read", true)
            .put("elapsed_ms", SystemClock.elapsedRealtime() - started)
            .put("operation_elapsed_ms", operationElapsed)
            .put("retirement_elapsed_ms", retirementElapsed)
            .put("pixels", result.getBoolean("pixels"))
            .put("duration_ms", result.getLong("duration"))
            .put("waveform_bars", result.getFloatArray("waveform")?.size ?: 0)
            .put("input_write_errno", result.getInt("inputWriteErrno"))
}
