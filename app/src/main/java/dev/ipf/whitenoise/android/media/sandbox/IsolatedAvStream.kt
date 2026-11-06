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
import java.util.concurrent.atomic.AtomicIntegerArray

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
    private val idle = AtomicBoolean()
    private val idleExpiry = Runnable { preemptIdle() }

    @Volatile private var deathRegistered = false
    private var readyIdentity: Pair<Int, Int>? = null

    @Volatile private var identity: Pair<Int, Int>? = null

    @Volatile private var endpoint: IBinder? = null
    private var sessionInfo: AvStreamInfo? = null

    @Volatile var isolation: WorkerIsolation? = null
        private set

    /** Exports only authenticated READY scalars for an explicitly enabled diagnostic observer. */
    fun observeReady(
        observation: RawAvPlaybackObservation,
        activation: Long,
    ) {
        synchronized(stateLock) {
            readyIdentity?.let {
                observation.worker(activation, it.first, it.second, SystemClock.elapsedRealtimeNanos())
            }
        }
    }

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
            /** Retains the actual endpoint and death registration even when startup was already revoked. */
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

            /** Revokes availability without converting a framework disconnect into kernel absence evidence. */
            override fun onServiceDisconnected(name: ComponentName) = unavailable()

            /** Retains the process credit after framework binding loss until exact cleanup can be proved. */
            override fun onBindingDied(name: ComponentName) = unavailable()

            /** Revokes this generation without inventing a worker PID or releasing an accepted binding credit. */
            override fun onNullBinding(name: ComponentName) = unavailable()
        }

    private val readyCallback =
        object : Binder() {
            // Primitive READY validation and exact endpoint admission stay in one atomic Binder transaction.

            /**
             * Admits only fixed synchronous identity/READY primitives from the exact retained worker
             * generation.
             */
            @Suppress("ReturnCount", "TooGenericExceptionCaught", "LongMethod", "CyclomaticComplexMethod")
            override fun onTransact(
                code: Int,
                data: Parcel,
                reply: Parcel?,
                flags: Int,
            ): Boolean {
                if (code == AvStreamProtocol.IDENTITY) return acceptIdentity(data, reply, flags)
                if (code != AvStreamProtocol.READY || reply == null || flags and IBinder.FLAG_ONEWAY != 0) return false
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
                                    identity == (uid to pid) &&
                                    processWitness.acceptsCaller(uid, pid)
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

    /**
     * A late exact generation identity may establish cleanup evidence after revocation. It never
     * authorizes OPEN: that requires the current account epoch and an atomic exposure commit.
     */
    @Suppress("ReturnCount") // Reject invalid callbacks before acknowledging the exact retained generation.
    private fun acceptIdentity(
        data: Parcel,
        reply: Parcel?,
        flags: Int,
    ): Boolean {
        if (reply == null || flags and IBinder.FLAG_ONEWAY != 0) return false
        val accepted =
            runCatching {
                require(data.dataSize() <= AvStreamProtocol.MAX_REPLY_BYTES && !data.hasFileDescriptors())
                data.enforceInterface(AvStreamProtocol.READY_DESCRIPTOR)
                val received = data.readString()
                val isolated = AvStreamProtocol.boolean(data)
                val network = AvStreamProtocol.boolean(data)
                val initialized = AvStreamProtocol.boolean(data)
                require(data.dataAvail() == 0)
                val uid = Binder.getCallingUid()
                val pid = Binder.getCallingPid()
                synchronized(stateLock) {
                    val retained = !cleaned.get() && endpoint != null && identity == null
                    val valid = received == token && isolated && !network && !initialized
                    if (retained && valid && processWitness.admit(uid, pid)) {
                        identity = uid to pid
                        true
                    } else {
                        false
                    }
                }
            }.getOrDefault(false)
        if (!accepted) return false
        reply.writeInt(1)
        if (lifetime.closed) {
            retire()
            finishIfPossible()
        }
        return true
    }

    /** Revokes operations and starts independent retirement/cleanup without waiting for codec RPC. */
    private fun unavailable() {
        // A terminal binding callback permits unbind, not process-credit release without kernel evidence.
        lifetime.close()
        lifetime.bindingUnavailable()
        connected.countDown()
        retire()
        finishIfPossible()
    }

    /** Owns bounded parent resources and completes input-free identity before committing any OPEN capability. */
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
            // Identity carries no source/shared-memory capability and consumes no media sequence.
            transact(AvStreamProtocol.IDENTIFY, 0) { data -> data.writeStrongBinder(readyCallback) }
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

    /** Records accepted, refused or uncertain creation and retains every unresolved framework lifetime. */
    @Suppress("TooGenericExceptionCaught") // Any binding exception leaves process creation uncertain.
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

    /** Validates raw output metadata before copying owned pixel/PCM bytes from shared memory. */
    @Suppress("CyclomaticComplexMethod") // Validate and own one exact typed shared-memory output in this call.
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

    /** Serializes a bounded media-time seek through the retained exact worker generation. */
    fun seek(positionUs: Long) =
        call {
            require(positionUs in 0..AvStreamProtocol.MAX_DURATION_US)
            transact(AvStreamProtocol.SEEK, 0) { data -> data.writeLong(positionUs) }
            Unit
        }

    /**
     * Serializes caller work and rejects stale-account publication while retaining all in-flight
     * cleanup credits.
     */
    @Suppress("TooGenericExceptionCaught", "ThrowsCount") // Every failed IPC/copy revokes this capability.
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

    /** One RPC owns tuple validation, deadline, parcel recycling and its lifetime credit on every failure. */
    @Suppress("CyclomaticComplexMethod", "ThrowsCount", "TooGenericExceptionCaught", "LongMethod")
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
        val request = if (code == AvStreamProtocol.IDENTIFY) 1L else ++sequence
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
                        if (code == AvStreamProtocol.OPEN) {
                            val committed =
                                admissions.accept(lane, ownerEpoch, this) {
                                    endpoint === remote && deathRegistered && identity != null && lifetime.commitOpen()
                                }
                            if (!committed) throw IOException("Media OPEN ownership retired")
                        }
                        if (!remote.transact(code, data, reply, 0)) throw IOException("Media transaction refused")
                        require(reply.dataSize() <= AvStreamProtocol.MAX_REPLY_BYTES && !reply.hasFileDescriptors())
                        val status = reply.readInt()
                        if (status != 0) {
                            if (code in setOf(AvStreamProtocol.OPEN, AvStreamProtocol.READ) &&
                                status == AvStreamProtocol.CONTAINER_MALFORMED
                            ) {
                                val refused = AvStreamProtocol.readTuple(reply)
                                require(refused.first == token && refused.second == request && reply.dataAvail() == 0)
                                synchronized(stateLock) {
                                    require(
                                        endpoint === remote &&
                                            readyIdentity != null &&
                                            !lifetime.closed &&
                                            lifetime.openMayHaveBeenDelivered,
                                    )
                                    require(admissions.isCurrent(lane, ownerEpoch, this))
                                }
                                throw RawContainerFailure()
                            }
                            require(reply.dataAvail() == 0)
                            throw IOException("Media decoding refused")
                        }
                        val tuple = AvStreamProtocol.readTuple(reply)
                        require(tuple.first == token && tuple.second == request)
                        synchronized(stateLock) {
                            require(
                                endpoint === remote &&
                                    (
                                        if (code == AvStreamProtocol.IDENTIFY) {
                                            identity != null
                                        } else {
                                            readyIdentity != null
                                        }
                                    ) &&
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
            throw IOException("Media worker operation failed", failure.cause ?: failure)
        }
    }

    /**
     * Keeps this exact worker for the same owner's next activation while it is paused.
     * An idle worker receives no new capability; expiry, a waiting opener or account retirement closes it.
     */
    fun park(idleMs: Long) {
        idle.set(true)
        callbackHandler.postDelayed(idleExpiry, idleMs)
        // Pairs with reserve(): either the waiter observes idle, or this park observes the waiter.
        if (reserveWaiters.get(lane.ordinal) > 0) preemptIdle()
    }

    /** Reclaims a parked worker; false means it was preempted or can no longer serve its owner. */
    fun unpark(): Boolean {
        if (!idle.compareAndSet(true, false)) return false
        callbackHandler.removeCallbacks(idleExpiry)
        return !lifetime.closed && admissions.isCurrent(lane, ownerEpoch, this) && parentResources.healthy()
    }

    /** Closes this worker only if it is parked; an active stream is never revoked here. */
    private fun preemptIdle() {
        if (idle.compareAndSet(true, false)) close()
    }

    /** Close is nonblocking; the next source waits for calibrated kernel process absence and cleanup. */
    override fun close() {
        lifetime.close()
        closeSources()
        retire()
        finishIfPossible()
    }

    /** Waits off the main thread for acknowledged cleanup; a timeout never releases ownership. */
    fun awaitRetired(timeoutMs: Long = AvStreamProtocol.CALL_DEADLINE_MS): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper())
        require(timeoutMs in 0..AvStreamProtocol.CALL_DEADLINE_MS)
        return released.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Revokes source acquisition and records uncertain parent cleanup instead of dropping its
     * resource credit.
     */
    private fun closeSources() {
        runCatching { sources.close() }.onFailure { parentResources.uncertain() }
    }

    /** Sends only the existing input-free identity request; its callback may establish retirement evidence. */
    private fun identifyForRetirement(remote: IBinder) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(AvStreamProtocol.DESCRIPTOR)
            AvStreamProtocol.writeTuple(data, token, 1L)
            data.writeStrongBinder(readyCallback)
            remote.transact(AvStreamProtocol.IDENTIFY, data, reply, 0)
            // No reply grants admission: only the authenticated callback may supply the PID witness.
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /** Uses an independent executor so blocked codec work cannot queue ahead of the termination request. */
    private fun retire() {
        val remote =
            synchronized(stateLock) {
                if (cleaned.get() || endpoint == null || !retireSent.compareAndSet(false, true)) return
                lifetime.beginRetirement()
                checkNotNull(endpoint)
            }
        try {
            retirement.execute {
                try {
                    if (identity == null && !lifetime.openMayHaveBeenDelivered) {
                        try {
                            identifyForRetirement(remote)
                        } catch (_: Exception) {
                            // Failed identification supplies no absence proof; still request retirement.
                        }
                    }
                    val data = Parcel.obtain()
                    try {
                        data.writeInterfaceToken(AvStreamProtocol.DESCRIPTOR)
                        remote.transact(AvStreamProtocol.RETIRE, data, null, IBinder.FLAG_ONEWAY)
                    } finally {
                        data.recycle()
                    }
                } catch (_: android.os.RemoteException) {
                    // Endpoint loss is not a process-absence acknowledgement.
                } finally {
                    lifetime.endRetirement()
                    finishIfPossible()
                }
            }
        } catch (_: RuntimeException) {
            lifetime.endRetirement()
        }
    }

    /**
     * Releases admission only after process absence, connection cleanup, settled work and parent
     * resource closure.
     */
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
        closeDescriptorsOnce()
        val release =
            synchronized(stateLock) {
                lifetime.releasable() && parentResources.available() && cleaned.compareAndSet(false, true)
            }
        if (!release) {
            observeLater()
        } else {
            endpoint?.let { runCatching { it.unlinkToDeath(death, 0) } }
            rpc.shutdown()
            retirement.shutdown()
            callbacks.quitSafely()
            admissions.release(lane, this)
            released.countDown()
        }
    }

    /** Attempts each owned descriptor/mapping closure once and retains uncertain cleanup evidence. */
    @Suppress("TooGenericExceptionCaught") // A failed close retains the resource budget for any capability type.
    private fun closeDescriptorsOnce() {
        if (descriptorCleanupStarted.compareAndSet(false, true)) {
            for (resource in listOf<AutoCloseable?>(input, video, audio)) {
                try {
                    resource?.close()
                } catch (_: Exception) {
                    parentResources.uncertain()
                }
            }
        }
    }

    /** Schedules bounded-backoff absence observation while retaining unresolved worker accounting. */
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
        private val reserveWaiters = AtomicIntegerArray(AvStreamLane.entries.size)

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

        /** Owns the reservation through source acquisition, initialization and acknowledged failure cleanup. */
        @Suppress("TooGenericExceptionCaught", "LongParameterList", "ThrowsCount")
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

        /** Waits for exact prior-generation cleanup without increasing the fixed two-lane process bound. */
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
                    reserveWaiters.incrementAndGet(lane.ordinal)
                    val released =
                        try {
                            // A paused owner's parked worker yields its lane to any new opener.
                            current.preemptIdle()
                            current.released.await(left, TimeUnit.MILLISECONDS)
                        } finally {
                            reserveWaiters.decrementAndGet(lane.ordinal)
                        }
                    if (!released) throw MediaAdmissionFailure("Previous media worker lifetime is unresolved")
                }
                if (SystemClock.elapsedRealtime() >= deadline) throw MediaAdmissionFailure("Media admission timed out")
            }
        }
    }
}
