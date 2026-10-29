package dev.ipf.whitenoise.android.media.sandbox

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SharedMemory
import android.system.OsConstants
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal interface MediaDecodeTransport {
    fun decode(
        bytes: ByteArray,
        edge: Int,
        oriented: Boolean,
        operation: Int,
    ): Bitmap?

    fun exif(bytes: ByteArray): Int? = inspect(bytes)?.orientation

    fun inspect(bytes: ByteArray): ImageInspection?

    fun region(
        bytes: ByteArray,
        region: MediaImageRegion,
        edge: Int,
    ): Bitmap? = null

    fun animation(
        bytes: ByteArray,
        edge: Int,
    ): android.graphics.drawable.Drawable? = null

    fun probe(
        file: File,
        edge: Int,
        poster: Boolean,
    ): MediaProbe? = null

    fun decodeUri(
        resolver: android.content.ContentResolver,
        uri: android.net.Uri,
        edge: Int,
    ): Bitmap? = null

    fun probeUri(
        resolver: android.content.ContentResolver,
        uri: android.net.Uri,
        edge: Int,
    ): MediaProbe? = null

    fun probeBytes(
        bytes: ByteArray,
        edge: Int,
        poster: Boolean,
    ): MediaProbe? = null

    fun waveform(file: File): FloatArray? = null
}

/** One serialized image lane; each admitted input retains its worker until exact Binder death. */
@Suppress("TooManyFunctions") // Cohesive binding, admission and descriptor lifecycle for one decoder lane.
internal class IsolatedMediaTransport(
    private val context: Context,
    private val instanceName: String = "images",
) : MediaDecodeTransport,
    java.io.Closeable {
    private val gate = Semaphore(1)
    private val bindingLock = Any()
    private val ids = AtomicInteger()
    private var epoch = 0L

    @Volatile private var session: Session? = Session(epoch, MediaSourceAccess(context))

    @Volatile private var closed = false

    @Volatile private var requestDispatched = false

    @Volatile private var pending: Reply? = null
    private var worker: Worker? = null
    private val callbackThread = lazy { HandlerThread("media-decoder-ipc").apply { start() } }
    private val callbacks by callbackThread
    private val handler by lazy { Handler(callbacks.looper) }
    private val callbackExecutor = java.util.concurrent.Executor { command -> handler.post(command) }

    /** Measurement-only queue observer, used by matched lane characterization. */
    @Volatile internal var admissionObserver: MediaAdmissionObserver? = null

    /** Account invalidation never discards the process-death witness. */
    internal fun activateSession() = replaceSession(true)

    internal fun invalidateSession() = replaceSession(false)

    private fun replaceSession(active: Boolean) {
        val (previous, retiringWorker) =
            synchronized(bindingLock) {
                check(!closed || !active) { "A closed decoder cannot be reactivated" }
                val previous = session
                val retiringWorker = worker
                session = if (active) Session(++epoch, MediaSourceAccess(context)) else null
                revokePending()
                previous to retiringWorker
            }
        previous?.sources?.close()
        retiringWorker?.let(::retire)
    }

    private fun revokePending() {
        pending?.let {
            it.completed.set(true)
            it.result = null
            it.ready.countDown()
            it.connected.countDown()
        }
        pending = null
    }

    private fun current(ticket: Operation): Boolean = !closed && session?.epoch == ticket.session.epoch

    private fun responseCapability(reply: Reply): Messenger =
        Messenger(
            object : android.os.Binder() {
                @Suppress("ReturnCount") // Reject unrelated, oversized or retired callback capabilities before parsing.
                override fun onTransact(
                    code: Int,
                    data: android.os.Parcel,
                    response: android.os.Parcel?,
                    flags: Int,
                ): Boolean {
                    if (code != IBinder.FIRST_CALL_TRANSACTION) return super.onTransact(code, data, response, flags)
                    if (pending !== reply || data.dataAvail() > MediaDecodePolicy.MAX_REPLY_BYTES) return false
                    val result =
                        try {
                            data.enforceInterface(MEDIA_REPLY_DESCRIPTOR)
                            if (data.readInt() != reply.id) return false
                            readMediaDecodeReply(data).apply { putInt("uid", android.os.Binder.getCallingUid()) }
                        } catch (_: RuntimeException) {
                            null
                        }
                    synchronized(bindingLock) {
                        if (closed || session?.epoch != reply.session.epoch || pending !== reply) return false
                        if (reply.completed.compareAndSet(false, true)) {
                            reply.result = result
                            pending = null // Revoke the capability before retirement or another callback.
                            result?.let(IsolatedMediaDecoder::recordIsolation)
                            reply.ready.countDown()
                        }
                    }
                    return true
                }
            },
        )

    private fun connection(selected: Worker): ServiceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName,
                service: IBinder,
            ) {
                synchronized(bindingLock) {
                    if (worker !== selected || selected.binder != null) return
                    selected.binder = service
                    selected.bound = true
                    selected.remote = Messenger(service)
                    try {
                        service.linkToDeath(selected.deathRecipient, 0)
                        selected.linked = true
                    } catch (_: android.os.RemoteException) {
                        // linkToDeath can report an already-dead remote; never infer death from send failure.
                        if (!service.isBinderAlive) {
                            selected.dead.countDown()
                        } else {
                            warnLifetimeHold(selected, "death-registration")
                        }
                        selected.retiring = true
                    } catch (_: RuntimeException) {
                        selected.retiring = true
                        warnLifetimeHold(selected, "death-registration")
                    }
                    if (closed || session?.epoch != selected.reply.session.epoch) selected.retiring = true
                    selected.reply.connected.countDown()
                }
                if (selected.retiring) retire(selected)
                dispose(selected)
            }

            override fun onServiceDisconnected(name: ComponentName) = disconnected(selected)

            override fun onBindingDied(name: ComponentName) = disconnected(selected)

            override fun onNullBinding(name: ComponentName) = disconnected(selected)
        }

    private fun disconnected(selected: Worker) {
        synchronized(bindingLock) {
            if (worker !== selected) return
            selected.retiring = true
            if (pending === selected.reply) revokePending()
            selected.reply.connected.countDown()
        }
        // A binding notification is not a process-death receipt.
        retire(selected)
    }

    private fun died(selected: Worker) {
        synchronized(bindingLock) {
            selected.dead.countDown()
            if (worker === selected && pending === selected.reply) revokePending()
            selected.reply.ready.countDown()
            selected.reply.connected.countDown()
        }
        dispose(selected)
    }

    private fun dispose(selected: Worker) {
        synchronized(bindingLock) {
            if (selected.dead.count != 0L || !selected.bindComplete || selected.disposed) return
            selected.disposed = true
            if (worker === selected) worker = null
        }
        // A definitive false bind still registers a connection that must be released.
        runCatching { context.unbindService(selected.connection) }
        selected.binder?.let { binder -> runCatching { binder.unlinkToDeath(selected.deathRecipient, 0) } }
        if (closed && callbackThread.isInitialized()) callbacks.quitSafely()
    }

    override fun decode(
        bytes: ByteArray,
        edge: Int,
        oriented: Boolean,
        operation: Int,
    ): Bitmap? =
        serialized { ticket ->
            val capacity = MediaDecodePolicy.outputCapacity(edge) ?: return@serialized null
            SharedMemory.create("media-pixels", capacity).use { output ->
                val spec = MediaDecodeRequest(edge, oriented, operation)
                val result = request(ticket, bytes, spec, output) ?: return@serialized null
                copyPixels(ticket, result, output, edge)
            }
        }

    override fun exif(bytes: ByteArray): Int? =
        serialized { ticket ->
            SharedMemory.create("media-exif", Int.SIZE_BYTES).use { output ->
                val spec = MediaDecodeRequest(1, false, MediaDecodePolicy.EXIF)
                request(ticket, bytes, spec, output)?.getInt("orientation")?.takeIf {
                    it in
                        1..MediaDecodePolicy.MAX_EXIF_ORIENTATION
                }
            }
        }

    override fun region(
        bytes: ByteArray,
        region: MediaImageRegion,
        edge: Int,
    ): Bitmap? =
        serialized { ticket ->
            val capacity = MediaDecodePolicy.outputCapacity(edge) ?: return@serialized null
            SharedMemory.create("media-region", capacity).use { output ->
                ticket.session.sources.withInput(bytes) { input ->
                    val request = MediaDecodeRequest(edge, false, MediaDecodePolicy.REGION, region = region)
                    exchange(ticket, input, output, request)
                        ?.let { copyPixels(ticket, it, output, edge) }
                }
            }
        }

    override fun inspect(bytes: ByteArray): ImageInspection? =
        serialized { ticket ->
            SharedMemory.create("media-inspection", Int.SIZE_BYTES).use { output ->
                val spec = MediaDecodeRequest(1, false, MediaDecodePolicy.INSPECT)
                val result = request(ticket, bytes, spec, output) ?: return@serialized null
                val width = result.getInt("width")
                val height = result.getInt("height")
                if (width <= 0 || height <= 0) return@serialized null
                ImageInspection(
                    width,
                    height,
                    result.getString("mime").orEmpty().take(MediaDecodePolicy.MAX_MIME_CHARS),
                    result.getInt("orientation"),
                )
            }
        }

    override fun decodeUri(
        resolver: android.content.ContentResolver,
        uri: android.net.Uri,
        edge: Int,
    ): Bitmap? {
        val selected = session ?: return null
        return selected.sources.withUriInput(resolver, uri) { input ->
            serialized(selected) { ticket ->
                val capacity = MediaDecodePolicy.outputCapacity(edge) ?: return@serialized null
                SharedMemory.create("media-pixels", capacity).use { output ->
                    exchange(ticket, input, output, MediaDecodeRequest(edge, true, MediaDecodePolicy.DECODE))
                        ?.let { copyPixels(ticket, it, output, edge) }
                }
            }
        }
    }

    private fun <T> serialized(
        selected: Session? = session,
        block: (Operation) -> T?,
    ): T? {
        if (selected == null || closed) return null
        // Queue admission has its own bound; it never spends the admitted exchange's budget.
        val queued = System.nanoTime()
        val admitted = gate.tryAcquire(MediaDecodePolicy.DEADLINE_MS, TimeUnit.MILLISECONDS)
        admissionObserver?.admission(System.nanoTime() - queued, admitted)
        return if (!admitted) {
            null
        } else {
            val ticket = Operation(selected, android.os.SystemClock.elapsedRealtime() + MediaDecodePolicy.DEADLINE_MS)
            try {
                if (!current(ticket)) {
                    null
                } else {
                    val result = block(ticket)
                    if (current(ticket)) {
                        result
                    } else {
                        (result as? Bitmap)?.recycle()
                        null
                    }
                }
            } finally {
                gate.release()
            }
        }
    }

    private fun remaining(ticket: Operation): Long {
        val timeLeft = ticket.deadline - android.os.SystemClock.elapsedRealtime()
        return timeLeft.coerceAtLeast(0L)
    }

    private fun await(
        latch: CountDownLatch,
        ticket: Operation,
    ): Boolean = latch.await(remaining(ticket), TimeUnit.MILLISECONDS)

    @Suppress("ReturnCount") // Reject resource and cooldown failures before binding.
    private fun request(
        ticket: Operation,
        bytes: ByteArray,
        spec: MediaDecodeRequest,
        output: SharedMemory,
    ): Bundle? {
        val limit = MediaDecodePolicy.MAX_INPUT_BYTES.toLong()
        if (bytes.size.toLong() !in 1..limit || !current(ticket)) return null
        val digest =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(bytes)
        val key = "${spec.operation}:${spec.edge}:${spec.option}:" + digest.joinToString("") { "%02x".format(it) }
        val now = android.os.SystemClock.elapsedRealtime()
        if ((ticket.session.refusals[key] ?: 0L) > now) return null
        val result =
            ticket.session.sources.withInput(bytes) { input ->
                exchange(ticket, input, output, spec)
            }
        if (ticket.workerRefused && current(ticket)) {
            recordRefusal(ticket.session, key, android.os.SystemClock.elapsedRealtime())
        }
        return result
    }

    @Suppress("ReturnCount") // Connection, deadline and exact reply shape are separate fail-closed boundaries.
    private fun exchange(
        ticket: Operation,
        input: ParcelFileDescriptor,
        output: SharedMemory,
        request: MediaDecodeRequest,
    ): Bundle? {
        requestDispatched = false
        val reply = Reply(ids.incrementAndGet(), ticket.session)
        val selected = reserveWorker(reply, ticket) ?: return null
        try {
            if (!connect(selected, ticket)) return null
            val target = admittedTarget(selected, ticket) ?: return null
            target.send(
                Message.obtain().apply {
                    what = MediaDecodePolicy.DECODE
                    arg1 = reply.id
                    replyTo = responseCapability(reply)
                    data = requestPayload(input, output, request)
                },
            )
            if (!await(reply.ready, ticket)) return null
            retire(selected)
            if (!await(selected.dead, ticket) || !current(ticket)) return null
            val result = reply.result ?: return null
            ticket.workerRefused = validIsolation(result) && !result.getBoolean("success")
            return result.takeIf(::validResult)
        } finally {
            synchronized(bindingLock) { if (pending === reply) revokePending() }
            retire(selected)
            dispose(selected)
        }
    }

    private fun reserveWorker(
        reply: Reply,
        ticket: Operation,
    ): Worker? =
        synchronized(bindingLock) {
            if (!current(ticket) || worker != null || remaining(ticket) == 0L) {
                null
            } else {
                Worker(reply).also {
                    it.connection = connection(it)
                    worker = it
                    pending = reply
                }
            }
        }

    private fun admittedTarget(
        selected: Worker,
        ticket: Operation,
    ): Messenger? =
        synchronized(bindingLock) {
            val matching = current(ticket) && worker === selected
            val usable = selected.linked && !selected.retiring
            if (!matching || !usable || remaining(ticket) == 0L) {
                null
            } else {
                // Descriptor admission linearizes before a concurrent reset.
                requestDispatched = true
                selected.remote
            }
        }

    private fun validResult(result: Bundle): Boolean = result.getBoolean("success") && validIsolation(result)

    private fun validIsolation(result: Bundle): Boolean {
        val isolated = result.getInt("uid") != Process.myUid() && result.getBoolean("isolated")
        val restricted = !result.getBoolean("internetGranted") && !result.getBoolean("runtimeInitialized")
        return isolated && restricted
    }

    private fun retire(selected: Worker) {
        val target =
            synchronized(bindingLock) {
                selected.retiring = true
                if (selected.retireSent || selected.dead.count == 0L) {
                    null
                } else {
                    selected.remote?.also { selected.retireSent = true }
                }
            }
        runCatching { target?.send(Message.obtain().apply { what = MediaDecodePolicy.RETIRE }) }
    }

    /** One input-free diagnostic per held worker; this never supplies a death witness. */
    private fun warnLifetimeHold(
        selected: Worker,
        reason: String,
    ) {
        val first =
            synchronized(bindingLock) {
                if (selected.holdReported) false else true.also { selected.holdReported = true }
            }
        if (first) android.util.Log.w("MediaDecode", "Worker lifetime unresolved: $reason")
    }

    internal fun isRequestDispatchedForTest(): Boolean {
        check(dev.ipf.whitenoise.android.BuildConfig.DEBUG)
        return requestDispatched && pending != null
    }

    private fun requestPayload(
        input: ParcelFileDescriptor,
        output: SharedMemory,
        request: MediaDecodeRequest,
    ): Bundle =
        Bundle().apply {
            if (dev.ipf.whitenoise.android.BuildConfig.DEBUG) {
                val canary = File(context.filesDir, "media-containment-canary")
                if (canary.isFile) putString("canaryPath", canary.absolutePath)
            }
            putInt("version", MediaDecodePolicy.VERSION)
            putInt("operation", request.operation)
            putInt("edge", request.edge)
            putBoolean("oriented", request.option)
            putLong("offset", request.offset)
            putLong("length", request.length ?: input.statSize)
            request.region?.let {
                putInt("left", it.left)
                putInt("top", it.top)
                putInt("right", it.right)
                putInt("bottom", it.bottom)
            }
            putParcelable("input", input)
            putParcelable("output", output)
        }

    private fun recordRefusal(
        selected: Session,
        key: String,
        now: Long,
    ) {
        selected.refusals[key] = now + MediaDecodePolicy.REFUSAL_TTL_MS
        while (selected.refusals.size > MediaDecodePolicy.MAX_REFUSALS) {
            selected.refusals.remove(selected.refusals.keys.first())
        }
    }

    @Suppress("ReturnCount") // Current, late and unsuccessful binding paths have distinct ownership outcomes.
    private fun connect(selected: Worker, ticket: Operation): Boolean {
        val accepted =
            try {
                context.bindIsolatedService(
                    Intent(context, MediaDecodeService::class.java),
                    Context.BIND_AUTO_CREATE,
                    workerInstanceName(instanceName, bindingSequence.incrementAndGet()),
                    callbackExecutor,
                    selected.connection,
                )
            } catch (_: RuntimeException) {
                // A throwing bind has no authenticated lifetime outcome; retain the generation.
                synchronized(bindingLock) {
                    selected.bindComplete = true
                    selected.retiring = true
                }
                warnLifetimeHold(selected, "bind-outcome")
                return false
            }
        val bound =
            synchronized(bindingLock) {
                selected.bindComplete = true
                selected.bound = selected.bound || accepted
                if (!selected.bound) selected.dead.countDown() // Framework definitively refused this binding.
                selected.bound
            }
        dispose(selected)
        if (!bound || !await(selected.reply.connected, ticket)) return false
        return synchronized(bindingLock) {
            val matching = current(ticket) && worker === selected
            val usable = selected.linked && !selected.retiring
            matching && usable
        }
    }

    // Failed copies recycle their allocation before propagation.
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private fun copyPixels(
        ticket: Operation,
        result: Bundle,
        output: SharedMemory,
        edge: Int,
    ): Bitmap? {
        if (!current(ticket)) return null
        val width = result.getInt("width")
        val height = result.getInt("height")
        val count = MediaDecodePolicy.pixelBytes(width, height, edge, output.size) ?: return null
        if (!output.setProtect(OsConstants.PROT_READ)) return null
        val pixels = output.mapReadOnly()
        return try {
            pixels.limit(count)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                check(bitmap.byteCount == count)
                bitmap.copyPixelsFromBuffer(pixels)
                if (current(ticket)) {
                    bitmap
                } else {
                    bitmap.recycle()
                    null
                }
            } catch (failure: Exception) {
                bitmap.recycle()
                throw failure
            }
        } finally {
            SharedMemory.unmap(pixels)
        }
    }

    override fun close() {
        synchronized(bindingLock) { closed = true }
        invalidateSession()
        if (synchronized(bindingLock) { worker == null } && callbackThread.isInitialized()) callbacks.quitSafely()
    }

    companion object {
        private val bindingSequence = AtomicInteger()
    }

    private class Session(
        val epoch: Long,
        val sources: MediaSourceAccess,
    ) {
        val refusals = LinkedHashMap<String, Long>()
    }

    private data class Operation(
        val session: Session,
        val deadline: Long,
        var workerRefused: Boolean = false,
    )

    private inner class Worker(
        val reply: Reply,
    ) {
        lateinit var connection: ServiceConnection
        var binder: IBinder? = null
        var remote: Messenger? = null
        var linked = false
        var bound = false
        var bindComplete = false
        var retiring = false
        var retireSent = false
        var disposed = false
        var holdReported = false
        val dead = CountDownLatch(1)
        val deathRecipient = IBinder.DeathRecipient { died(this) }
    }

    private class Reply(
        val id: Int,
        val session: Session,
    ) {
        val connected = CountDownLatch(1)
        val ready = CountDownLatch(1)
        val completed =
            java.util.concurrent.atomic
                .AtomicBoolean(false)

        @Volatile var result: Bundle? = null
    }
}

internal data class MediaDecodeRequest(
    val edge: Int,
    val option: Boolean,
    val operation: Int,
    val offset: Long = 0L,
    val length: Long? = null,
    val region: MediaImageRegion? = null,
)
