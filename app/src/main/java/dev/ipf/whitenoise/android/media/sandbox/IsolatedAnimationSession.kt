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
import dev.ipf.whitenoise.android.media.AnimationStartupException
import dev.ipf.whitenoise.android.media.AnimationTermination
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val inputStaged: () -> Unit = {},
    private val awaitSignal: (CountDownLatch, Long) -> Boolean = { signal, remaining ->
        signal.await(remaining, TimeUnit.NANOSECONDS)
    },
) : AnimationSessionControl {
    private val viewerPreemption = MutableStateFlow(false)
    private val terminalReason = MutableStateFlow<AnimationTermination?>(null)
    override val termination = terminalReason.asStateFlow()

    /** Observers may queue a replacement after viewer takeover, never manufacture worker death. */
    override val preempted = viewerPreemption.asStateFlow()

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
    private var bindingAttempted = false

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
            if (code == AnimationFrameProtocol.STATUS_TRANSACTION) return receiveStatus(data, Binder.getCallingUid())
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
                closeWithReason(AnimationTermination.ProtocolRejected)
                return false
            } catch (_: OutOfMemoryError) {
                closeWithReason(AnimationTermination.OwnerClosed) // Host pixel allocation failure is not worker loss.
                return false
            } finally {
                if (!posted) delivering.set(false)
            }
        }
    }

    /** Rejection authenticates the same session/UID/isolation as frames; it cannot release admission. */
    private fun receiveStatus(
        data: Parcel,
        uid: Int,
    ): Boolean {
        val status =
            try {
                readAnimationStatus(data)
            } catch (_: Exception) {
                return false
            }
        val accepted =
            synchronized(lock) {
                val owner = !closed && status.session == id
                val kernelWorker = uid != Process.myUid() && (workerUid == null || workerUid == uid)
                val isolated = status.isolated && !status.internetGranted && !status.runtimeInitialized
                if (!owner || !kernelWorker || !isolated) {
                    false
                } else {
                    workerUid = uid
                    if (terminalReason.value == null) terminalReason.value = AnimationTermination.CodecRejected
                    true
                }
            }
        if (accepted) closeWithReason(AnimationTermination.CodecRejected)
        return accepted
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
                // A null callback proves no OPEN reached this binding. It also completes cancellation cleanup.
                closeWithReason(AnimationTermination.TransportUnavailable)
                releaseLease()
                retireAndUnbind()
            }
        }

    /** Stages only while the original admission budget and capability are both current. */
    private fun open(bytes: ByteArray): IsolatedAnimationDrawable? {
        val acceptedBinding = bindWorker()
        if (closed) retireAndUnbind()
        val available = acceptedBinding && awaitStartup(connected) && startupAvailable()
        if (!available) {
            closeWithReason(AnimationTermination.TransportUnavailable)
            if (!acceptedBinding) releaseLease() // Immutable platform refusal, never mutable unbound state.
            return null
        }
        val sent = stageAndOpen(bytes)
        val received = sent && awaitStartup(firstFrame) && startupAvailable()
        if (sent && !received) closeWithReason(AnimationTermination.TransportUnavailable)
        return synchronized(lock) {
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
    }

    /**
     * Binds this session's own isolated worker process while startup is still live, returning whether the platform
     * accepted it. A synchronous platform refusal releases admission, since no capability was ever granted.
     */
    private fun bindWorker(): Boolean =
        synchronized(lock) {
            if (closed || !startupAvailable()) return@synchronized false
            bindingAttempted = true
            val accepted =
                try {
                    context.bindIsolatedService(
                        Intent(context, MediaAnimationService::class.java),
                        Context.BIND_AUTO_CREATE,
                        workerInstanceName("animation", id),
                        { command -> callbackHandler.post(command) },
                        connection,
                    )
                } catch (refused: SecurityException) {
                    releaseLease() // Platform rejected the binding before accepting a capability.
                    throw refused
                } catch (refused: IllegalArgumentException) {
                    releaseLease()
                    throw refused
                }
            bound = accepted
            accepted
        }

    /**
     * Stages [bytes] as an unlinked read-only descriptor and sends OPEN while the budget lasts, returning whether OPEN
     * was sent. A Binder failure retires the session as transport loss rather than worker death.
     */
    private fun stageAndOpen(bytes: ByteArray): Boolean =
        try {
            if (!startupAvailable()) {
                false
            } else {
                sources.withInput(bytes) { input ->
                    inputStaged()
                    sendOpen(input)
                } == true
            }
        } catch (_: android.os.RemoteException) {
            closeWithReason(AnimationTermination.TransportUnavailable)
            false
        }

    /** Expiry retires a bound capability but never fabricates process death. */
    private fun startupAvailable(): Boolean {
        if (closed) return false
        if (Thread.currentThread().isInterrupted) throw InterruptedException("animation owner cancelled")
        val available = admissionLease.remainingNanos() != 0L
        if (!available) closeWithReason(AnimationTermination.TransportUnavailable)
        return available
    }

    /**
     * Waits on a startup latch for at most the lease's remaining budget. Close and lease release also trip the
     * latches, so callers recheck [startupAvailable] after a true return.
     */
    private fun awaitStartup(signal: CountDownLatch): Boolean {
        if (!startupAvailable()) return false
        return awaitSignal(signal, admissionLease.remainingNanos())
    }

    /**
     * Serializes OPEN with [close], so retirement cannot close shared memory during parcel marshalling.
     * A retired startup returns false; unrelated source/IPC failures still escape without a retry.
     */
    private fun sendOpen(input: android.os.ParcelFileDescriptor): Boolean =
        synchronized(lock) {
            if (!startupAvailable()) return@synchronized false
            val target = remote?.takeUnless { closed } ?: return@synchronized false
            val message =
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
                }
            if (!startupAvailable()) return@synchronized false
            target.send(message)
            true
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
            closeWithReason(AnimationTermination.TransportUnavailable)
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
        closeWithReason(AnimationTermination.WorkerLostUnknown)
        releaseLease() // Only system/Binder-confirmed death reopens process admission.
        retireAndUnbind() // A dead Binder may never have been assigned to remote.
        main.post { drawable?.stop() }
    }

    private fun bindingUnavailable() {
        if (remote?.binder?.isBinderAlive == false) {
            workerDied()
        } else {
            closeWithReason(AnimationTermination.TransportUnavailable) // Connection loss is not proof of death.
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

    /** Owner close retires the worker, admission stays held until its death is confirmed. */
    override fun close() = closeWithReason(AnimationTermination.OwnerClosed)

    /**
     * Records the first terminal reason, then idempotently frees pending pixels and shared memory, clears the active
     * slot, trips startup latches and asks the worker to retire. It never releases admission.
     */
    private fun closeWithReason(reason: AnimationTermination) {
        synchronized(lock) {
            if (terminalReason.value == null) terminalReason.value = reason
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

    /** Retires this decorative session before allowing its still-visible owner to queue again. */
    private fun yieldToViewer() {
        closeWithReason(AnimationTermination.Preempted)
        viewerPreemption.value = true
    }

    companion object {
        private val ids = AtomicInteger()
        private val admission = AnimationAdmission()
        private val active =
            java.util.concurrent.atomic
                .AtomicReference<IsolatedAnimationSession?>()
        private val callbackHandler by lazy { Handler(HandlerThread("animation-ipc").apply { start() }.looper) }

        /** Ordinary animation callers retain the existing nullable startup contract. */
        fun create(
            context: Context,
            bytes: ByteArray,
            edge: Int,
            decorative: Boolean = false,
            waitForDecorativeAdmission: Boolean = false,
        ): IsolatedAnimationDrawable? =
            if (decorative && waitForDecorativeAdmission) {
                createGroup(context, bytes, edge, null)
            } else {
                createAdmitted(context, bytes, edge, decorative, null)
            }

        /** Only the existing group seam carries a persistent pending ticket. */
        fun createGroup(
            context: Context,
            bytes: ByteArray,
            edge: Int,
            demand: AnimationAdmission.GroupDemand?,
        ): IsolatedAnimationDrawable? {
            if (!validSource(bytes, edge)) return null
            val ownedDemand = if (demand == null) admission.groupDemand() else null
            return try {
                createAdmitted(context, bytes, edge, true, demand ?: ownedDemand)
            } finally {
                ownedDemand?.close()
            }
        }

        /** Accepts only off-main-thread calls with a non-empty source under the input cap and an edge in range. */
        private fun validSource(
            bytes: ByteArray,
            edge: Int,
        ): Boolean =
            Looper.myLooper() != Looper.getMainLooper() &&
                bytes.size in 1..MediaDecodePolicy.MAX_INPUT_BYTES &&
                edge in 1..AnimationFrameProtocol.MAX_EDGE

        /**
         * Runs [openAdmitted] under admission with one total startup deadline. A non-null [demand] marks a waiting
         * group thumbnail, which admission requeues and retries after preemption.
         */
        private fun createAdmitted(
            context: Context,
            bytes: ByteArray,
            edge: Int,
            decorative: Boolean,
            demand: AnimationAdmission.GroupDemand?,
        ): IsolatedAnimationDrawable? {
            if (!validSource(bytes, edge)) return null
            return admission.decode(decorative, demand != null, MediaDecodePolicy.DEADLINE_MS, demand) { lease ->
                openAdmitted(context, bytes, edge, lease)
            }
        }

        /** Owns one startup attempt; a preempted null result is retried by admission after retirement. */
        private fun openAdmitted(
            context: Context,
            bytes: ByteArray,
            edge: Int,
            admissionLease: AnimationAdmission.Lease,
        ): IsolatedAnimationDrawable? {
            var session: IsolatedAnimationSession? = null
            var delivered: IsolatedAnimationDrawable? = null
            return try {
                val created = IsolatedAnimationSession(context.applicationContext, edge, admissionLease)
                session = created
                active.set(created)
                admissionLease.onRetire { created.yieldToViewer() }
                created.open(bytes).also {
                    delivered = it
                    if (it == null) {
                        when (val reason = created.terminalReason.value) {
                            AnimationTermination.TransportUnavailable, AnimationTermination.WorkerLostUnknown ->
                                throw AnimationStartupException(reason)
                            else -> Unit
                        }
                    }
                }
            } finally {
                // The facade handles failure/cancellation; this scope only owns cleanup.
                if (delivered == null) {
                    if (session == null) {
                        admissionLease.release()
                    } else {
                        session.close()
                        synchronized(session.lock) {
                            if (!session.bindingAttempted) session.releaseLease() // Cancelled before bind.
                        }
                    }
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

        /** A persistent, already queued FIFO ticket for a mounted group-thumbnail owner, closed on unmount. */
        fun groupDemand(): AnimationAdmission.GroupDemand = admission.groupDemand()

        fun retireActive() {
            active.get()?.close()
        }
    }
}
