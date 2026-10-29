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
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

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

/**
 * One serialized still-image lane with a disposable worker generation per untrusted input. The reply
 * capability is revoked before retirement, the generation is retired after its single request, and its
 * exact Binder death precedes both the parent pixel copy and the next input's admission. Pre-warmed
 * clean spares keep process start off the admission path, see [MediaWorkerPool] for the bounds.
 */
@Suppress("TooManyFunctions") // Cohesive admission, session and descriptor lifecycle for one decoder lane.
internal class IsolatedMediaTransport(
    private val context: Context,
    instanceName: String = "images",
    spares: Int = MediaWorkerPool.DEFAULT_SPARES,
    private val deadlineMs: Long = MediaDecodePolicy.DEADLINE_MS,
) : MediaDecodeTransport,
    java.io.Closeable {
    private val gate = Semaphore(1)
    private val bindingLock = Any()
    private var epoch = 0L

    @Volatile private var session: Session? = Session(epoch, MediaSourceAccess(context))

    @Volatile private var closed = false

    @Volatile private var requestDispatched = false

    @Volatile private var pending: MediaWorker? = null
    private val pool =
        MediaWorkerPool(context, instanceName, spares, bindingLock) { ended ->
            // Called under bindingLock: a disconnected or dead generation can never complete a reply.
            if (pending === ended) revokePending()
        }

    /** Measurement-only queue observer, used by matched lane characterization. */
    @Volatile internal var admissionObserver: MediaAdmissionObserver? = null

    /** Account invalidation never discards a process-death witness. */
    internal fun activateSession() = replaceSession(true)

    internal fun invalidateSession() = replaceSession(false)

    private fun replaceSession(active: Boolean) {
        val (previous, retiring) =
            synchronized(bindingLock) {
                check(!closed || !active) { "A closed decoder cannot be reactivated" }
                val previous = session
                session = if (active) Session(++epoch, MediaSourceAccess(context)) else null
                revokePending()
                // Captured with the session swap, so a generation started for the new session is never retired here.
                // Spares carry no input, but every captured generation predates the new session.
                previous to pool.detachAll()
            }
        previous?.sources?.close()
        retiring.forEach { pool.retire(it) }
    }

    private fun revokePending() {
        pending?.let {
            it.completed.set(true)
            it.result = null
            it.ready.countDown()
        }
        pending = null
    }

    private fun current(ticket: Operation): Boolean = !closed && session?.epoch == ticket.session.epoch

    private fun responseCapability(worker: MediaWorker): Messenger =
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
                    if (pending !== worker || data.dataAvail() > MediaDecodePolicy.MAX_REPLY_BYTES) return false
                    val result =
                        try {
                            data.enforceInterface(MEDIA_REPLY_DESCRIPTOR)
                            if (data.readInt() != worker.generation) return false
                            readMediaDecodeReply(data).apply { putInt("uid", android.os.Binder.getCallingUid()) }
                        } catch (_: RuntimeException) {
                            null
                        }
                    synchronized(bindingLock) {
                        // A retiring generation's capability is already dead, whatever its id.
                        if (closed || pending !== worker || worker.retiring) return false
                        if (worker.completed.compareAndSet(false, true)) {
                            worker.result = result
                            pending = null // Revoke the capability before retirement or another callback.
                            result?.let(IsolatedMediaDecoder::recordIsolation)
                            worker.ready.countDown()
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

    /** Debug-only worker canary: the reply's bounded text field, from one ordinary single-use generation. */
    internal fun debugProbeForTest(
        bytes: ByteArray,
        operation: Int,
    ): String? {
        check(dev.ipf.whitenoise.android.BuildConfig.DEBUG)
        check(operation == MediaDecodePolicy.DEBUG_CANARY || operation == MediaDecodePolicy.DEBUG_IGNORE_RETIRE)
        return serialized { ticket ->
            SharedMemory.create("media-canary", Int.SIZE_BYTES).use { output ->
                request(ticket, bytes, MediaDecodeRequest(1, false, operation), output)?.getString("mime")
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
        val admitted = gate.tryAcquire(deadlineMs, TimeUnit.MILLISECONDS)
        admissionObserver?.admission(System.nanoTime() - queued, admitted)
        return if (!admitted) {
            null
        } else {
            val ticket = Operation(selected, android.os.SystemClock.elapsedRealtime() + deadlineMs)
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

    @Suppress("ReturnCount") // Admission, deadline, death and exact reply shape are separate fail-closed boundaries.
    private fun exchange(
        ticket: Operation,
        input: ParcelFileDescriptor,
        output: SharedMemory,
        request: MediaDecodeRequest,
    ): Bundle? {
        requestDispatched = false
        if (!current(ticket)) return null
        val selected = pool.claim(remaining(ticket)) { current(ticket) } ?: return null
        try {
            val target = admittedTarget(selected, ticket) ?: return null
            target.send(
                Message.obtain().apply {
                    what = MediaDecodePolicy.DECODE
                    arg1 = selected.generation
                    replyTo = responseCapability(selected)
                    data = requestPayload(input, output, request)
                },
            )
            // Warm the next generation while this one serves, the death fence still gates its first input.
            pool.refill()
            if (!await(selected.ready, ticket)) return null
            pool.retire(selected)
            // A revoked or failed reply has no pixels to protect, the next claim still holds the death fence.
            val result = selected.result ?: return null
            // The death barrier precedes the pixel copy, a worker ignoring RETIRE is stopped by Android.
            if (!pool.awaitDeath(selected, remaining(ticket)) || !current(ticket)) return null
            ticket.workerRefused = validIsolation(result) && !result.getBoolean("success")
            return result.takeIf(::validResult)
        } finally {
            synchronized(bindingLock) { if (pending === selected) revokePending() }
            pool.retire(selected)
            pool.dispose(selected)
        }
    }

    private fun admittedTarget(
        selected: MediaWorker,
        ticket: Operation,
    ): Messenger? =
        synchronized(bindingLock) {
            val usable = selected.linked && !selected.retiring && selected.dead.count != 0L
            if (!current(ticket) || !usable || remaining(ticket) == 0L) {
                null
            } else {
                // Descriptor admission linearizes before a concurrent reset.
                requestDispatched = true
                pending = selected
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
        return requestDispatched && pending != null
    }

    /** Live generations, held ones included, never more than one plus the spare limit. */
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
        val refusals = LinkedHashMap<String, Long>()
    }

    private data class Operation(
        val session: Session,
        val deadline: Long,
        var workerRefused: Boolean = false,
    )
}

internal data class MediaDecodeRequest(
    val edge: Int,
    val option: Boolean,
    val operation: Int,
    val offset: Long = 0L,
    val length: Long? = null,
    val region: MediaImageRegion? = null,
)
