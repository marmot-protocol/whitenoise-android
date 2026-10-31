package dev.ipf.whitenoise.android.media.sandbox

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SharedMemory
import android.system.OsConstants
import dev.ipf.whitenoise.android.media.MediaTrustDomain
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

    /**
     * Retires [domain]'s worker, such as a pick removed from a draft or sent. A lane without domain-bound workers
     * has nothing to retire.
     */
    fun retireDomain(domain: MediaTrustDomain) = Unit
}

/**
 * Still-image worker generations bound to data-access domains (#3373). Each request carries the
 * [MediaTrustDomain] set by its caller through [MediaTrustScope]. Requests of one domain are serialized
 * on that domain's own admission and reuse its generation, which never serves another domain; different
 * domains run in parallel within the pool's live limit. A [MediaTrustDomain.Unique] request gets a
 * single-use generation: its reply capability is revoked before retirement, and its exact Binder death
 * precedes both the parent pixel copy and the next single-use input. See [MediaWorkerPool] for the bounds.
 */
@Suppress("TooManyFunctions") // Cohesive admission, session and descriptor lifecycle for one decoder lane.
internal class IsolatedMediaTransport(
    private val context: Context,
    instanceName: String = "images",
    spares: Int = MediaWorkerPool.DEFAULT_SPARES,
    private val deadlineMs: Long = MediaDecodePolicy.DEADLINE_MS,
    liveLimit: Int = 1 + spares.coerceIn(0, MediaWorkerPool.MAX_SPARES),
    /**
     * How much of a request's deadline may pass before dispatch, waiting for a worker, while a missed deadline still
     * counts against its source: a worker then had at least its own watchdog's time to answer.
     */
    private val claimAllowanceMs: Long = MediaDecodePolicy.DEADLINE_MS - MediaDecodePolicy.WATCHDOG_MS,
) : MediaDecodeTransport,
    java.io.Closeable {
    /** Admission for single-use requests, which share one serialized lane. */
    private val gate = Semaphore(1)

    /** Admission per reusable domain, held only while a request of that domain is queued or running. */
    private val lanes = HashMap<MediaTrustDomain, Lane>()

    /** Requests whose output buffer exists; a request waiting for a worker has none yet. */
    private val descriptorHolders = AtomicInteger()
    private val bindingLock = Any()
    private var epoch = 0L

    @Volatile private var session: Session? = Session(epoch, MediaSourceAccess(context))

    @Volatile private var closed = false

    /** Generations with a request in flight, each holding that request's reply capability. */
    private val pending = HashSet<MediaWorker>()
    private val pool =
        MediaWorkerPool(context, instanceName, spares, bindingLock, liveLimit) { ended ->
            // Called under bindingLock: a disconnected or dead generation can never complete a reply.
            revoke(ended)
        }

    /** Measurement-only queue observer, used by matched lane characterization. */
    @Volatile internal var admissionObserver: MediaAdmissionObserver? = null

    /** Account invalidation never discards a process-death witness. */
    internal fun activateSession() = replaceSession(true)

    internal fun invalidateSession() = replaceSession(false)

    /**
     * Retires [domain]'s worker generation, such as a pick removed from a draft or sent: at once when idle, after
     * its request in flight otherwise. A later input of [domain] waits for that generation's death as usual.
     */
    override fun retireDomain(domain: MediaTrustDomain) {
        if (domain != MediaTrustDomain.Unique) pool.retireDomain(domain)
    }

    private fun replaceSession(active: Boolean) {
        val (previous, retiring) =
            synchronized(bindingLock) {
                check(!closed || !active) { "A closed decoder cannot be reactivated" }
                val previous = session
                session = if (active) Session(++epoch, MediaSourceAccess(context)) else null
                pending.toList().forEach(::revoke)
                // Captured with the session swap, so a generation started for the new session is never retired here.
                // Spares carry no input, but every captured generation predates the new session.
                previous to pool.detachAll()
            }
        previous?.sources?.close()
        retiring.forEach { pool.retire(it) }
    }

    /** Revokes [worker]'s in-flight reply capability. Called under bindingLock. */
    private fun revoke(worker: MediaWorker) {
        worker.exchange?.abort()
        worker.exchange = null
        pending.remove(worker)
    }

    private fun current(ticket: Operation): Boolean = !closed && session?.epoch == ticket.session.epoch

    /** A reply capability that accepts exactly one well-formed reply for [exchange] on [worker]. */
    private fun responseCapability(
        worker: MediaWorker,
        exchange: MediaExchange,
    ): Messenger =
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
                    if (worker.exchange !== exchange || data.dataAvail() > MediaDecodePolicy.MAX_REPLY_BYTES) {
                        return false
                    }
                    val result =
                        try {
                            data.enforceInterface(MEDIA_REPLY_DESCRIPTOR)
                            if (data.readInt() != exchange.id) return false
                            readMediaDecodeReply(data).apply { putInt("uid", android.os.Binder.getCallingUid()) }
                        } catch (_: RuntimeException) {
                            null
                        }
                    synchronized(bindingLock) {
                        // A retiring generation's capability is already dead, whatever its id.
                        if (closed || worker.exchange !== exchange || worker.retiring) return false
                        if (exchange.completed.compareAndSet(false, true)) {
                            exchange.result = result
                            // Revoke the capability before retirement, reuse or another callback.
                            worker.exchange = null
                            pending.remove(worker)
                            result?.let(IsolatedMediaDecoder::recordIsolation)
                            exchange.ready.countDown()
                        }
                    }
                    return true
                }
            },
        )

    override fun decode(
        bytes: ByteArray,
        edge: Int,
        oriented: Boolean,
        operation: Int,
    ): Bitmap? =
        serialized(MediaTrustScope.current()) { ticket ->
            val capacity = MediaDecodePolicy.outputCapacity(edge) ?: return@serialized null
            val spec = MediaDecodeRequest(edge, oriented, operation)
            request(ticket, bytes, spec, "media-pixels", capacity) { result, output ->
                copyPixels(ticket, result, output, edge)
            }
        }

    override fun exif(bytes: ByteArray): Int? =
        serialized(MediaTrustScope.current()) { ticket ->
            val spec = MediaDecodeRequest(1, false, MediaDecodePolicy.EXIF)
            request(ticket, bytes, spec, "media-exif", Int.SIZE_BYTES) { result, _ ->
                result.getInt("orientation").takeIf { it in 1..MediaDecodePolicy.MAX_EXIF_ORIENTATION }
            }
        }

    override fun region(
        bytes: ByteArray,
        region: MediaImageRegion,
        edge: Int,
    ): Bitmap? =
        serialized(MediaTrustScope.current()) { ticket ->
            val capacity = MediaDecodePolicy.outputCapacity(edge) ?: return@serialized null
            if (!claimWorker(ticket)) return@serialized null
            withOutput("media-region", capacity) { output ->
                ticket.session.sources.withInput(bytes) { input ->
                    val request = MediaDecodeRequest(edge, false, MediaDecodePolicy.REGION, region = region)
                    exchange(ticket, input, output, request)
                        ?.let { copyPixels(ticket, it, output, edge) }
                }
            }
        }

    override fun inspect(bytes: ByteArray): ImageInspection? =
        serialized(MediaTrustScope.current()) { ticket ->
            val spec = MediaDecodeRequest(1, false, MediaDecodePolicy.INSPECT)
            request(ticket, bytes, spec, "media-inspection", Int.SIZE_BYTES) { result, _ ->
                val width = result.getInt("width")
                val height = result.getInt("height")
                if (width <= 0 || height <= 0) {
                    null
                } else {
                    ImageInspection(
                        width,
                        height,
                        result.getString("mime").orEmpty().take(MediaDecodePolicy.MAX_MIME_CHARS),
                        result.getInt("orientation"),
                    )
                }
            }
        }

    override fun decodeUri(
        resolver: android.content.ContentResolver,
        uri: android.net.Uri,
        edge: Int,
    ): Bitmap? {
        val domain = MediaTrustScope.current()
        val selected = session ?: return null
        return selected.sources.withUriInput(resolver, uri) { input ->
            serialized(domain, selected) { ticket ->
                val capacity = MediaDecodePolicy.outputCapacity(edge) ?: return@serialized null
                if (!claimWorker(ticket)) return@serialized null
                withOutput("media-pixels", capacity) { output ->
                    exchange(ticket, input, output, MediaDecodeRequest(edge, true, MediaDecodePolicy.DECODE))
                        ?.let { copyPixels(ticket, it, output, edge) }
                }
            }
        }
    }

    /** Debug-only worker canary: the reply's bounded text field, from one ordinary single-use generation. */
    internal fun debugProbeForTest(
        bytes: ByteArray,
        operation: Int,
    ): String? {
        check(dev.ipf.whitenoise.android.BuildConfig.DEBUG)
        check(operation == MediaDecodePolicy.DEBUG_CANARY || operation == MediaDecodePolicy.DEBUG_IGNORE_RETIRE)
        return serialized(MediaTrustScope.current()) { ticket ->
            val spec = MediaDecodeRequest(1, false, operation)
            request(ticket, bytes, spec, "media-canary", Int.SIZE_BYTES) { result, _ -> result.getString("mime") }
        }
    }

    /**
     * Admits one request of [domain]: single-use requests share one serialized lane, each reusable domain
     * has its own, so a queue in one conversation never delays another domain's admission.
     */
    @Suppress("ReturnCount") // Closed, unadmitted, stale and completed requests leave separately.
    private fun <T> serialized(
        domain: MediaTrustDomain,
        selected: Session? = session,
        block: (Operation) -> T?,
    ): T? {
        if (selected == null || closed) return null
        val lane = if (domain == MediaTrustDomain.Unique) null else synchronized(lanes) { enterLane(domain) }
        val admission = lane?.gate ?: gate
        try {
            // Queue admission has its own bound; it never spends the admitted exchange's budget.
            val queued = System.nanoTime()
            if (!admission.tryAcquire(deadlineMs, TimeUnit.MILLISECONDS)) {
                admissionObserver?.admission(System.nanoTime() - queued, false)
                return null
            }
            admissionObserver?.admission(System.nanoTime() - queued, true)
            try {
                return admittedRequest(selected, domain, block)
            } finally {
                admission.release()
            }
        } finally {
            lane?.let { synchronized(lanes) { leaveLane(domain, it) } }
        }
    }

    /**
     * Runs one admitted request under its own exchange deadline, discarding a result from a replaced session. A
     * worker the request claimed but never sent input to goes back unused.
     */
    private fun <T> admittedRequest(
        selected: Session,
        domain: MediaTrustDomain,
        block: (Operation) -> T?,
    ): T? {
        val ticket = Operation(selected, domain, android.os.SystemClock.elapsedRealtime() + deadlineMs)
        if (!current(ticket)) return null
        try {
            val result = block(ticket)
            return if (current(ticket)) {
                result
            } else {
                (result as? Bitmap)?.recycle()
                null
            }
        } finally {
            ticket.worker?.let { worker -> if (!ticket.dispatched) pool.unclaim(worker) }
        }
    }

    /**
     * Claims the request's worker before it creates any descriptor, so a request waiting for a worker holds none
     * however many domains wait. A reusable domain gets its own generation, Unique a single-use one.
     */
    private fun claimWorker(ticket: Operation): Boolean {
        val reusable = ticket.domain != MediaTrustDomain.Unique
        val key: Any = if (reusable) ticket.domain else SingleUseInput()
        val worker = pool.claim(key, reusable, remaining(ticket)) { current(ticket) } ?: return false
        ticket.worker = worker
        return true
    }

    /** Creates one request's output buffer, counted for as long as it exists. */
    private fun <T> withOutput(
        name: String,
        capacity: Int,
        block: (SharedMemory) -> T?,
    ): T? {
        descriptorHolders.incrementAndGet()
        try {
            return SharedMemory.create(name, capacity).use(block)
        } finally {
            descriptorHolders.decrementAndGet()
        }
    }

    /** Joins [domain]'s admission lane, creating it on first use. Called under the lanes lock. */
    private fun enterLane(domain: MediaTrustDomain): Lane = lanes.getOrPut(domain, ::Lane).also { it.users++ }

    /** Leaves [lane], dropping it once no request of [domain] is queued or running. Called under the lanes lock. */
    private fun leaveLane(
        domain: MediaTrustDomain,
        lane: Lane,
    ) {
        if (--lane.users == 0) lanes.remove(domain)
    }

    /**
     * Whether a request of the current session failed after dispatch in a way that says something about its source:
     * always, unless its reply wait ran out after waiting for a worker had used more than [claimAllowanceMs] of its
     * deadline.
     */
    private fun failureIsEvidence(ticket: Operation): Boolean {
        if (!ticket.dispatched || !current(ticket)) return false
        val waitedMs = deadlineMs - ticket.dispatchBudgetMs
        return !ticket.replyTimedOut || waitedMs <= claimAllowanceMs
    }

    private fun remaining(ticket: Operation): Long {
        val timeLeft = ticket.deadline - android.os.SystemClock.elapsedRealtime()
        return timeLeft.coerceAtLeast(0L)
    }

    private fun await(
        latch: CountDownLatch,
        ticket: Operation,
    ): Boolean = latch.await(remaining(ticket), TimeUnit.MILLISECONDS)

    /**
     * Runs one byte-sourced request under the session's refusal cooldown and records new failures. A refused
     * source claims no worker; otherwise the worker is claimed before the [outputName] buffer and input exist,
     * and [use] reads a valid result while the buffer is still mapped.
     */
    @Suppress("ReturnCount", "LongParameterList") // Reject resource and cooldown failures before binding.
    private fun <T> request(
        ticket: Operation,
        bytes: ByteArray,
        spec: MediaDecodeRequest,
        outputName: String,
        capacity: Int,
        use: (Bundle, SharedMemory) -> T?,
    ): T? {
        val limit = MediaDecodePolicy.MAX_INPUT_BYTES.toLong()
        if (bytes.size.toLong() !in 1..limit || !current(ticket)) return null
        val digest =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(bytes)
        val key = "${spec.operation}:${spec.edge}:${spec.option}:" + digest.joinToString("") { "%02x".format(it) }
        val now = android.os.SystemClock.elapsedRealtime()
        if (ticket.session.refusals.refused(key, now)) return null
        if (!claimWorker(ticket)) return null
        return withOutput(outputName, capacity) { output ->
            val result =
                ticket.session.sources.withInput(bytes) { input ->
                    exchange(ticket, input, output, spec)
                }
            // Any dispatched request without a valid result (crash, watchdog kill, deadline miss or refusal)
            // quarantines these bytes, as the reusable lane does. Interruption and reset never reach here. A missed
            // deadline counts only when the worker had its full time: a long wait for a worker is not the source's.
            if (result == null && failureIsEvidence(ticket)) {
                recordRefusal(ticket.session, key, android.os.SystemClock.elapsedRealtime())
            }
            result?.let { use(it, output) }
        }
    }

    /**
     * Sends one request to its domain's generation and waits for the reply. A reusable domain keeps its
     * generation after a valid reply, and the pixels are copied while it lives, as only that domain's own
     * worker can touch them. Any other outcome retires it. A single-use generation is retired after its reply,
     * and its exact Binder death precedes the pixel copy.
     */
    @Suppress("ReturnCount") // Admission, deadline, death and exact reply shape are separate fail-closed boundaries.
    private fun exchange(
        ticket: Operation,
        input: ParcelFileDescriptor,
        output: SharedMemory,
        request: MediaDecodeRequest,
    ): Bundle? {
        if (!current(ticket)) return null
        val reusable = ticket.domain != MediaTrustDomain.Unique
        val selected = checkNotNull(ticket.worker)
        val exchange = MediaExchange(requestSequence.incrementAndGet())
        var keep = false
        try {
            val target = admittedTarget(selected, exchange, ticket) ?: return null
            target.send(
                Message.obtain().apply {
                    what = MediaDecodePolicy.DECODE
                    arg1 = exchange.id
                    replyTo = responseCapability(selected, exchange)
                    data = requestPayload(input, output, request)
                },
            )
            // Warm a spare while this generation serves, a new domain or single-use input takes it.
            pool.refill()
            if (!await(exchange.ready, ticket)) {
                ticket.replyTimedOut = true
                return null
            }
            val result = exchange.result ?: return null
            if (reusable) {
                keep = validResult(result) && current(ticket)
                return result.takeIf { keep }
            }
            pool.retire(selected)
            // The death barrier precedes the pixel copy, a worker ignoring RETIRE is stopped by Android.
            if (!pool.awaitDeath(selected, remaining(ticket)) || !current(ticket)) return null
            return result.takeIf(::validResult)
        } finally {
            synchronized(bindingLock) { if (selected.exchange === exchange) revoke(selected) }
            // An undispatched worker received no input; the admitted request returns it unused.
            if (keep) {
                pool.release(selected)
            } else if (ticket.dispatched) {
                pool.retire(selected)
                pool.dispose(selected)
            }
        }
    }

    /** The live target for [selected], or null, marking the ticket dispatched once descriptors may leave. */
    private fun admittedTarget(
        selected: MediaWorker,
        exchange: MediaExchange,
        ticket: Operation,
    ): Messenger? =
        synchronized(bindingLock) {
            val usable = selected.linked && !selected.retiring && selected.dead.count != 0L
            if (!current(ticket) || !usable || remaining(ticket) == 0L) {
                null
            } else {
                // Descriptor admission linearizes before a concurrent reset.
                ticket.dispatched = true
                ticket.dispatchBudgetMs = remaining(ticket)
                selected.served = true
                selected.exchange = exchange
                pending += selected
                selected.remote
            }
        }

    private fun validResult(result: Bundle): Boolean = result.getBoolean("success") && validIsolation(result)

    private fun validIsolation(result: Bundle): Boolean {
        val isolated = result.getInt("uid") != Process.myUid() && result.getBoolean("isolated")
        val restricted = !result.getBoolean("internetGranted") && !result.getBoolean("runtimeInitialized")
        return isolated && restricted
    }

    internal fun isRequestDispatchedForTest(): Boolean {
        check(dev.ipf.whitenoise.android.BuildConfig.DEBUG)
        return synchronized(bindingLock) { pending.isNotEmpty() }
    }

    /** Requests now holding an output buffer; only a request that already has its worker holds one. */
    internal fun descriptorHoldersForTest(): Int {
        check(dev.ipf.whitenoise.android.BuildConfig.DEBUG)
        return descriptorHolders.get()
    }

    /** Live generations, held ones included, never more than the live limit. */
    internal fun liveGenerationsForTest(): Int {
        check(dev.ipf.whitenoise.android.BuildConfig.DEBUG)
        return pool.liveGenerationsForTest()
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

    /** Quarantines [key] in [selected] for the refusal period from [now]; the ledger holds its own lock. */
    private fun recordRefusal(
        selected: Session,
        key: String,
        now: Long,
    ) = selected.refusals.refuse(key, now + MediaDecodePolicy.REFUSAL_TTL_MS)

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
        pool.close()
    }

    private class Session(
        val epoch: Long,
        val sources: MediaSourceAccess,
    ) {
        // Shared by every domain lane of the session, so it is a locked ledger rather than a bare map.
        val refusals = MediaRefusalLedger()
    }

    private data class Operation(
        val session: Session,
        val domain: MediaTrustDomain,
        val deadline: Long,
        var dispatched: Boolean = false,
    ) {
        /** The generation this request claimed before creating any descriptor. */
        var worker: MediaWorker? = null

        /** What was left of [deadline] when the request was dispatched. */
        var dispatchBudgetMs: Long = 0L

        /** Whether the worker's reply did not arrive before [deadline]. */
        var replyTimedOut: Boolean = false
    }

    /** One reusable domain's admission, kept while any of its requests is queued or running. */
    private class Lane {
        val gate = Semaphore(1)
        var users = 0
    }

    /** A pool key no other request shares, so a single-use generation is never claimed again. */
    private class SingleUseInput

    private companion object {
        val requestSequence = AtomicInteger()
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
