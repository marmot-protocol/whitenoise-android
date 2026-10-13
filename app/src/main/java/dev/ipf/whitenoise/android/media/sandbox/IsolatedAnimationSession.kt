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
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** One outstanding frame and one actual isolated process across account generations. */
@Suppress("TooManyFunctions") // One capability/binding lifetime, never a second decode or image cache.
internal class IsolatedAnimationSession private constructor(
    private val context: Context,
    private val edge: Int,
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
            if (closed ||
                frame.session != id ||
                frame.sequence != sequence + 1L ||
                uid == Process.myUid() ||
                (workerUid != null && workerUid != uid) ||
                !frame.isolated ||
                frame.internetGranted ||
                frame.runtimeInitialized
            ) {
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
            try {
                pixels.limit(count)
                check(bitmap.byteCount == count)
                bitmap.copyPixelsFromBuffer(pixels)
                return bitmap
            } catch (failure: RuntimeException) {
                bitmap.recycle()
                throw failure
            } catch (failure: OutOfMemoryError) {
                bitmap.recycle()
                throw failure
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
            bound =
                context.bindIsolatedService(
                    Intent(context, MediaAnimationService::class.java),
                    Context.BIND_AUTO_CREATE,
                    workerInstanceName("animation", id),
                    { command -> callbackHandler.post(command) },
                    connection,
                )
        }
        if (!bound) {
            releaseLease()
            return null
        }
        if (!connected.await(MediaDecodePolicy.DEADLINE_MS, TimeUnit.MILLISECONDS) || closed) return null
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
        if (!firstFrame.await(MediaDecodePolicy.DEADLINE_MS, TimeUnit.MILLISECONDS)) return null
        return synchronized(lock) {
            if (closed) {
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
        if (leaseReleased.compareAndSet(false, true)) admission.release()
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
        private val admission = Semaphore(1)
        private val active =
            java.util.concurrent.atomic
                .AtomicReference<IsolatedAnimationSession?>()
        private val callbackHandler by lazy { Handler(HandlerThread("animation-ipc").apply { start() }.looper) }

        fun create(
            context: Context,
            bytes: ByteArray,
            edge: Int,
        ): IsolatedAnimationDrawable? {
            if (Looper.myLooper() == Looper.getMainLooper() ||
                bytes.size !in 1..MediaDecodePolicy.MAX_INPUT_BYTES ||
                edge !in 1..AnimationFrameProtocol.MAX_EDGE ||
                !admission.tryAcquire(MediaDecodePolicy.DEADLINE_MS, TimeUnit.MILLISECONDS)
            ) {
                return null
            }
            val session =
                try {
                    IsolatedAnimationSession(context.applicationContext, edge)
                } catch (_: Exception) {
                    admission.release()
                    return null
                } catch (_: OutOfMemoryError) {
                    admission.release()
                    return null
                }
            active.set(session)
            return try {
                session.open(bytes).also { if (it == null) session.close() }
            } catch (
                interrupted: InterruptedException,
            ) {
                session.close()
                throw interrupted
            } catch (_: Exception) {
                session.close()
                null
            } catch (_: OutOfMemoryError) {
                session.close()
                null
            }
        }

        fun retireActive() {
            active.get()?.close()
        }
    }
}
