@file:Suppress("MagicNumber") // Versioned primitive layout and fixed raw-output limits.

package dev.ipf.whitenoise.android.media.sandbox

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SharedMemory
import android.system.Os
import android.system.OsConstants
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Compressed input is parsed here; the parent receives raw fixed-format pixels/PCM only. */
class MediaAvPlaybackService : Service() {
    private val nativeLock = Any()
    private val watchdog = Executors.newSingleThreadScheduledExecutor()
    private var admitted = false
    private var identityToken: String? = null
    private var identityCallback: IBinder? = null
    private var token: String? = null
    private var sequence = 0L
    private var handle = 0L
    private var video: SharedMemory? = null
    private var audio: SharedMemory? = null
    private var videoBuffer: ByteBuffer? = null
    private var audioBuffer: ByteBuffer? = null
    private var selectedInput: ParcelFileDescriptor? = null
    private var edge = 0

    private val endpoint =
        object : Binder() {
            // Primitive request validation, watchdog and native call form one atomic Binder transaction.

            /**
             * Validates primitive requests before JNI and limits codec work with an independent
             * process watchdog.
             */
            @Suppress("ReturnCount", "TooGenericExceptionCaught", "LongMethod", "CyclomaticComplexMethod")
            override fun onTransact(
                code: Int,
                data: Parcel,
                reply: Parcel?,
                flags: Int,
            ): Boolean {
                if (!Process.isIsolated()) return false
                if (code == AvStreamProtocol.RETIRE) {
                    data.enforceInterface(AvStreamProtocol.DESCRIPTOR)
                    if (data.dataAvail() == 0 && !data.hasFileDescriptors()) Process.killProcess(Process.myPid())
                    return true
                }
                if (code == AvStreamProtocol.IDENTIFY) return identify(data, reply, flags)
                if (code !in AvStreamProtocol.OPEN..AvStreamProtocol.SEEK || reply == null) return false
                if (flags and IBinder.FLAG_ONEWAY != 0 || data.dataSize() > 2048) return false
                val killer =
                    watchdog.schedule(
                        { Process.killProcess(Process.myPid()) },
                        AvStreamProtocol.WATCHDOG_MS,
                        TimeUnit.MILLISECONDS,
                    )
                var requestTuple: Pair<String, Long>? = null
                try {
                    data.enforceInterface(AvStreamProtocol.DESCRIPTOR)
                    val tuple = AvStreamProtocol.readTuple(data)
                    requestTuple = tuple
                    val values =
                        synchronized(nativeLock) {
                            if (code == AvStreamProtocol.OPEN) {
                                open(tuple.first, tuple.second, data)
                            } else {
                                require(
                                    !data.hasFileDescriptors() && token == tuple.first && tuple.second == sequence + 1,
                                )
                                sequence = tuple.second
                                when (code) {
                                    AvStreamProtocol.READ -> {
                                        val preferred = data.readInt()
                                        require(data.dataAvail() == 0 && handle > 0L)
                                        require(preferred in AvStreamProtocol.ANY_TRACK..AvStreamProtocol.AUDIO_TRACK)
                                        require(preferred != AvStreamProtocol.VIDEO_TRACK || videoBuffer != null)
                                        require(preferred != AvStreamProtocol.AUDIO_TRACK || audioBuffer != null)
                                        val engine = NativeMediaEngine
                                        val result =
                                            if (preferred == AvStreamProtocol.ANY_TRACK) {
                                                engine.nativeRead(handle, videoBuffer, audioBuffer)
                                            } else {
                                                engine.nativeReadPreferred(handle, videoBuffer, audioBuffer, preferred)
                                            }
                                        result.also {
                                            AvStreamOutput.from(it, edge, videoBuffer != null, audioBuffer != null)
                                        }
                                    }
                                    else -> {
                                        val positionUs = data.readLong()
                                        require(
                                            data.dataAvail() == 0 && positionUs in 0..AvStreamProtocol.MAX_DURATION_US,
                                        )
                                        NativeMediaEngine.nativeSeek(handle, positionUs)
                                        longArrayOf()
                                    }
                                }
                            }
                        }
                    reply.writeInt(0)
                    AvStreamProtocol.writeTuple(reply, tuple.first, tuple.second)
                    AvStreamProtocol.writeValues(reply, values)
                } catch (_: RawContainerFailure) {
                    reply.setDataPosition(0)
                    reply.setDataSize(0)
                    reply.writeInt(AvStreamProtocol.CONTAINER_MALFORMED)
                    val tuple = checkNotNull(requestTuple)
                    AvStreamProtocol.writeTuple(reply, tuple.first, tuple.second)
                } catch (_: Exception) {
                    reply.setDataPosition(0)
                    reply.setDataSize(0)
                    reply.writeInt(1) // A fixed refusal has no native error text or parser-controlled objects.
                } catch (_: OutOfMemoryError) {
                    Process.killProcess(Process.myPid())
                } finally {
                    killer.cancel(false)
                }
                return true
            }
        }

    /** Returns the isolated Binder endpoint without receiving any media or presentation capability. */
    override fun onBind(intent: Intent): IBinder = endpoint

    /** Input-free exchange binds this one worker to its parent's exact generation before OPEN. */
    private fun identify(
        data: Parcel,
        reply: Parcel?,
        flags: Int,
    ): Boolean {
        if (reply == null || flags and IBinder.FLAG_ONEWAY != 0) return false
        return try {
            require(data.dataSize() <= AvStreamProtocol.MAX_REPLY_BYTES && !data.hasFileDescriptors())
            data.enforceInterface(AvStreamProtocol.DESCRIPTOR)
            val tuple = AvStreamProtocol.readTuple(data)
            val callback = checkNotNull(data.readStrongBinder())
            require(tuple.second == 1L && data.dataAvail() == 0)
            synchronized(nativeLock) {
                check(!admitted && identityToken == null)
                identityToken = tuple.first
                identityCallback = callback
            }
            sendIdentity(callback, tuple.first)
            reply.writeInt(0)
            AvStreamProtocol.writeTuple(reply, tuple.first, tuple.second)
            AvStreamProtocol.writeValues(reply, longArrayOf())
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Only fixed isolation primitives cross this synchronous, descriptor-free callback. */
    private fun sendIdentity(
        callback: IBinder,
        nextToken: String,
    ) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(AvStreamProtocol.READY_DESCRIPTOR)
            data.writeString(nextToken)
            data.writeInt(if (Process.isIsolated()) 1 else 0)
            val network = checkSelfPermission(android.Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED
            data.writeInt(if (network) 1 else 0)
            val runtime =
                (application as dev.ipf.whitenoise.android.WhiteNoiseApplication).initializedAppState() != null
            data.writeInt(if (runtime) 1 else 0)
            check(callback.transact(AvStreamProtocol.IDENTITY, data, reply, 0))
            check(reply.dataAvail() == Int.SIZE_BYTES && !reply.hasFileDescriptors() && reply.readInt() == 1)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /** Owns the one selected FD and output mappings; refuses any generation lacking identity. */
    private fun open(
        nextToken: String,
        nextSequence: Long,
        data: Parcel,
    ): LongArray {
        check(!admitted && nextSequence == 1L && identityToken == nextToken)
        admitted = true // One OPEN, even if a parser or protocol later refuses.
        token = nextToken
        sequence = nextSequence
        val nextEdge = data.readInt()
        val wantVideo = AvStreamProtocol.boolean(data)
        val wantAudio = AvStreamProtocol.boolean(data)
        require(wantVideo || wantAudio)
        val capacity = AvStreamProtocol.pixelCapacity(nextEdge)
        val input = ParcelFileDescriptor.CREATOR.createFromParcel(data).also { selectedInput = it }
        require(input.statSize in 1..AvStreamProtocol.MAX_SOURCE_BYTES)
        require(
            Os.fcntlInt(input.fileDescriptor, OsConstants.F_GETFL, 0) and OsConstants.O_ACCMODE == OsConstants.O_RDONLY,
        )
        if (wantVideo) {
            video = SharedMemory.CREATOR.createFromParcel(data).also { require(it.size == capacity) }
            videoBuffer = checkNotNull(video).mapReadWrite()
        }
        if (wantAudio) {
            audio = SharedMemory.CREATOR.createFromParcel(data).also { require(it.size == AvStreamProtocol.PCM_BYTES) }
            audioBuffer = checkNotNull(audio).mapReadWrite()
        }
        val ready = checkNotNull(data.readStrongBinder())
        require(data.dataAvail() == 0 && ready == identityCallback)
        sendReady(ready, nextToken)
        edge = nextEdge
        NativeMediaEngine.ensureLoaded()
        handle = NativeMediaEngine.nativeOpen(input.fd, 0, input.statSize, edge)
        if (handle == -1L) throw RawContainerFailure()
        check(handle > 0L)
        return NativeMediaEngine.nativeInfo(handle).also { info ->
            val admittedInfo = avStreamInfo(info)
            require((wantVideo && admittedInfo.hasVideo) || (wantAudio && admittedInfo.hasAudio))
        }
    }

    /** Synchronously confirms source write denial and isolation to the same pre-admitted parent generation. */
    private fun sendReady(
        callback: IBinder,
        nextToken: String,
    ) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(AvStreamProtocol.READY_DESCRIPTOR)
            data.writeString(nextToken)
            data.writeInt(if (Process.isIsolated()) 1 else 0)
            val network = checkSelfPermission(android.Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED
            data.writeInt(if (network) 1 else 0)
            val runtime =
                (application as dev.ipf.whitenoise.android.WhiteNoiseApplication).initializedAppState() != null
            data.writeInt(if (runtime) 1 else 0)
            data.writeInt(privateCanaryErrno())
            data.writeInt(inputWriteErrno())
            check(callback.transact(AvStreamProtocol.READY, data, reply, 0))
            check(reply.dataAvail() == Int.SIZE_BYTES && reply.readInt() == 1)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /** Checks only the fixed debug private canary path and returns its denial errno. */
    private fun privateCanaryErrno(): Int {
        if (!dev.ipf.whitenoise.android.BuildConfig.DEBUG) return -1
        val path = applicationInfo.dataDir + "/files/media-containment-canary"
        return try {
            Os.close(Os.open(path, OsConstants.O_RDONLY, 0))
            0
        } catch (failure: android.system.ErrnoException) {
            failure.errno
        }
    }

    /** Verifies debug selected-FD write denial without acquiring any additional file-opening authority. */
    private fun inputWriteErrno(): Int {
        if (!dev.ipf.whitenoise.android.BuildConfig.DEBUG) return -1
        return try {
            Os.write(checkNotNull(selectedInput).fileDescriptor, byteArrayOf(0), 0, 1)
            0
        } catch (failure: android.system.ErrnoException) {
            failure.errno
        }
    }

    /** Terminates the disposable process; blocked native cleanup cannot delay retirement. */
    override fun onDestroy() {
        // No potentially blocked native cleanup can delay retirement or its independent control path.
        if (Process.isIsolated()) Process.killProcess(Process.myPid())
        watchdog.shutdownNow()
        super.onDestroy()
    }
}
