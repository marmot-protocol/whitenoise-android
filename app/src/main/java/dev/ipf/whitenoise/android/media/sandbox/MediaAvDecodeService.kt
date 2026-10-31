package dev.ipf.whitenoise.android.media.sandbox

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SharedMemory
import android.system.Os
import android.system.OsConstants
import dev.ipf.whitenoise.android.audio.WaveformAccumulator
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One selected input per isolated process; codec work cannot occupy control or watchdog threads. */
class MediaAvDecodeService : Service() {
    private val codec = Executors.newSingleThreadExecutor()
    private val watchdog = Executors.newSingleThreadScheduledExecutor()
    private val messenger = Messenger(Handler(Looper.getMainLooper(), ::accept))
    private var admitted = false

    override fun onBind(intent: Intent) = messenger.binder

    @Suppress("ReturnCount") // Refuse unrelated or repeated capabilities before dispatch.
    private fun accept(message: Message): Boolean {
        if (!Process.isIsolated()) return true
        if (message.what == MediaDecodePolicy.RETIRE) {
            Process.killProcess(Process.myPid())
            return true
        }
        if (message.what != MediaDecodePolicy.DECODE || admitted) return true
        val reply = message.replyTo?.binder ?: return true
        admitted = true
        val data = Bundle(message.data)
        val requestId = message.arg1
        val limit =
            if (data.getInt("operation") == MediaDecodePolicy.WAVEFORM) {
                WAVEFORM_WORK_MS
            } else {
                MediaDecodePolicy.WATCHDOG_MS
            }
        // Never cancel the hard kill after a reply: a missing host retirement still cannot retain this process.
        watchdog.schedule({ Process.killProcess(Process.myPid()) }, limit, TimeUnit.MILLISECONDS)
        codec.execute {
            val response = isolation(data)
            val result =
                try {
                    execute(data, response)
                } catch (_: java.io.IOException) {
                    response
                } catch (_: Exception) {
                    Bundle()
                } catch (_: LinkageError) {
                    Bundle()
                } catch (_: OutOfMemoryError) {
                    Bundle()
                }
            runCatching { sendMediaDecodeReply(reply, requestId, result) }
        }
        return true
    }

    @Suppress("DEPRECATION", "ReturnCount") // Refuse each invalid capability before native entry.
    private fun execute(data: Bundle, result: Bundle): Bundle {
        val input = data.getParcelable<ParcelFileDescriptor>("input") ?: return result
        input.use {
            val output = data.getParcelable<SharedMemory>("output") ?: return result
            output.use {
                if (data.getInt("version") != MediaDecodePolicy.VERSION) return result
                val operation = data.getInt("operation")
                if (operation != MediaDecodePolicy.PROBE && operation != MediaDecodePolicy.WAVEFORM) return result
                val edge = data.getInt("edge")
                if (MediaDecodePolicy.outputCapacity(edge) == null) return result
                val length = data.getLong("length")
                val offset = data.getLong("offset")
                if (input.statSize !in 1..MAX_MEDIA_SOURCE_BYTES || !validMediaRange(input.statSize, offset, length)) {
                    return result
                }
                val mode = Os.fcntlInt(input.fileDescriptor, OsConstants.F_GETFL, 0) and OsConstants.O_ACCMODE
                if (mode != OsConstants.O_RDONLY) return result
                result.putInt("inputWriteErrno", readOnlyErrno(input))
                NativeMediaEngine.ensureLoaded()
                val handle = NativeMediaEngine.nativeOpen(input.fd, offset, length, edge)
                if (handle == 0L) return result
                try {
                    if (fillResult(handle, data, edge, output, result)) result.putBoolean("success", true)
                } finally {
                    NativeMediaEngine.nativeClose(handle)
                }
            }
        }
        return result
    }

    @Suppress("ReturnCount") // Distinct absent-audio, empty-waveform and poster refusal boundaries.
    private fun fillResult(
        handle: Long,
        data: Bundle,
        edge: Int,
        output: SharedMemory,
        result: Bundle,
    ): Boolean {
        val info = NativeMediaOutputPolicy.info(NativeMediaEngine.nativeInfo(handle))
        val swapped = info.rotationDegrees == 90 || info.rotationDegrees == 270
        result.putInt("sourceWidth", if (swapped) info.height else info.width)
        result.putInt("sourceHeight", if (swapped) info.width else info.height)
        result.putLong("duration", info.durationUs / 1000)
        if (data.getInt("operation") == MediaDecodePolicy.WAVEFORM) {
            if (info.channels == 0) return false
            val values = waveform(handle) ?: return false
            result.putFloatArray("waveform", values)
        } else if (data.getBoolean("oriented") && info.width > 0) {
            return poster(handle, info.rotationDegrees, edge, output, result)
        }
        return true
    }

    private fun waveform(handle: Long): FloatArray? {
        val samples = ByteBuffer.allocateDirect(NativeMediaOutputPolicy.PCM_CAPACITY)
        val signal = WaveformAccumulator()
        val deadline = android.os.SystemClock.elapsedRealtime() + WAVEFORM_WORK_MS
        repeat(MAX_READS) {
            check(android.os.SystemClock.elapsedRealtime() < deadline)
            samples.clear()
            val frame = NativeMediaOutputPolicy.frame(NativeMediaEngine.nativeRead(handle, null, samples), 1)
            when (frame.kind) {
                0 -> return signal.finish()
                2 -> {
                    samples.position(0)
                    samples.limit(frame.bytes)
                    signal.addPcm16(samples, frame.bytes, frame.channels)
                }
                else -> error("Unrequested video output")
            }
        }
        return null
    }

    private fun poster(
        handle: Long,
        rotation: Int,
        edge: Int,
        output: SharedMemory,
        result: Bundle,
    ): Boolean {
        val capacity = checkNotNull(MediaDecodePolicy.outputCapacity(edge))
        if (output.size < capacity) return false
        val pixels = output.mapReadWrite()
        try {
            val source = if (rotation == 0) pixels else ByteBuffer.allocateDirect(capacity)
            repeat(MAX_READS) {
                source.clear()
                val frame = NativeMediaOutputPolicy.frame(NativeMediaEngine.nativeRead(handle, source, null), edge)
                if (frame.kind == 0) return false
                check(frame.kind == 1) { "Unrequested PCM output" }
                val swapped = rotation == 90 || rotation == 270
                val width = if (swapped) frame.height else frame.width
                val height = if (swapped) frame.width else frame.height
                if (rotation != 0) NativeMediaPixels.rotate(source, pixels, frame.width, frame.height, rotation)
                result.putInt("width", width)
                result.putInt("height", height)
                result.putBoolean("pixels", true)
                return true
            }
        } finally {
            SharedMemory.unmap(pixels)
        }
        return false
    }

    private fun isolation(data: Bundle): Bundle =
        Bundle().apply {
            putBoolean("isolated", Process.isIsolated())
            val internet = checkSelfPermission(android.Manifest.permission.INTERNET)
            putBoolean("internetGranted", internet == android.content.pm.PackageManager.PERMISSION_GRANTED)
            val app = application as dev.ipf.whitenoise.android.WhiteNoiseApplication
            putBoolean("runtimeInitialized", app.initializedAppState() != null)
            val errno = canaryErrno(data)
            putInt("privateCanaryErrno", errno)
            putBoolean("privateCanaryDenied", errno in setOf(OsConstants.EACCES, OsConstants.EPERM, OsConstants.ENOENT))
        }

    private fun readOnlyErrno(input: ParcelFileDescriptor): Int {
        if (!dev.ipf.whitenoise.android.BuildConfig.DEBUG) return -1
        return try {
            Os.write(input.fileDescriptor, byteArrayOf(0), 0, 1)
            0
        } catch (failure: android.system.ErrnoException) {
            failure.errno
        }
    }

    private fun canaryErrno(data: Bundle): Int {
        val path = data.getString("canaryPath")?.takeIf { it.endsWith("/files/media-containment-canary") }
        if (!dev.ipf.whitenoise.android.BuildConfig.DEBUG || path == null) return -1
        return try {
            Os.close(Os.open(path, OsConstants.O_RDONLY, 0))
            0
        } catch (failure: android.system.ErrnoException) {
            failure.errno
        }
    }

    override fun onDestroy() {
        codec.shutdownNow()
        watchdog.shutdownNow()
        super.onDestroy()
        if (Process.isIsolated()) Process.killProcess(Process.myPid())
    }

    private companion object {
        const val WAVEFORM_WORK_MS = 30000L
        const val MAX_READS = 500000
    }
}
