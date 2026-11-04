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

/** One serialized automatic A/V lane; its worker is retained until parent cleanup and kernel absence. */
@Suppress("TooManyFunctions", "LargeClass") // Keep one lock owner for binding, admission and exact-death retirement.
internal class IsolatedAutomaticAvTransport(
    private val context: Context,
    private val instanceName: String = "automatic_av",
    private val processWitnessFactory: () -> KernelProcessWitness = ::KernelProcessWitness,
    private val refusalClock: () -> Long = android.os.SystemClock::elapsedRealtime,
) : MediaDecodeTransport,
    java.io.Closeable {
    private val gate = Semaphore(1)
    private val bindingLock = Any()
    private val automaticIds = AtomicInteger()
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

    /** Revoke pending source capabilities while retaining the prior worker until its retirement is proven. */
    internal fun invalidateSession() = replaceSession(false)

    /**
     * Replace only account-scoped source state; worker lifetime and retirement remain with the exact prior
     * generation.
     */
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
        try {
            previous?.sources?.close()
        } finally {
            retiringWorker?.let(::retire)
        }
    }

    /**
     * Wake revoked callers with no result and invalidate their callback capability before another request can
     * publish.
     */
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

    /**
     * Register selected descriptor ownership with the automatic lane so cleanup participates in admission
     * release.
     */
    private fun sourceAccess(): MediaSourceAccess {
        val access = MediaSourceAccess(context, parentResources)
        return access
    }

    /** Keep the exact parent-owned resource registered for the complete operation and its acknowledged close. */
    private fun <T : AutoCloseable, R> withParentResource(
        value: T,
        block: (T) -> R,
    ): R {
        val owner = parentResources.own(value)
        return owner.use { block(it.value) }
    }

    /** Reject work whose account epoch or transport lifetime no longer matches its admitted ticket. */
    private fun current(ticket: Operation): Boolean = !closed && session?.epoch == ticket.session.epoch

    /** Create the bounded callback capability tied to one worker, reply id and account epoch. */
    private fun responseCapability(
        selected: Worker,
        reply: Reply,
    ): Messenger =
        Messenger(
            object : android.os.Binder() {
                // Keep identity and final-reply admission with the exact capability.

                /**
                 * Authenticate callback identity and bounded reply layout before publishing any result to the
                 * current caller.
                 */
                @Suppress("ReturnCount", "CyclomaticComplexMethod")
                override fun onTransact(
                    code: Int,
                    data: android.os.Parcel,
                    response: android.os.Parcel?,
                    flags: Int,
                ): Boolean {
                    if (code == AutomaticAvIdentity.CALLBACK) {
                        return acceptIdentity(selected, data, response, flags)
                    }
                    val invalidReply = response == null || flags and IBinder.FLAG_ONEWAY != 0
                    if (invalidReply ||
                        !selected.processWitness.acceptsCaller(
                            android.os.Binder.getCallingUid(),
                            android.os.Binder.getCallingPid(),
                        )
                    ) {
                        return false
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
                                IsolatedMediaDecoder.recordAvIsolation(receipt)
                            }
                            reply.ready.countDown()
                        }
                    }
                    return true
                }
            },
        )

    /**
     * Calibrate the exact worker PID/UID before input exposure; late identity may aid cleanup but cannot restore
     * admission.
     */
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

    /** Bind callbacks to the selected generation so a stale service connection cannot replace a newer owner. */
    private fun connection(selected: Worker): ServiceConnection =
        object : ServiceConnection {
            /**
             * Attach the exact Binder death witness before waking the admitted caller or retiring a late
             * connection.
             */
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

            /** Revoke the endpoint without treating disconnection as proof that its kernel process disappeared. */
            override fun onServiceDisconnected(name: ComponentName) = disconnected(selected)

            /**
             * Retire the disconnected binding while preserving the process-absence and resource-cleanup
             * requirements.
             */
            override fun onBindingDied(name: ComponentName) = disconnected(selected)

            /** Handle a null service endpoint through the same exact-generation retirement policy. */
            override fun onNullBinding(name: ComponentName) = disconnected(selected)
        }

    /** Invalidate the selected reply and initiate retirement without inferring process death from Binder state. */
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

    /** Record Binder endpoint death, wake the selected caller and preserve separate kernel-absence observation. */
    private fun died(selected: Worker) {
        synchronized(bindingLock) {
            selected.dead.countDown()
            if (worker === selected && pending === selected.reply) revokePending()
            selected.reply.ready.countDown()
            selected.reply.connected.countDown()
        }
        dispose(selected)
    }

    /** Advance exact-generation cleanup only after its calls and retirement operation no longer own resources. */
    private fun dispose(selected: Worker) = disposeAutomatic(selected)

    /** Close the selected automatic source and unbind once, retaining admission until process absence is proven. */
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

    /** This lane accepts only A/V probes and waveforms; still images use their domain-bound transport. */
    override fun decode(
        bytes: ByteArray,
        edge: Int,
        oriented: Boolean,
        operation: Int,
    ): Bitmap? = null

    override fun inspect(bytes: ByteArray): ImageInspection? = null

    /** Probe one selected local file for bounded automatic metadata and an optional poster. */
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

    /** Stage bounded encoded bytes for the automatic probe and retain their ownership through cleanup. */
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

    /** Probe only the explicitly selected URI descriptor, using the automatic source-refusal policy. */
    override fun probeUri(
        resolver: android.content.ContentResolver,
        uri: android.net.Uri,
        edge: Int,
    ): MediaProbe? {
        val selected = session ?: return null
        return serialized(selected) { ticket ->
            selected.sources.withMediaUriInput(resolver, uri) { input ->
                ticket.deadline = android.os.SystemClock.elapsedRealtime() + MediaDecodePolicy.DEADLINE_MS
                probeInput(ticket, input, edge, true)
            }
        }
    }

    /** Request the bounded poster at a specific source time without replacing the selected URI capability. */
    override fun probeUriAt(
        resolver: android.content.ContentResolver,
        uri: android.net.Uri,
        positionMs: Long,
        edge: Int,
    ): MediaProbe? {
        val selected = session?.takeIf { positionMs in 0..MAX_MEDIA_DURATION_MS } ?: return null
        return serialized(selected) { ticket ->
            selected.sources.withMediaUriInput(resolver, uri) { input ->
                ticket.deadline = android.os.SystemClock.elapsedRealtime() + MediaDecodePolicy.DEADLINE_MS
                probeInput(ticket, input, edge, true, TimeUnit.MILLISECONDS.toMicros(positionMs))
            }
        }
    }

    /** Validate bounded probe output before adopting metadata or copying pixels from shared memory. */
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

    /** Return a bounded waveform from the selected local audio file, preserving typed source-refusal evidence. */
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

    /** Keep the selected file registration alive until the automatic operation and its cleanup finish. */
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

    /**
     * Apply account-scoped source refusal policy without turning admission contention into corrupt-media
     * evidence.
     */
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

    /** Admit one automatic operation at a time while keeping queue wait separate from its execution deadline. */
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
                if (!current(ticket) || !parentResources.available()) {
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

    /** Return the current ticket execution budget; an obsolete account cannot regain time by waiting. */
    private fun remaining(ticket: Operation): Long {
        val timeLeft = ticket.deadline - android.os.SystemClock.elapsedRealtime()
        return timeLeft.coerceAtLeast(0L)
    }

    /** Wait only within the current ticket deadline and stop when account invalidation revokes the request. */
    private fun await(
        latch: CountDownLatch,
        ticket: Operation,
    ): Boolean = latch.await(remaining(ticket), TimeUnit.MILLISECONDS)

    // One input capability owns READY, exact reply, deadline and retirement; every refusal stays fail-closed.

    /**
     * Expose selected input only after authenticated worker identity and copy output only after the lifetime
     * checks pass.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private fun exchange(
        ticket: Operation,
        input: ParcelFileDescriptor,
        output: SharedMemory,
        request: MediaDecodeRequest,
    ): Bundle? {
        requestDispatched = false
        val id =
            automaticIds.updateAndGet {
                if (it ==
                    Int.MAX_VALUE
                ) {
                    1
                } else {
                    it + 1
                }
            }

        val reply = Reply(id, ticket.session)
        val selected = reserveWorker(reply, ticket) ?: return null
        try {
            if (!connect(selected, ticket)) return null
            val target = admittedTarget(selected, ticket, dispatch = false) ?: return null
            val capability = responseCapability(selected, reply)
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

            // Only the calibrated READY permits selected-descriptor admission.
            if (admittedTarget(selected, ticket) == null) return null
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
            val lifetime = selected.safeToCopy
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

    /** Reserve the single automatic worker for the current operation without replacing a retiring generation. */
    private fun reserveWorker(
        reply: Reply,
        ticket: Operation,
    ): Worker? =
        synchronized(bindingLock) {
            if (!current(ticket)) {
                null
            } else {
                val occupied = worker != null || remaining(ticket) == 0L
                if (occupied || !parentResources.healthy()) {
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

    /** Return the endpoint only while the same admitted operation still owns its current worker. */
    private fun admittedTarget(
        selected: Worker,
        ticket: Operation,
        dispatch: Boolean = true,
    ): Messenger? =
        synchronized(bindingLock) {
            val matching = current(ticket) && worker === selected
            val usable = selected.linked && !selected.retiring && parentResources.healthy()
            if (!matching || !usable || remaining(ticket) == 0L) {
                null
            } else {
                // Descriptor admission linearizes before a concurrent reset.
                if (dispatch) requestDispatched = true
                selected.remote
            }
        }

    /** Require both a successful reply and the expected isolated-worker identity before consuming output. */
    private fun validResult(result: Bundle): Boolean = result.getBoolean("success") && validIsolation(result)

    /** Reject a reply attributed to the privileged caller rather than the selected isolated UID. */
    private fun validIsolation(result: Bundle): Boolean {
        val isolated = result.getInt("uid") != Process.myUid() && result.getBoolean("isolated")
        val restricted = !result.getBoolean("internetGranted") && !result.getBoolean("runtimeInitialized")
        return isolated && restricted
    }

    /** Send bounded retirement for the exact worker while retaining cleanup and process-absence evidence. */
    private fun retire(selected: Worker) {
        val target =
            synchronized(bindingLock) {
                selected.retiring = true
                if (selected.retireSent || selected.dead.count == 0L) {
                    null
                } else {
                    selected.remote?.also {
                        selected.retireSent = true
                        selected.retireRpcActive = true
                    }
                }
            }
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

    /** Expose the current dispatch flag to lifetime fixtures without changing transport state. */
    internal fun isRequestDispatchedForTest(): Boolean {
        check(dev.ipf.whitenoise.android.BuildConfig.DEBUG)
        return requestDispatched && pending != null
    }

    /** Construct only the bounded operation fields and selected descriptor capabilities admitted by this protocol. */
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

    /**
     * Distinguish definite framework refusal from uncertain binding outcomes before admitting or retaining the
     * worker.
     */
    @Suppress("ReturnCount") // Current, late and unsuccessful binding paths have distinct ownership outcomes.
    private fun connect(selected: Worker, ticket: Operation): Boolean {
        val accepted =
            try {
                context.bindIsolatedService(
                    Intent(context, MediaAvDecodeService::class.java),
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

    /**
     * Copy only bounded validated pixels into caller ownership and recycle allocations if the account becomes
     * stale.
     */
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

    /** Retire the transport and its current account sources without discarding an unresolved worker witness. */
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
