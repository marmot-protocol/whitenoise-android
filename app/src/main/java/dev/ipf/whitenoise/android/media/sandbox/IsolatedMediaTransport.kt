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

    fun probeUriAt(
        resolver: android.content.ContentResolver,
        uri: android.net.Uri,
        positionMs: Long,
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
@Suppress("TooManyFunctions", "LargeClass") // Keep one lock owner for binding, admission and exact-death retirement.
internal class IsolatedMediaTransport(
    private val context: Context,
    private val instanceName: String = "images",
    private val serviceClass: Class<out android.app.Service> = MediaDecodeService::class.java,
    private val processWitnessFactory: () -> KernelProcessWitness = ::KernelProcessWitness,
    private val refusalClock: () -> Long = android.os.SystemClock::elapsedRealtime,
) : MediaDecodeTransport,
    java.io.Closeable {
    private val gate = Semaphore(1)
    private val bindingLock = Any()
    private val ids = AtomicInteger()
    private val automaticIds = AtomicInteger()
    private val automaticAv = serviceClass == MediaAvDecodeService::class.java
    private val parentResources = MediaParentResources()
    private var epoch = 0L

    @Volatile private var session: Session? = Session(epoch, sourceAccess(), refusalClock)

    @Volatile private var closed = false

    @Volatile private var requestDispatched = false

    @Volatile private var pending: Reply? = null
    private var worker: Worker? = null
    private val callbackThread = lazy { HandlerThread("media-decoder-ipc").apply { start() } }
    private val callbacks by callbackThread
    private val handler by lazy { Handler(callbacks.looper) }
    private val callbackExecutor = java.util.concurrent.Executor { command -> handler.post(command) }
    private val retirementThread =
        lazy {
            java.util.concurrent.Executors
                .newSingleThreadExecutor()
        }
    private val retirementExecutor by retirementThread

    /** Account invalidation never discards the process-death witness. */
    internal fun activateSession() = replaceSession(true)

    internal fun invalidateSession() = replaceSession(false)

    private fun replaceSession(active: Boolean) {
        val (previous, retiringWorker) =
            synchronized(bindingLock) {
                check(!closed || !active) { "A closed decoder cannot be reactivated" }
                val previous = session
                val retiringWorker = worker
                session = if (active) Session(++epoch, sourceAccess(), refusalClock) else null
                revokePending()
                previous to retiringWorker
            }
        if (automaticAv) {
            try {
                previous?.sources?.close()
            } finally {
                retiringWorker?.let(::retire)
            }
        } else {
            previous?.sources?.close()
            retiringWorker?.let(::retire)
        }
    }

    private fun revokePending() {
        pending?.let {
            it.completed.set(true)
            it.result = null
            it.ready.countDown()
            it.connected.countDown()
            worker?.takeIf { candidate -> candidate.reply === it }?.identityReady?.countDown()
        }
        pending = null
    }

    private fun sourceAccess(): MediaSourceAccess {
        val access = MediaSourceAccess(context, if (automaticAv) parentResources else null)
        return access
    }

    private fun <T : AutoCloseable, R> withParentResource(
        value: T,
        block: (T) -> R,
    ): R {
        val owner = if (automaticAv) parentResources.own(value) else MediaParentResource(value)
        return owner.use { block(it.value) }
    }

    private fun current(ticket: Operation): Boolean = !closed && session?.epoch == ticket.session.epoch

    private fun responseCapability(
        selected: Worker,
        reply: Reply,
    ): Messenger =
        Messenger(
            object : android.os.Binder() {
                // Keep identity and final-reply admission with the exact capability.
                @Suppress("ReturnCount", "CyclomaticComplexMethod")
                override fun onTransact(
                    code: Int,
                    data: android.os.Parcel,
                    response: android.os.Parcel?,
                    flags: Int,
                ): Boolean {
                    if (automaticAv && code == AutomaticAvIdentity.CALLBACK) {
                        return acceptIdentity(selected, data, response, flags)
                    }
                    if (automaticAv) {
                        val invalidReply = response == null || flags and IBinder.FLAG_ONEWAY != 0
                        if (invalidReply ||
                            !selected.processWitness.acceptsCaller(
                                android.os.Binder.getCallingUid(),
                                android.os.Binder.getCallingPid(),
                            )
                        ) {
                            return false
                        }
                    }
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
                            result?.let { receipt ->
                                IsolatedMediaDecoder.recordIsolation(receipt)
                                if (serviceClass == MediaAvDecodeService::class.java) {
                                    IsolatedMediaDecoder.recordAvIsolation(receipt)
                                }
                            }
                            reply.ready.countDown()
                        }
                    }
                    return true
                }
            },
        )

    private fun acceptIdentity(
        selected: Worker,
        data: android.os.Parcel,
        response: android.os.Parcel?,
        flags: Int,
    ): Boolean {
        val accepted =
            try {
                require(response != null && flags and IBinder.FLAG_ONEWAY == 0)
                require(data.dataSize() <= AutomaticAvIdentity.MAX_BYTES && !data.hasFileDescriptors())
                data.enforceInterface(AutomaticAvIdentity.DESCRIPTOR)
                require(
                    data.dataAvail() == Int.SIZE_BYTES && data.readInt() == selected.reply.id && selected.reply.id > 0,
                )
                synchronized(bindingLock) {
                    // Even a late exact callback can identify the retired process for cleanup; it cannot admit input.
                    val exact = worker === selected && selected.linked
                    val calibrated =
                        exact &&
                            selected.processWitness.admit(
                                android.os.Binder.getCallingUid(),
                                android.os.Binder.getCallingPid(),
                            )
                    val current =
                        !closed &&
                            session?.epoch == selected.reply.session.epoch &&
                            pending === selected.reply &&
                            !selected.retiring
                    calibrated && current
                }
            } catch (_: RuntimeException) {
                false
            }
        selected.identityAccepted = accepted
        selected.identityReady.countDown()
        if (accepted) response?.writeInt(1) else retire(selected)
        return accepted
    }

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
        if (automaticAv) {
            disposeAutomatic(selected)
            return
        }
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

    @Suppress("ReturnCount", "CyclomaticComplexMethod") // One atomic process/unbind/caller retirement decision.
    private fun disposeAutomatic(selected: Worker) {
        val unbind =
            synchronized(bindingLock) {
                if (selected.disposed || !selected.bindComplete || !selected.retiring) return
                if (!selected.unbindStarted) true.also { selected.unbindStarted = true } else false
            }
        if (unbind) {
            handler.post {
                val success =
                    try {
                        context.unbindService(selected.connection)
                        true
                    } catch (_: RuntimeException) {
                        false
                    }
                synchronized(bindingLock) { selected.unbound = success }
                disposeAutomatic(selected)
            }
        }
        val gone = selected.definitiveNoWorker || selected.processWitness.confirmedGone()
        synchronized(bindingLock) {
            val detached = gone && selected.unbound
            if (detached) selected.safeToCopy.countDown()
            val operationIdle = !selected.operationActive && !selected.retireRpcActive
            if (detached && operationIdle && parentResources.available()) {
                if (selected.disposed) return
                selected.disposed = true
                if (worker === selected) worker = null
            } else {
                if (!selected.observationScheduled) {
                    selected.observationScheduled = true
                    val delay = AutomaticAvIdentity.observationDelay(selected.observationPolls++)
                    handler.postDelayed({
                        synchronized(bindingLock) { selected.observationScheduled = false }
                        disposeAutomatic(selected)
                    }, delay)
                }
                return
            }
        }
        selected.binder?.let { binder -> runCatching { binder.unlinkToDeath(selected.deathRecipient, 0) } }
        if (closed && callbackThread.isInitialized()) callbacks.quitSafely()
        if (closed && retirementThread.isInitialized()) retirementExecutor.shutdown()
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

    override fun probe(
        file: File,
        edge: Int,
        poster: Boolean,
    ): MediaProbe? =
        serialized { ticket ->
            withMediaFile(ticket, file, "probe:$edge:$poster") { input ->
                probeInput(ticket, input, edge, poster)
            }
        }

    override fun probeBytes(
        bytes: ByteArray,
        edge: Int,
        poster: Boolean,
    ): MediaProbe? =
        serialized { ticket ->
            if (bytes.size.toLong() !in 1..MAX_MEDIA_SOURCE_BYTES) return@serialized null
            val digest =
                java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest(bytes)
                    .joinToString("") { "%02x".format(it) }
            mediaAttempt(ticket, "probe:$edge:$poster:$digest") {
                ticket.session.sources.withInput(bytes) { input -> probeInput(ticket, input, edge, poster) }
            }
        }

    override fun probeUri(
        resolver: android.content.ContentResolver,
        uri: android.net.Uri,
        edge: Int,
    ): MediaProbe? {
        val selected = session ?: return null
        return if (!automaticAv) {
            selected.sources.withMediaUriInput(resolver, uri) { input ->
                serialized(selected) { ticket -> probeInput(ticket, input, edge, true) }
            }
        } else {
            serialized(selected) { ticket ->
                selected.sources.withMediaUriInput(resolver, uri) { input ->
                    ticket.deadline = android.os.SystemClock.elapsedRealtime() + MediaDecodePolicy.DEADLINE_MS
                    probeInput(ticket, input, edge, true)
                }
            }
        }
    }

    override fun probeUriAt(
        resolver: android.content.ContentResolver,
        uri: android.net.Uri,
        positionMs: Long,
        edge: Int,
    ): MediaProbe? {
        val selected = session?.takeIf { positionMs in 0..MAX_MEDIA_DURATION_MS } ?: return null
        return if (!automaticAv) {
            selected.sources.withMediaUriInput(resolver, uri) { input ->
                serialized(selected) { ticket ->
                    probeInput(ticket, input, edge, true, TimeUnit.MILLISECONDS.toMicros(positionMs))
                }
            }
        } else {
            serialized(selected) { ticket ->
                selected.sources.withMediaUriInput(resolver, uri) { input ->
                    ticket.deadline = android.os.SystemClock.elapsedRealtime() + MediaDecodePolicy.DEADLINE_MS
                    probeInput(ticket, input, edge, true, TimeUnit.MILLISECONDS.toMicros(positionMs))
                }
            }
        }
    }

    private fun probeInput(
        ticket: Operation,
        input: ParcelFileDescriptor,
        edge: Int,
        poster: Boolean,
        positionUs: Long = 0L,
    ): MediaProbe? {
        val capacity = MediaDecodePolicy.outputCapacity(edge) ?: return null
        return withParentResource(
            SharedMemory.create("media-av-pixels", if (poster) capacity else Int.SIZE_BYTES),
        ) { output ->
            val spec = MediaDecodeRequest(edge, poster, MediaDecodePolicy.PROBE, positionUs = positionUs)
            val result = exchange(ticket, input, output, spec) ?: return@withParentResource null
            val bitmap =
                if (result.getBoolean("pixels")) {
                    copyPixels(ticket, result, output, edge) ?: return@withParentResource null
                } else {
                    null
                }
            validateMediaProbe(
                MediaProbe(
                    result.getInt("sourceWidth"),
                    result.getInt("sourceHeight"),
                    result.getLong("duration"),
                    bitmap,
                ),
                edge,
            )
        }
    }

    override fun waveform(file: File): FloatArray? =
        serialized(deadlineMs = MediaDecodePolicy.WAVEFORM_DEADLINE_MS) { ticket ->
            withMediaFile(ticket, file, "waveform") { input ->
                withParentResource(SharedMemory.create("media-av-waveform", Int.SIZE_BYTES)) { output ->
                    val spec = MediaDecodeRequest(1, false, MediaDecodePolicy.WAVEFORM)
                    exchange(ticket, input, output, spec)?.getFloatArray("waveform")?.takeIf {
                        it.size == dev.ipf.whitenoise.android.audio.AudioWaveformExtractor.BARS &&
                            it.all { value -> value.isFinite() && value in 0f..1f }
                    }
                }
            }
        }

    private fun <T> withMediaFile(
        ticket: Operation,
        file: File,
        operation: String,
        block: (ParcelFileDescriptor) -> T?,
    ): T? {
        if (!file.isFile || file.length() !in 1..MAX_MEDIA_SOURCE_BYTES) return null
        val key = "$operation:${file.absolutePath}:${file.length()}:${file.lastModified()}"
        return mediaAttempt(ticket, key) {
            withParentResource(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) { input ->
                if (input.statSize !in 1..MAX_MEDIA_SOURCE_BYTES) null else block(input)
            }
        }
    }

    private fun <T> mediaAttempt(
        ticket: Operation,
        key: String,
        block: () -> T?,
    ): T? =
        ticket.session.mediaRefusals.attempt(
            key,
            { check(current(ticket) && remaining(ticket) > 0) { "Media session retired or expired" } },
            { ticket.workerRefused },
            block,
        )

    private fun <T> serialized(
        selected: Session? = session,
        deadlineMs: Long = MediaDecodePolicy.DEADLINE_MS,
        block: (Operation) -> T?,
    ): T? {
        if (selected == null || closed) return null
        // Queue admission has its own bound; it never spends the admitted exchange's budget.
        return if (!gate.tryAcquire(MediaDecodePolicy.DEADLINE_MS, TimeUnit.MILLISECONDS)) {
            null
        } else {
            val ticket = Operation(selected, android.os.SystemClock.elapsedRealtime() + deadlineMs)
            try {
                if (!current(ticket) || (automaticAv && !parentResources.available())) {
                    null
                } else {
                    val result = block(ticket)
                    if (current(ticket)) {
                        result
                    } else {
                        (result as? Bitmap)?.recycle()
                        (result as? MediaProbe)?.let(::disposeProbe)
                        null
                    }
                }
            } finally {
                synchronized(bindingLock) { ticket.worker?.operationActive = false }
                ticket.worker?.let(::dispose)
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

    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    // One input capability owns READY, exact reply, deadline and retirement; every refusal stays fail-closed.
    private fun exchange(
        ticket: Operation,
        input: ParcelFileDescriptor,
        output: SharedMemory,
        request: MediaDecodeRequest,
    ): Bundle? {
        requestDispatched = false
        val id =
            if (automaticAv) {
                automaticIds.updateAndGet {
                    if (it ==
                        Int.MAX_VALUE
                    ) {
                        1
                    } else {
                        it + 1
                    }
                }
            } else {
                ids.incrementAndGet()
            }
        val reply = Reply(id, ticket.session)
        val selected = reserveWorker(reply, ticket) ?: return null
        try {
            if (!connect(selected, ticket)) return null
            val target = admittedTarget(selected, ticket, dispatch = !automaticAv) ?: return null
            val capability = responseCapability(selected, reply)
            if (automaticAv) {
                target.send(
                    Message.obtain().apply {
                        what = AutomaticAvIdentity.MESSAGE
                        arg1 = reply.id
                        replyTo = capability
                    },
                )
                if (!await(selected.identityReady, ticket) ||
                    !selected.identityAccepted ||
                    !current(ticket)
                ) {
                    return null
                }
            }
            // Only the calibrated READY permits selected-descriptor admission.
            if (automaticAv && admittedTarget(selected, ticket) == null) return null
            target.send(
                Message.obtain().apply {
                    what = MediaDecodePolicy.DECODE
                    arg1 = reply.id
                    replyTo = capability
                    data = requestPayload(input, output, request)
                },
            )
            if (!await(reply.ready, ticket)) return null
            retire(selected)
            val lifetime = if (automaticAv) selected.safeToCopy else selected.dead
            if (!await(lifetime, ticket) || !current(ticket)) return null
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
            if (!current(ticket)) {
                null
            } else {
                val occupied = worker != null || remaining(ticket) == 0L
                if (occupied || (automaticAv && !parentResources.healthy())) {
                    null
                } else {
                    Worker(reply).also {
                        it.connection = connection(it)
                        worker = it
                        ticket.worker = it
                        pending = reply
                    }
                }
            }
        }

    private fun admittedTarget(
        selected: Worker,
        ticket: Operation,
        dispatch: Boolean = true,
    ): Messenger? =
        synchronized(bindingLock) {
            val matching = current(ticket) && worker === selected
            val usable = selected.linked && !selected.retiring && (!automaticAv || parentResources.healthy())
            if (!matching || !usable || remaining(ticket) == 0L) {
                null
            } else {
                // Descriptor admission linearizes before a concurrent reset.
                if (dispatch) requestDispatched = true
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
                    selected.remote?.also {
                        selected.retireSent = true
                        if (automaticAv) selected.retireRpcActive = true
                    }
                }
            }
        if (automaticAv) {
            if (target != null) {
                try {
                    retirementExecutor.execute {
                        try {
                            runCatching { target.send(Message.obtain().apply { what = MediaDecodePolicy.RETIRE }) }
                        } finally {
                            synchronized(bindingLock) { selected.retireRpcActive = false }
                            disposeAutomatic(selected)
                        }
                    }
                } catch (_: RuntimeException) {
                    synchronized(bindingLock) { selected.retireRpcActive = false }
                }
            }
            // AMS unbinding and process observation never queue behind a hostile endpoint transaction.
            disposeAutomatic(selected)
        } else {
            runCatching { target?.send(Message.obtain().apply { what = MediaDecodePolicy.RETIRE }) }
        }
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
            putLong("positionUs", request.positionUs)
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
                    Intent(context, serviceClass),
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
                if (!selected.bound) {
                    selected.definitiveNoWorker = true
                    selected.retiring = true
                    selected.dead.countDown() // Framework definitively refused this binding.
                }
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
        if (synchronized(bindingLock) { worker == null }) {
            if (callbackThread.isInitialized()) callbacks.quitSafely()
            if (retirementThread.isInitialized()) retirementExecutor.shutdown()
        }
    }

    companion object {
        private val bindingSequence = AtomicInteger()
    }

    private class Session(
        val epoch: Long,
        val sources: MediaSourceAccess,
        refusalClock: () -> Long,
    ) {
        val mediaRefusals = MediaRefusalCache(refusalClock)
        val refusals = LinkedHashMap<String, Long>()
    }

    private inner class Operation(
        val session: Session,
        var deadline: Long,
        var workerRefused: Boolean = false,
    ) {
        var worker: Worker? = null
    }

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
        val processWitness = processWitnessFactory()
        val identityReady = CountDownLatch(1)

        @Volatile var identityAccepted = false
        val safeToCopy = CountDownLatch(1)
        var definitiveNoWorker = false
        var unbindStarted = false
        var unbound = false
        var observationScheduled = false
        var observationPolls = 0
        var operationActive = true
        var retireRpcActive = false
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
    val positionUs: Long = 0L,
)
