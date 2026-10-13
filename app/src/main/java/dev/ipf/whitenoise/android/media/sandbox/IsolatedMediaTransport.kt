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

/** One serialized reusable worker. A live worker that misses its deadline poisons further admission. */
@Suppress("TooManyFunctions") // Cohesive binding, admission and descriptor lifecycle for one decoder lane.
internal class IsolatedMediaTransport(
    private val context: Context,
    private val instanceName: String = "images",
) : MediaDecodeTransport,
    java.io.Closeable {
    private val gate = Semaphore(1)
    private val bindingLock = Any()

    @Volatile private var requestDispatched = false
    private val ids = AtomicInteger()
    private val callbackThread = lazy { HandlerThread("media-decoder-ipc").apply { start() } }
    private val callbacks by callbackThread
    private val handler by lazy { Handler(callbacks.looper) }
    private val callbackExecutor = java.util.concurrent.Executor { command -> handler.post(command) }
    private val sources = MediaSourceAccess(context)

    @Volatile private var remote: Messenger? = null

    @Volatile private var poisoned = false

    @Volatile private var startupTimedOut = false

    @Volatile private var closed = false

    private var bindingLost = false

    @Volatile private var pending: Reply? = null

    private fun responseCapability(current: Reply): Messenger =
        Messenger(
            object : android.os.Binder() {
                @Suppress("ReturnCount") // Reject unrelated, oversized or retired callback capabilities before parsing.
                override fun onTransact(
                    code: Int,
                    data: android.os.Parcel,
                    reply: android.os.Parcel?,
                    flags: Int,
                ): Boolean {
                    if (code != IBinder.FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
                    if (closed ||
                        pending !== current ||
                        data.dataAvail() > MediaDecodePolicy.MAX_REPLY_BYTES
                    ) {
                        return false
                    }
                    val result =
                        try {
                            data.enforceInterface(MEDIA_REPLY_DESCRIPTOR)
                            if (data.readInt() != current.id) return false
                            readMediaDecodeReply(data).apply { putInt("uid", android.os.Binder.getCallingUid()) }
                        } catch (_: RuntimeException) {
                            null
                        }
                    if (!closed && pending === current && current.completed.compareAndSet(false, true)) {
                        current.result = result
                        result?.let(IsolatedMediaDecoder::recordIsolation)
                        current.ready.countDown()
                    }
                    return true
                }
            },
        )

    @Volatile private var connection: ServiceConnection? = null

    private fun newConnection(): ServiceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName,
                service: IBinder,
            ) {
                synchronized(bindingLock) {
                    if (closed || connection !== this) return
                    remote = Messenger(service)
                    bindingLost = false
                    if (startupTimedOut) {
                        startupTimedOut = false
                        poisoned = false // No codec request was sent; this same late connection is reusable.
                    }
                    pending?.connected?.countDown()
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                synchronized(bindingLock) {
                    if (closed || connection !== this) return
                    remote = null
                    bindingLost = true
                    poisoned = false // Only a current system death notification reopens admission.
                    pending?.ready?.countDown()
                    pending?.connected?.countDown()
                }
            }

            override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
        }

    private var bound = false
    private val refusals = LinkedHashMap<String, Long>()

    override fun decode(
        bytes: ByteArray,
        edge: Int,
        oriented: Boolean,
        operation: Int,
    ): Bitmap? =
        serialized {
            val capacity = MediaDecodePolicy.outputCapacity(edge) ?: return@serialized null
            SharedMemory.create("media-pixels", capacity).use { output ->
                val result = request(bytes, edge, oriented, operation, output) ?: return@serialized null
                copyPixels(result, output, edge)
            }
        }

    override fun exif(bytes: ByteArray): Int? =
        serialized {
            SharedMemory.create("media-exif", Int.SIZE_BYTES).use { output ->
                request(bytes, 1, false, MediaDecodePolicy.EXIF, output)?.getInt("orientation")?.takeIf {
                    it in
                        1..MediaDecodePolicy.MAX_EXIF_ORIENTATION
                }
            }
        }

    override fun inspect(bytes: ByteArray): ImageInspection? =
        serialized {
            SharedMemory.create("media-inspection", Int.SIZE_BYTES).use { output ->
                val result = request(bytes, 1, false, MediaDecodePolicy.INSPECT, output) ?: return@serialized null
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
    ): Bitmap? =
        sources.withUriInput(resolver, uri) { input ->
            serialized {
                val capacity = MediaDecodePolicy.outputCapacity(edge) ?: return@serialized null
                SharedMemory.create("media-pixels", capacity).use { output ->
                    exchange(input, output, MediaDecodeRequest(edge, true, MediaDecodePolicy.DECODE))
                        ?.let { copyPixels(it, output, edge) }
                }
            }
        }

    private fun <T> serialized(block: () -> T?): T? {
        if (closed || poisoned || !gate.tryAcquire(MediaDecodePolicy.DEADLINE_MS, TimeUnit.MILLISECONDS)) return null
        return try {
            if (closed || poisoned) null else block()
        } finally {
            gate.release()
        }
    }

    @Suppress("ReturnCount") // Reject resource and cooldown failures before binding.
    private fun request(
        bytes: ByteArray,
        edge: Int,
        oriented: Boolean,
        operation: Int,
        output: SharedMemory,
    ): Bundle? {
        val limit = MediaDecodePolicy.MAX_INPUT_BYTES.toLong()
        if (bytes.size.toLong() !in 1..limit || poisoned) return null
        val digest =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(bytes)
        val key = "$operation:$edge:$oriented:" + digest.joinToString("") { "%02x".format(it) }
        val now = android.os.SystemClock.elapsedRealtime()
        if ((refusals[key] ?: 0L) > now) return null
        val result =
            sources.withInput(bytes) { input -> exchange(input, output, MediaDecodeRequest(edge, oriented, operation)) }
        if (result == null && requestDispatched) recordRefusal(key, now)
        return result
    }

    @Suppress("ReturnCount") // Connection, deadline and exact reply shape are separate fail-closed boundaries.
    private fun exchange(
        input: ParcelFileDescriptor,
        output: SharedMemory,
        request: MediaDecodeRequest,
    ): Bundle? {
        if (closed) return null
        requestDispatched = false
        val reply = Reply(ids.incrementAndGet())
        try {
            pending = reply
            if (!connect(reply)) return null
            val target = remote ?: return null
            target.send(
                Message.obtain().apply {
                    what = MediaDecodePolicy.DECODE
                    arg1 = reply.id
                    replyTo = responseCapability(reply)
                    data = requestPayload(input, output, request)
                },
            )
            requestDispatched = true
            if (!reply.ready.await(MediaDecodePolicy.DEADLINE_MS, TimeUnit.MILLISECONDS)) {
                retireCurrentWorker()
                return null
            }
            return reply.result?.takeIf {
                !closed &&
                    it.getBoolean("success") &&
                    it.getInt("uid") != Process.myUid() &&
                    it.getBoolean("isolated") &&
                    !it.getBoolean("internetGranted") &&
                    !it.getBoolean("runtimeInitialized")
            }
        } catch (interrupted: InterruptedException) {
            if (requestDispatched && !reply.completed.get()) {
                retireCurrentWorker()
            }
            throw interrupted
        } finally {
            pending = null
        }
    }

    private fun retireCurrentWorker() {
        val target =
            synchronized(bindingLock) {
                val current = remote?.takeUnless { closed || bindingLost }
                poisoned = current?.binder?.isBinderAlive == true
                current?.takeIf { poisoned }
            }
        // Never write poison after the death callback has cleared it. A healthy late worker must
        // retire too: completing its request would otherwise cancel the watchdog without reopening admission.
        runCatching { target?.send(Message.obtain().apply { what = MediaDecodePolicy.RETIRE }) }
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
            putParcelable("input", input)
            putParcelable("output", output)
        }

    private fun recordRefusal(
        key: String,
        now: Long,
    ) {
        refusals[key] = now + MediaDecodePolicy.REFUSAL_TTL_MS
        while (refusals.size > MediaDecodePolicy.MAX_REFUSALS) refusals.remove(refusals.keys.first())
    }

    @Suppress("ReturnCount") // Current, late and unsuccessful binding paths have distinct ownership outcomes.
    private fun connect(reply: Reply): Boolean {
        synchronized(bindingLock) {
            if (closed) return false
            if (remote != null) return true
            if (bound) connection?.let(context::unbindService)
            val selected = newConnection()
            connection = selected
            bindingLost = false
            bound =
                context.bindIsolatedService(
                    Intent(context, MediaDecodeService::class.java),
                    Context.BIND_AUTO_CREATE,
                    workerInstanceName(instanceName, bindingSequence.incrementAndGet()),
                    callbackExecutor,
                    selected,
                )
        }
        val connected =
            bound && reply.connected.await(MediaDecodePolicy.DEADLINE_MS, TimeUnit.MILLISECONDS) && remote != null
        if (bound && !connected) {
            synchronized(bindingLock) {
                if (closed) return false
                // Close the race with a callback immediately after the await timed out.
                if (remote != null) return true
                if (!bindingLost) {
                    startupTimedOut = true
                    poisoned = true
                }
            }
        }
        return connected
    }

    // Failed copies recycle their allocation before propagation.
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private fun copyPixels(
        result: Bundle,
        output: SharedMemory,
        edge: Int,
    ): Bitmap? {
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
                bitmap
            } catch (failure: Exception) {
                bitmap.recycle()
                throw failure
            }
        } finally {
            SharedMemory.unmap(pixels)
        }
    }

    override fun close() {
        closed = true
        poisoned = true
        sources.close()
        pending?.ready?.countDown()
        pending?.connected?.countDown()
        synchronized(bindingLock) {
            val previous = connection
            connection = null
            if (bound && previous != null) context.unbindService(previous)
            bound = false
            remote = null
        }
        if (callbackThread.isInitialized()) callbacks.quitSafely()
    }

    companion object {
        private val bindingSequence = AtomicInteger()
    }

    private class Reply(
        val id: Int,
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
)
