package dev.ipf.whitenoise.android.media.sandbox

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Parcel
import android.os.Process
import android.os.SharedMemory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** One outstanding frame and one actual isolated process across account generations. */
@Suppress("TooManyFunctions") // One capability/binding lifetime, never a second decode or image cache.
internal class IsolatedAnimationSession private constructor(
    private val context: Context,
    private val edge: Int,
    private val admissionLease: AnimationAdmission.Lease,
) : AnimationSessionControl {
    private val id = ids.incrementAndGet()
    private val output = SharedMemory.create("animation-pixels", requireNotNull(MediaDecodePolicy.outputCapacity(edge)))
    private val lock = Any()
    private val connected = CountDownLatch(1)
    private val firstFrame = CountDownLatch(1)
    private val leaseReleased = AtomicBoolean()
    private val delivering = AtomicBoolean()
    private val main = Handler(Looper.getMainLooper())
    private val sources = MediaSourceAccess(context)
    private var bound = false

    @Volatile private var remote: Messenger? = null

    @Volatile private var closed = false
    private var sequence = 0L
    private var workerUid: Int? = null
    private var firstPixels: Bitmap? = null
    private var drawable: IsolatedAnimationDrawable? = null

    private val callback = Messenger(FrameReceiver())

    private inner class FrameReceiver : Binder() {
        @Suppress("ReturnCount", "TooGenericExceptionCaught") // Untrusted IPC failures retire only their capability.
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != IBinder.FIRST_CALL_TRANSACTION || closed || !delivering.compareAndSet(false, true)) return false
            var posted = false
            try {
                val frame = readAnimationFrame(data)
                val delivery = captureFrame(frame, Binder.getCallingUid()) ?: return false
                val bitmap = delivery.bitmap
                if (bitmap == null) {
                    delivering.set(false)
                    send(AnimationFrameProtocol.ACK, frame.sequence)
                    return true
                }
                posted = true
                main.post {
                    try {
                        if (closed) bitmap.recycle() else drawable?.accept(bitmap) ?: bitmap.recycle()
                    } finally {
                        delivering.set(false)
                        send(AnimationFrameProtocol.ACK, frame.sequence)
                    }
                }
                return true
            } catch (_: Exception) {
                close()
                return false
            } catch (_: OutOfMemoryError) {
                close()
                return false
            } finally {
                if (!posted) delivering.set(false)
            }
        }
    }

    private data class Delivery(
        val bitmap: Bitmap?,
    )

    private fun captureFrame(
        frame: AnimationFrameInfo,
        uid: Int,
    ): Delivery? =
        synchronized(lock) {
            val current = !closed && frame.session == id && frame.sequence == sequence + 1L
            val isolated = frame.isolated && !frame.internetGranted && !frame.runtimeInitialized
            val kernelWorker = uid != Process.myUid() && (workerUid == null || workerUid == uid)
            if (!current || !isolated || !kernelWorker) {
                return@synchronized null
            }
            val count =
                MediaDecodePolicy.pixelBytes(frame.width, frame.height, edge, output.size)
                    ?: return@synchronized null
            val bitmap = copyFrame(frame, count)
            workerUid = uid
            sequence = frame.sequence
            recordIsolation(frame, uid)
            if (drawable == null) {
                if (firstPixels != null) {
                    bitmap.recycle()
                    return@synchronized null
                }
                firstPixels = bitmap
                firstFrame.countDown()
                Delivery(null)
            } else {
                Delivery(bitmap)
            }
        }

    private fun copyFrame(
        frame: AnimationFrameInfo,
        count: Int,
    ): Bitmap {
        val pixels = output.mapReadOnly()
        try {
            val bitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
            var copied = false
            try {
                pixels.limit(count)
                check(bitmap.byteCount == count)
                bitmap.copyPixelsFromBuffer(pixels)
                copied = true
                return bitmap
            } finally {
                if (!copied) bitmap.recycle()
            }
        } finally {
            SharedMemory.unmap(pixels)
        }
    }

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName,
                service: IBinder,
            ) {
                try {
                    service.linkToDeath({ workerDied() }, 0)
                } catch (_: android.os.RemoteException) {
                    workerDied()
                    return
                }
                synchronized(lock) { remote = Messenger(service) }
                connected.countDown()
                if (closed) retireAndUnbind()
            }

            override fun onServiceDisconnected(name: ComponentName) = bindingUnavailable()

            override fun onBindingDied(name: ComponentName) = bindingUnavailable()

            override fun onNullBinding(name: ComponentName) {
                // A null binding cannot have received OPEN or any source capability.
                releaseLease()
                close()
            }
        }

    private fun open(bytes: ByteArray): IsolatedAnimationDrawable? {
        synchronized(lock) {
            if (closed) {
                releaseLease() // No binding was started.
                return null
            }
            bound =
                context.bindIsolatedService(
                    Intent(context, MediaAnimationService::class.java),
                    Context.BIND_AUTO_CREATE,
                    workerInstanceName("animation", id),
                    { command -> callbackHandler.post(command) },
                    connection,
                )
        }
        val available =
            if (bound) {
                connected.await(MediaDecodePolicy.DEADLINE_MS, TimeUnit.MILLISECONDS) && !closed
            } else {
                releaseLease()
                false
            }
        return if (available) {
            sources.withInput(bytes) { input ->
                remote?.send(
                    Message.obtain().apply {
                        what = AnimationFrameProtocol.OPEN
                        arg1 = id
                        replyTo = this@IsolatedAnimationSession.callback
                        data =
                            android.os.Bundle().apply {
                                putInt("edge", edge)
                                putParcelable("input", input)
                                putParcelable("output", output)
                                if (dev.ipf.whitenoise.android.BuildConfig.DEBUG) {
                                    val canary = java.io.File(context.filesDir, "media-containment-canary")
                                    if (canary.isFile) putString("canaryPath", canary.absolutePath)
                                }
                            }
                    },
                )
            }
            val received = firstFrame.await(MediaDecodePolicy.DEADLINE_MS, TimeUnit.MILLISECONDS)
            synchronized(lock) {
                if (closed || !received) {
                    null
                } else {
                    firstPixels?.let { pixels ->
                        IsolatedAnimationDrawable(pixels, this).also {
                            drawable = it
                            firstPixels = null
                        }
                    }
                }
            }
        } else {
            null
        }
    }

    override fun play() = send(AnimationFrameProtocol.PLAY)

    override fun pause() = send(AnimationFrameProtocol.PAUSE)

    override fun isOpen(): Boolean = !closed

    private fun send(
        command: Int,
        ack: Long = 0L,
    ) {
        if (closed) return
        try {
            remote?.send(
                Message.obtain().apply {
                    what = command
                    arg1 = id
                    if (ack > 0L) data = android.os.Bundle().apply { putLong("sequence", ack) }
                },
            )
        } catch (_: android.os.RemoteException) {
            close()
        }
    }

    private fun recordIsolation(
        frame: AnimationFrameInfo,
        uid: Int,
    ) {
        IsolatedMediaDecoder.recordIsolation(
            android.os.Bundle().apply {
                putInt("uid", uid)
                putBoolean("isolated", frame.isolated)
                putBoolean("internetGranted", frame.internetGranted)
                putBoolean("runtimeInitialized", frame.runtimeInitialized)
                putInt("privateCanaryErrno", frame.privateCanaryErrno)
                putBoolean(
                    "privateCanaryDenied",
                    frame.privateCanaryErrno in
                        listOf(
                            android.system.OsConstants.EACCES,
                            android.system.OsConstants.EPERM,
                            android.system.OsConstants.ENOENT,
                        ),
                )
            },
        )
    }

    private fun workerDied() {
        releaseLease() // Only system/Binder-confirmed death reopens process admission.
        close()
        main.post { drawable?.stop() }
    }

    private fun bindingUnavailable() {
        if (remote?.binder?.isBinderAlive == false) {
            workerDied()
        } else {
            close() // Unbinding/connection loss alone is not proof of native process death.
        }
    }

    private fun releaseLease() {
        if (leaseReleased.compareAndSet(false, true)) admissionLease.release()
        connected.countDown()
        firstFrame.countDown()
    }

    private fun retireAndUnbind() {
        runCatching { remote?.send(Message.obtain().apply { what = MediaDecodePolicy.RETIRE }) }
        synchronized(lock) {
            if (bound && (remote != null || leaseReleased.get())) {
                runCatching { context.unbindService(connection) }
                bound = false
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            firstPixels?.recycle()
            firstPixels = null
            output.close()
        }
        sources.close()
        active.compareAndSet(this, null)
        connected.countDown()
        firstFrame.countDown()
        retireAndUnbind()
        // If startup is late, its callback retires the same process. Never admit a replacement
        // on timeout/unbind alone or fabricate a death acknowledgement.
    }

    companion object {
        private val ids = AtomicInteger()
        private val admission = AnimationAdmission()
        private val active =
            java.util.concurrent.atomic
                .AtomicReference<IsolatedAnimationSession?>()
        private val callbackHandler by lazy { Handler(HandlerThread("animation-ipc").apply { start() }.looper) }

        fun create(
            context: Context,
            bytes: ByteArray,
            edge: Int,
            decorative: Boolean = false,
        ): IsolatedAnimationDrawable? {
            val validSource =
                bytes.size in 1..MediaDecodePolicy.MAX_INPUT_BYTES &&
                    edge in 1..AnimationFrameProtocol.MAX_EDGE
            val admissionLease =
                if (Looper.myLooper() == Looper.getMainLooper() || !validSource) {
                    null
                } else {
                    admission.acquire(decorative, MediaDecodePolicy.DEADLINE_MS)
                }
            if (admissionLease == null) return null
            var session: IsolatedAnimationSession? = null
            var delivered: IsolatedAnimationDrawable? = null
            return try {
                val created = IsolatedAnimationSession(context.applicationContext, edge, admissionLease)
                session = created
                active.set(created)
                admissionLease.onRetire { created.close() }
                created.open(bytes).also { delivered = it }
            } finally {
                // The facade handles failure/cancellation; this scope only owns cleanup.
                if (delivered == null) {
                    if (session == null) admissionLease.release() else session.close()
                }
            }
        }

        /** Test fixture barrier: borrow admission after confirmed retirement without starting a binding. */
        internal fun awaitIdleForTest(): Boolean {
            check(Looper.myLooper() != Looper.getMainLooper())
            val lease = admission.acquire(decorative = false, MediaDecodePolicy.DEADLINE_MS) ?: return false
            lease.release() // This fixture lease never started a binding or received source bytes.
            return true
        }

        fun retireActive() {
            active.get()?.close()
        }
    }
}
