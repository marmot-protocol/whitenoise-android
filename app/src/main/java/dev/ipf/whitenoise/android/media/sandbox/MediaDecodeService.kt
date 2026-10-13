package dev.ipf.whitenoise.android.media.sandbox

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SharedMemory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Permissionless codec worker. The control looper and watchdog never run native decoder work. */
class MediaDecodeService : Service() {
    private val codec = Executors.newSingleThreadExecutor()
    private val watchdog = Executors.newSingleThreadScheduledExecutor()
    private val messenger = Messenger(Handler(Looper.getMainLooper(), ::accept))

    override fun onBind(intent: Intent) = messenger.binder

    @Suppress("ReturnCount") // Invalid control messages never enter the codec queue.
    private fun accept(message: Message): Boolean {
        if (!Process.isIsolated()) return true
        if (message.what == MediaDecodePolicy.RETIRE) {
            Process.killProcess(Process.myPid())
            return true
        }
        if (message.what != MediaDecodePolicy.DECODE) return true
        val reply = message.replyTo ?: return true
        val data = message.data
        val requestId = message.arg1
        codec.execute {
            val killer =
                watchdog.schedule(
                    { Process.killProcess(Process.myPid()) },
                    MediaDecodePolicy.WATCHDOG_MS,
                    TimeUnit.MILLISECONDS,
                )
            try {
                val result =
                    try {
                        execute(data)
                    } catch (_: Exception) {
                        Bundle()
                    } catch (_: OutOfMemoryError) {
                        Bundle()
                    }
                sendMediaDecodeReply(reply.binder, requestId, result)
            } catch (_: Exception) {
                // The host has gone away; never retry or decode in its privileged process.
            } finally {
                killer.cancel(false)
            }
        }
        return true
    }

    @Suppress("DEPRECATION", "ReturnCount") // Legacy framework descriptors and fail-closed operation guards.
    private fun execute(data: Bundle): Bundle {
        val response = isolationResponse(data)
        val input = data.getParcelable<ParcelFileDescriptor>("input") ?: return response
        val output = data.getParcelable<SharedMemory>("output") ?: return response.also { input.close() }
        input.use {
            output.use {
                if (data.getInt("version") != MediaDecodePolicy.VERSION) return response
                return imageOperation(input, output, data, response)
            }
        }
    }

    private fun isolationResponse(data: Bundle): Bundle =
        Bundle().apply {
            putInt("uid", Process.myUid())
            putBoolean("isolated", Process.isIsolated())
            val canaryErrno = privateCanaryErrno(data)
            putInt("privateCanaryErrno", canaryErrno)
            putBoolean(
                "privateCanaryDenied",
                canaryErrno == android.system.OsConstants.EACCES ||
                    canaryErrno == android.system.OsConstants.EPERM ||
                    canaryErrno == android.system.OsConstants.ENOENT,
            )
            val app = application as dev.ipf.whitenoise.android.WhiteNoiseApplication
            putBoolean("runtimeInitialized", app.initializedAppState() != null)
            val permission = checkSelfPermission(android.Manifest.permission.INTERNET)
            putBoolean("internetGranted", permission == android.content.pm.PackageManager.PERMISSION_GRANTED)
        }

    @Suppress("ReturnCount") // Each metadata-only operation returns before pixel allocation.
    private fun imageOperation(
        input: ParcelFileDescriptor,
        output: SharedMemory,
        data: Bundle,
        response: Bundle,
    ): Bundle {
        val operation = data.getInt("operation")
        if (dev.ipf.whitenoise.android.BuildConfig.DEBUG && operation == MediaDecodePolicy.DEBUG_HANG) {
            Thread.sleep(MediaDecodePolicy.DEBUG_HANG_MS)
            return response
        }
        val bytes = readInput(input) ?: return response
        if (operation == MediaDecodePolicy.EXIF) {
            response.putInt(
                "orientation",
                dev.ipf.whitenoise.android.media.MediaPipeline
                    .readExifOrientationInDecoder(bytes),
            )
            response.putBoolean("success", true)
            return response
        }
        if (operation == MediaDecodePolicy.INSPECT) {
            PlatformMediaDecoder.inspect(bytes)?.let { info ->
                response.putInt("width", info.width)
                response.putInt("height", info.height)
                response.putString("mime", info.mime)
                response.putInt("orientation", info.orientation)
                response.putBoolean("success", true)
            }
            return response
        }
        val bitmap =
            when (operation) {
                MediaDecodePolicy.DECODE ->
                    PlatformMediaDecoder.decode(
                        bytes,
                        data.getInt("edge"),
                        data.getBoolean("oriented"),
                    )
                MediaDecodePolicy.SVG -> PlatformMediaDecoder.svg(bytes)
                else -> null
            } ?: return response
        bitmap.usePixels(output, data.getInt("edge"), response)
        return response
    }

    @Suppress("ReturnCount") // Stat length, truncation and trailing data are separate descriptor guards.
    private fun readInput(input: ParcelFileDescriptor): ByteArray? {
        val size = input.statSize
        if (size !in 1..MediaDecodePolicy.MAX_INPUT_BYTES.toLong()) return null
        return ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(input.fileDescriptor)).use { stream ->
            val bytes = ByteArray(size.toInt())
            var offset = 0
            while (offset < bytes.size) {
                val count = stream.read(bytes, offset, bytes.size - offset)
                if (count <= 0) return null
                offset += count
            }
            bytes.takeIf { stream.read() == -1 }
        }
    }

    private fun Bitmap.usePixels(
        output: SharedMemory,
        edge: Int,
        response: Bundle,
    ) {
        try {
            val alphaSupported = !hasAlpha() || isPremultiplied
            if (config != Bitmap.Config.ARGB_8888 || rowBytes != width * Int.SIZE_BYTES || !alphaSupported) return
            val count = MediaDecodePolicy.pixelBytes(width, height, edge, output.size) ?: return
            val buffer = output.mapReadWrite()
            try {
                buffer.limit(count)
                copyPixelsToBuffer(buffer)
                response.putInt("width", width)
                response.putInt("height", height)
                response.putBoolean("success", true)
            } finally {
                SharedMemory.unmap(buffer)
            }
        } finally {
            recycle()
        }
    }

    private fun privateCanaryErrno(data: Bundle): Int {
        val path = data.getString("canaryPath")?.takeIf { it.endsWith("/files/media-containment-canary") }
        if (!dev.ipf.whitenoise.android.BuildConfig.DEBUG || path == null) return -1
        return try {
            val descriptor = android.system.Os.open(path, android.system.OsConstants.O_RDONLY, 0)
            android.system.Os.close(descriptor)
            0
        } catch (error: android.system.ErrnoException) {
            error.errno
        }
    }

    override fun onDestroy() {
        codec.shutdownNow()
        watchdog.shutdownNow()
        super.onDestroy()
        if (Process.isIsolated()) Process.killProcess(Process.myPid())
    }
}
