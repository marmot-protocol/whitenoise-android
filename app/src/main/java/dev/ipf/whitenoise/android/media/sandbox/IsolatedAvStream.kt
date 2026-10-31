@file:Suppress("MagicNumber") // Versioned primitive layout and fixed raw-output limits.

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
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SharedMemory
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal sealed interface AvStreamFrame {
    data class Video(
        val bitmap: Bitmap,
        val timestampUs: Long,
    ) : AvStreamFrame

    data class Audio(
        val bytes: ByteArray,
        val sampleRate: Int,
        val channels: Int,
        val timestampUs: Long,
    ) : AvStreamFrame

    data object End : AvStreamFrame

    data object VideoEnd : AvStreamFrame

    data object AudioEnd : AvStreamFrame
}

/**
 * One app-process playback capability. Parsing and decoded buffers remain in the isolated worker;
 * each read returns an owned bounded copy. EOF retains the session so callers can seek or repeat.
 */
@Suppress("TooManyFunctions") // One endpoint, its primitive protocol and its complete lifetime owner.
internal class IsolatedAvStream private constructor(
    private val context: Context,
    private val wantVideo: Boolean,
    private val wantAudio: Boolean,
    private val edge: Int,
    private val ownerEpoch: Long,
    private val lane: AvStreamLane,
) : AutoCloseable {
    private val token = UUID.randomUUID().toString().replace("-", "")
    private val instance = ids.incrementAndGet()
    private val lifetime = AvStreamLifetime()
    private val processWitness = KernelProcessWitness()
    private val parentResources = MediaParentResources()
    private val sources = MediaSourceAccess(context, parentResources)
    private val descriptorCleanupStarted = AtomicBoolean()
    private val observationScheduled = AtomicBoolean()
    private var observationPolls = 0
    private val operationLock = Any()
    private val stateLock = Any()
    private val connected = CountDownLatch(1)
    private val released = CountDownLatch(1)
    private val cleaned = AtomicBoolean()
    private val retireSent = AtomicBoolean()
    private val cleanupScheduled = AtomicBoolean()
    private val rpc = Executors.newSingleThreadExecutor()
    private val retirement = Executors.newSingleThreadExecutor()
    private val callbacks = HandlerThread("media-av-stream-ipc").apply { start() }
    private val callbackHandler = Handler(callbacks.looper)
    private var video: MediaParentResource<SharedMemory>? = null
    private var audio: MediaParentResource<SharedMemory>? = null
    private var input: MediaParentResource<ParcelFileDescriptor>? = null
    private var sequence = 0L
    private var bindingAttempted = false
    private var deathRegistered = false
    private var readyIdentity: Pair<Int, Int>? = null

    @Volatile private var endpoint: IBinder? = null
    private var sessionInfo: AvStreamInfo? = null

    @Volatile var isolation: WorkerIsolation? = null
        private set

    val info: AvStreamInfo get() = checkNotNull(sessionInfo)

    private val death =
        IBinder.DeathRecipient {
            lifetime.deathObserved()
            lifetime.close()
            connected.countDown()
            finishIfPossible()
        }

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName,
                service: IBinder,
            ) {
                synchronized(stateLock) {
                    if (endpoint != null) return
                    endpoint = service
                }
                try {
                    service.linkToDeath(death, 0)
                    synchronized(stateLock) { deathRegistered = true }
                } catch (_: android.os.RemoteException) {
                    if (!service.isBinderAlive) lifetime.deathObserved()
                    lifetime.close()
                } catch (_: RuntimeException) {
                    lifetime.close() // A failed death registration never grants replacement admission.
                }
                connected.countDown()
                if (lifetime.closed) retire()
                finishIfPossible()
            }

            override fun onServiceDisconnected(name: ComponentName) = unavailable()

            override fun onBindingDied(name: ComponentName) = unavailable()

            override fun onNullBinding(name: ComponentName) = unavailable()
        }

    private val readyCallback =
        object : Binder() {
            // Primitive READY validation and exact endpoint admission stay in one atomic Binder transaction.
            @Suppress("ReturnCount", "TooGenericExceptionCaught", "LongMethod", "CyclomaticComplexMethod")
            override fun onTransact(
                code: Int,
                data: Parcel,
                reply: Parcel?,
                flags: Int,
            ): Boolean {
                if (code != AvStreamProtocol.READY || reply == null || flags != 0) return false
                val accepted =
                    try {
                        require(data.dataSize() <= AvStreamProtocol.MAX_REPLY_BYTES && !data.hasFileDescriptors())
                        data.enforceInterface(AvStreamProtocol.READY_DESCRIPTOR)
                        val received = data.readString()
                        val isolated = AvStreamProtocol.boolean(data)
                        val network = AvStreamProtocol.boolean(data)
                        val initialized = AvStreamProtocol.boolean(data)
                        val canaryErrno = data.readInt()
                        val writeErrno = data.readInt()
                        require(data.dataAvail() == 0)
                        val uid = Binder.getCallingUid()
                        val pid = Binder.getCallingPid()
                        synchronized(stateLock) {
                            val current =
                                !lifetime.closed &&
                                    endpoint != null &&
                                    deathRegistered &&
                                    readyIdentity == null &&
                                    admissions.isCurrent(lane, ownerEpoch, this@IsolatedAvStream)
                            val debugWriteDenied =
                                !dev.ipf.whitenoise.android.BuildConfig.DEBUG || writeErrno == OsConstants.EBADF
                            val valid =
                                received == token &&
                                    isolated &&
                                    !network &&
                                    !initialized &&
                                    uid > 0 &&
                                    uid != Process.myUid() &&
                                    pid > 0 &&
                                    debugWriteDenied &&
                                    processWitness.admit(uid, pid)
                            if (current && valid) {
                                readyIdentity = uid to pid
                                val canaryDenied =
                                    canaryErrno in setOf(OsConstants.EACCES, OsConstants.EPERM, OsConstants.ENOENT)
                                isolation =
                                    WorkerIsolation(
                                        uid,
                                        isolated,
                                        network,
                                        initialized,
                                        canaryDenied,
                                        canaryErrno,
                                        writeErrno,
                                    )
                                IsolatedMediaDecoder.recordIsolation(
                                    android.os.Bundle().apply {
                                        putInt("uid", uid)
                                        putBoolean("isolated", isolated)
                                        putBoolean("internetGranted", network)
                                        putBoolean("runtimeInitialized", initialized)
                                        putBoolean("privateCanaryDenied", canaryDenied)
                                        putInt("privateCanaryErrno", canaryErrno)
                                        putInt("inputWriteErrno", writeErrno)
                                    },
                                )
                                true
                            } else {
                                false
                            }
                        }
                    } catch (_: Exception) {
                        false
                    }
                if (!accepted) {
                    close()
                    return false
                }
                reply.writeInt(1)
                return true
            }
        }

    private fun unavailable() {
        // Disconnect/null callbacks are availability information, not an exact endpoint death witness.
        lifetime.close()
        connected.countDown()
        retire()
        finishIfPossible()
    }

    private fun initialize(selected: ParcelFileDescriptor) =
        call {
            require(selected.statSize in 1..AvStreamProtocol.MAX_SOURCE_BYTES)
            val mode = Os.fcntlInt(selected.fileDescriptor, OsConstants.F_GETFL, 0) and OsConstants.O_ACCMODE
            require(mode == OsConstants.O_RDONLY)
            input = parentResources.own(ParcelFileDescriptor.dup(selected.fileDescriptor))
            if (wantVideo) {
                video =
                    parentResources.own(SharedMemory.create("media-av-video", AvStreamProtocol.pixelCapacity(edge)))
            }
            if (wantAudio) {
                audio =
                    parentResources.own(SharedMemory.create("media-av-audio", AvStreamProtocol.PCM_BYTES))
            }
            bind()
            if (!connected.await(AvStreamProtocol.CALL_DEADLINE_MS, TimeUnit.MILLISECONDS)) {
                throw IOException("Media worker startup timed out")
            }
            synchronized(stateLock) {
                if (lifetime.closed ||
                    endpoint == null ||
                    !deathRegistered
                ) {
                    throw IOException("Media worker unavailable")
                }
            }
            val values =
                transact(AvStreamProtocol.OPEN, 6) { data ->
                    data.writeInt(edge)
                    data.writeInt(if (wantVideo) 1 else 0)
                    data.writeInt(if (wantAudio) 1 else 0)
                    checkNotNull(input).value.writeToParcel(data, 0)
                    video?.value?.writeToParcel(data, 0)
                    audio?.value?.writeToParcel(data, 0)
                    data.writeStrongBinder(readyCallback)
                }
            synchronized(stateLock) { check(readyIdentity != null && !lifetime.closed) }
            sessionInfo = avStreamInfo(values)
            require((wantVideo && info.hasVideo) || (wantAudio && info.hasAudio))
        }

    private fun bind() {
        bindingAttempted = true
        val accepted =
            try {
                context.bindIsolatedService(
                    Intent(context, MediaAvPlaybackService::class.java),
                    Context.BIND_AUTO_CREATE,
                    workerInstanceName(if (lane == AvStreamLane.Playback) "av_playback" else "av_analysis", instance),
                    { command -> callbackHandler.post(command) },
                    connection,
                )
            } catch (failure: RuntimeException) {
                lifetime.bindOutcomeUnknown()
                lifetime.close()
                throw IOException("Media worker binding outcome is unknown", failure)
            }
        val observedBinding = synchronized(stateLock) { endpoint != null || accepted }
        lifetime.bindCompleted(observedBinding)
        if (!accepted) throw IOException("Media worker binding refused")
    }

    fun read(preferred: Int = AvStreamProtocol.ANY_TRACK): AvStreamFrame =
        call {
            require(preferred in AvStreamProtocol.ANY_TRACK..AvStreamProtocol.AUDIO_TRACK)
            require(preferred != AvStreamProtocol.VIDEO_TRACK || wantVideo)
            require(preferred != AvStreamProtocol.AUDIO_TRACK || wantAudio)
            val values = transact(AvStreamProtocol.READ, 7) { it.writeInt(preferred) }
            val output = AvStreamOutput.from(values, edge, wantVideo, wantAudio)
            require(preferred != AvStreamProtocol.VIDEO_TRACK || output.kind in setOf(1, 3))
            require(preferred != AvStreamProtocol.AUDIO_TRACK || output.kind in setOf(2, 4))
            require(preferred != AvStreamProtocol.ANY_TRACK || output.kind in 0..2)
            when (output.kind) {
                0 -> AvStreamFrame.End
                3 -> AvStreamFrame.VideoEnd
                4 -> AvStreamFrame.AudioEnd
                1 -> {
                    val memory = checkNotNull(video).value
                    val source = memory.mapReadOnly()
                    try {
                        source.limit(output.byteCount)
                        val bitmap = Bitmap.createBitmap(output.width, output.height, Bitmap.Config.ARGB_8888)
                        var retained = false
                        try {
                            check(bitmap.byteCount == output.byteCount)
                            bitmap.copyPixelsFromBuffer(source)
                            if (lifetime.closed || !admissions.isCurrent(lane, ownerEpoch, this)) {
                                throw IOException("Media session retired during output copy")
                            }
                            AvStreamFrame.Video(bitmap, output.timestampUs).also { retained = true }
                        } finally {
                            if (!retained) bitmap.recycle()
                        }
                    } finally {
                        SharedMemory.unmap(source)
                    }
                }
                else -> {
                    val source = checkNotNull(audio).value.mapReadOnly()
                    try {
                        val bytes = ByteArray(output.byteCount)
                        source.limit(output.byteCount)
                        source.get(bytes)
                        if (lifetime.closed || !admissions.isCurrent(lane, ownerEpoch, this)) {
                            throw IOException("Media session retired during output copy")
                        }
                        AvStreamFrame.Audio(bytes, output.rate, output.channels, output.timestampUs)
                    } finally {
                        SharedMemory.unmap(source)
                    }
                }
            }
        }

    fun seek(positionUs: Long) =
        call {
            require(positionUs in 0..AvStreamProtocol.MAX_DURATION_US)
            transact(AvStreamProtocol.SEEK, 0) { data -> data.writeLong(positionUs) }
            Unit
        }

    @Suppress("TooGenericExceptionCaught") // All failed IPC/copy paths revoke the exact media capability.
    private fun <T> call(action: () -> T): T {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Media IPC must run off the main thread" }
        synchronized(operationLock) {
            if (!admissions.isCurrent(lane, ownerEpoch, this)) {
                close()
                throw IOException("Media account generation changed")
            }
            lifetime.beginCall()
            try {
                val result = action()
                if (!admissions.accept(lane, ownerEpoch, this) { !lifetime.closed }) {
                    (result as? AvStreamFrame.Video)?.bitmap?.recycle()
                    throw IOException("Media account generation changed")
                }
                return result
            } catch (failure: Exception) {
                close()
                if (failure is InterruptedException) Thread.currentThread().interrupt()
                throw failure
            } catch (failure: OutOfMemoryError) {
                close()
                throw failure
            } finally {
                lifetime.endCall()
                finishIfPossible()
            }
        }
    }

    private fun transact(
        code: Int,
        resultCount: Int,
        write: (Parcel) -> Unit,
    ): LongArray {
        val remote =
            synchronized(stateLock) {
                if (lifetime.closed || !deathRegistered || !admissions.isCurrent(lane, ownerEpoch, this)) {
                    throw IOException("Media endpoint retired")
                }
                checkNotNull(endpoint)
            }
        val request = ++sequence
        lifetime.beginRpc()
        val future: Future<LongArray> =
            try {
                rpc.submit<LongArray> {
                    val data = Parcel.obtain()
                    val reply = Parcel.obtain()
                    try {
                        if (lifetime.closed || !admissions.isCurrent(lane, ownerEpoch, this)) {
                            throw IOException("Media account generation changed")
                        }
                        data.writeInterfaceToken(AvStreamProtocol.DESCRIPTOR)
                        AvStreamProtocol.writeTuple(data, token, request)
                        write(data)
                        if (!remote.transact(code, data, reply, 0)) throw IOException("Media transaction refused")
                        require(reply.dataSize() <= AvStreamProtocol.MAX_REPLY_BYTES && !reply.hasFileDescriptors())
                        if (reply.readInt() != 0) throw IOException("Media decoding refused")
                        val tuple = AvStreamProtocol.readTuple(reply)
                        require(tuple.first == token && tuple.second == request)
                        synchronized(stateLock) {
                            require(
                                endpoint === remote &&
                                    readyIdentity != null &&
                                    !lifetime.closed &&
                                    admissions.isCurrent(lane, ownerEpoch, this),
                            )
                        }
                        AvStreamProtocol.readValues(reply, resultCount)
                    } finally {
                        reply.recycle()
                        data.recycle()
                        lifetime.endRpc()
                        finishIfPossible()
                    }
                }
            } catch (failure: RuntimeException) {
                lifetime.endRpc()
                throw failure
            }
        return try {
            future.get(AvStreamProtocol.CALL_DEADLINE_MS, TimeUnit.MILLISECONDS)
        } catch (failure: TimeoutException) {
            close()
            throw IOException("Media operation timed out", failure)
        } catch (failure: java.util.concurrent.ExecutionException) {
            throw IOException("Media worker operation failed", failure.cause)
        }
    }

    /** Close is nonblocking; the next source waits for calibrated kernel process absence and cleanup. */
    override fun close() {
        lifetime.close()
        closeSources()
        retire()
        finishIfPossible()
    }

    fun awaitRetired(timeoutMs: Long = AvStreamProtocol.CALL_DEADLINE_MS): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper())
        require(timeoutMs in 0..AvStreamProtocol.CALL_DEADLINE_MS)
        return released.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun closeSources() {
        runCatching { sources.close() }.onFailure { parentResources.uncertain() }
    }

    private fun retire() {
        val remote =
            synchronized(stateLock) {
                if (cleaned.get() || endpoint == null || !retireSent.compareAndSet(false, true)) return
                lifetime.beginRetirement()
                checkNotNull(endpoint)
            }
        try {
            retirement.execute {
                val data = Parcel.obtain()
                try {
                    data.writeInterfaceToken(AvStreamProtocol.DESCRIPTOR)
                    remote.transact(AvStreamProtocol.RETIRE, data, null, IBinder.FLAG_ONEWAY)
                } catch (_: android.os.RemoteException) {
                    // Endpoint loss is not a process-absence acknowledgement.
                } finally {
                    data.recycle()
                    lifetime.endRetirement()
                    finishIfPossible()
                }
            }
        } catch (_: RuntimeException) {
            lifetime.endRetirement()
        }
    }

    @Suppress("TooGenericExceptionCaught") // Each selected capability is closed once before admission release.
    private fun finishIfPossible() {
        if (Looper.myLooper() != callbacks.looper) {
            if (!cleaned.get() && cleanupScheduled.compareAndSet(false, true)) {
                callbackHandler.post {
                    cleanupScheduled.set(false)
                    finishIfPossible()
                }
            }
            return
        }
        if (lifetime.beginUnbind()) {
            val success =
                try {
                    context.unbindService(connection)
                    true
                } catch (_: RuntimeException) {
                    false
                }
            lifetime.unbindFinished(success)
        }
        if (lifetime.closed && processWitness.confirmedGone()) lifetime.processGoneObserved()
        if (!lifetime.releasable()) {
            observeLater()
            return
        }
        if (descriptorCleanupStarted.compareAndSet(false, true)) {
            for (resource in listOf<AutoCloseable?>(input, video, audio)) {
                try {
                    resource?.close()
                } catch (_: Exception) {
                    parentResources.uncertain()
                }
            }
        }
        val release =
            synchronized(stateLock) {
                lifetime.releasable() && parentResources.available() && cleaned.compareAndSet(false, true)
            }
        if (!release) {
            observeLater()
            return
        }
        endpoint?.let { runCatching { it.unlinkToDeath(death, 0) } }
        rpc.shutdown()
        retirement.shutdown()
        callbacks.quitSafely()
        admissions.release(lane, this)
        released.countDown()
    }

    private fun observeLater() {
        if (lifetime.closed && !cleaned.get() && observationScheduled.compareAndSet(false, true)) {
            val delay = AutomaticAvIdentity.observationDelay(observationPolls++)
            callbackHandler.postDelayed({
                observationScheduled.set(false)
                finishIfPossible()
            }, delay)
        }
    }

    companion object {
        private val ids = AtomicInteger()
        private val admissions = AvStreamAdmissions<IsolatedAvStream>()

        /** Account teardown revokes the exact current source; it never frees an unconfirmed worker slot. */
        fun retireActive() {
            admissions.invalidateAll().forEach { it.close() }
        }

        /** Production file entry: lane ownership begins before the original descriptor is opened. */
        @Suppress("LongParameterList") // Fixed source and bounded stream settings; no opener crosses IPC.
        fun openFile(
            context: Context,
            file: java.io.File,
            wantVideo: Boolean = true,
            wantAudio: Boolean = true,
            edge: Int = AvStreamProtocol.MAX_EDGE,
            lane: AvStreamLane = AvStreamLane.Playback,
        ): IsolatedAvStream =
            openFixed(context, wantVideo, wantAudio, edge, lane) { stream ->
                val initialized =
                    stream.sources.withMediaFileInput(file) { input ->
                        stream.initialize(input)
                        true
                    }
                if (initialized != true) throw IOException("Selected media file unavailable")
            }

        /** Production URI entry: provider staging and its original/selected descriptors belong to this lane. */
        @Suppress("LongParameterList")
        fun openUri(
            context: Context,
            uri: android.net.Uri,
            wantVideo: Boolean = true,
            wantAudio: Boolean = true,
            edge: Int = AvStreamProtocol.MAX_EDGE,
            lane: AvStreamLane = AvStreamLane.Playback,
        ): IsolatedAvStream {
            require(uri.scheme == "file" || uri.scheme == "content")
            return openFixed(context, wantVideo, wantAudio, edge, lane) { stream ->
                val initialized =
                    stream.sources.withMediaUriInput(context.contentResolver, uri) { input ->
                        stream.initialize(input)
                        true
                    }
                if (initialized != true) throw IOException("Selected media URI unavailable")
            }
        }

        /** Borrowed-descriptor seam for explicit tests; production callers use openFile/openUri. */
        @Suppress("LongParameterList")
        fun open(
            context: Context,
            selected: ParcelFileDescriptor,
            wantVideo: Boolean = true,
            wantAudio: Boolean = true,
            edge: Int = AvStreamProtocol.MAX_EDGE,
            lane: AvStreamLane = AvStreamLane.Playback,
        ): IsolatedAvStream = openFixed(context, wantVideo, wantAudio, edge, lane) { it.initialize(selected) }

        // One reserved source owns every startup failure.
        @Suppress("TooGenericExceptionCaught", "LongParameterList")
        private fun openFixed(
            context: Context,
            wantVideo: Boolean,
            wantAudio: Boolean,
            edge: Int,
            lane: AvStreamLane,
            initialize: (IsolatedAvStream) -> Unit,
        ): IsolatedAvStream {
            check(Looper.myLooper() != Looper.getMainLooper())
            require(wantVideo || wantAudio)
            AvStreamProtocol.pixelCapacity(edge)
            val created = reserve(context, wantVideo, wantAudio, edge, lane)
            try {
                initialize(created)
                created.closeSources()
                if (!admissions.isCurrent(lane, created.ownerEpoch, created) ||
                    created.lifetime.closed ||
                    !created.parentResources.healthy()
                ) {
                    throw IOException("Media account or source ownership retired")
                }
                return created
            } catch (failure: Exception) {
                if (!created.bindingAttempted) created.lifetime.neverBound()
                created.close()
                throw failure
            } catch (failure: OutOfMemoryError) {
                if (!created.bindingAttempted) created.lifetime.neverBound()
                created.close()
                throw failure
            }
        }

        private fun reserve(
            context: Context,
            wantVideo: Boolean,
            wantAudio: Boolean,
            edge: Int,
            lane: AvStreamLane,
        ): IsolatedAvStream {
            val expectedEpoch = admissions.epoch()
            val deadline = SystemClock.elapsedRealtime() + AvStreamProtocol.CALL_DEADLINE_MS
            while (true) {
                val current = admissions.current(lane, expectedEpoch)
                if (current == null) {
                    val created =
                        admissions.reserve(lane, expectedEpoch) {
                            IsolatedAvStream(
                                context.applicationContext,
                                wantVideo,
                                wantAudio,
                                edge,
                                expectedEpoch,
                                lane,
                            )
                        }
                    if (created != null) return created
                } else {
                    val left = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)
                    if (!current.released.await(left, TimeUnit.MILLISECONDS)) {
                        throw MediaAdmissionFailure("Previous media worker lifetime is unresolved")
                    }
                }
                if (SystemClock.elapsedRealtime() >= deadline) throw MediaAdmissionFailure("Media admission timed out")
            }
        }
    }
}
