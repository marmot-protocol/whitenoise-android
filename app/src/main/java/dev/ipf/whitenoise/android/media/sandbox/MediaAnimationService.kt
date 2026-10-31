package dev.ipf.whitenoise.android.media.sandbox

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SharedMemory
import java.util.concurrent.TimeUnit

/** One explicit viewer. Independent control and watchdog threads can retire a stuck native draw. */
class MediaAnimationService : Service() {
    private val codecThread = HandlerThread("isolated-animation-codec").apply { start() }
    private val codec = Handler(codecThread.looper)
    private val watchdog =
        java.util.concurrent
            .ScheduledThreadPoolExecutor(1)
            .apply { removeOnCancelPolicy = true }
    private val messenger = Messenger(Handler(Looper.getMainLooper(), ::accept))
    private var session = 0
    private var opened = false
    private var animation: WorkerAnimation? = null
    private var idle: java.util.concurrent.ScheduledFuture<*>? = null

    override fun onBind(intent: Intent) = messenger.binder

    private fun accept(message: Message): Boolean {
        if (!Process.isIsolated()) return true
        if (message.what == MediaDecodePolicy.RETIRE) {
            Process.killProcess(Process.myPid())
        } else {
            dispatch(message)
        }
        return true
    }

    private fun dispatch(message: Message) {
        val command = message.what
        val id = message.arg1
        val ack = message.data.getLong("sequence")
        val data = message.data
        val reply = message.replyTo
        codec.post {
            try {
                if (command == AnimationFrameProtocol.OPEN && !opened) {
                    opened = true
                    session = id
                    open(data, requireNotNull(reply))
                } else if (id == session) {
                    when (command) {
                        AnimationFrameProtocol.PLAY -> animation?.play()
                        AnimationFrameProtocol.PAUSE -> animation?.pause()
                        AnimationFrameProtocol.ACK -> {
                            idle?.cancel(false)
                            animation?.acknowledge(ack)
                        }
                    }
                }
            } catch (_: Exception) {
                Process.killProcess(Process.myPid())
            } catch (_: OutOfMemoryError) {
                Process.killProcess(Process.myPid())
            }
        }
    }

    @Suppress("DEPRECATION") // API 30 descriptor access, supplied only by the nonexported bound client.
    private fun open(data: android.os.Bundle, callback: Messenger) {
        callback.binder.linkToDeath({ Process.killProcess(Process.myPid()) }, 0)
        val input = requireNotNull(data.getParcelable<ParcelFileDescriptor>("input"))
        val output = requireNotNull(data.getParcelable<SharedMemory>("output"))
        nativeWork {
            val bytes = input.use { requireNotNull(readDecoderInput(it)) }
            val canary = canaryErrno(data)
            val viewerOutput = readViewerAnimationOutput(data)
            animation =
                try {
                    WorkerAnimation(bytes, data.getInt("edge"), output, codec, { sequence, width, height ->
                        writeAnimationFrame(
                            callback.binder,
                            AnimationFrameInfo(
                                session,
                                sequence,
                                width,
                                height,
                                Process.isIsolated(),
                                checkSelfPermission(android.Manifest.permission.INTERNET) ==
                                    android.content.pm.PackageManager.PERMISSION_GRANTED,
                                (application as dev.ipf.whitenoise.android.WhiteNoiseApplication)
                                    .initializedAppState() != null,
                                canary,
                            ),
                        )
                        armIdle()
                    }, ::nativeWork, viewerOutput)
                } catch (rejected: java.io.IOException) {
                    reportCodecRejection(callback)
                    throw rejected
                } catch (rejected: IllegalArgumentException) {
                    reportCodecRejection(callback)
                    throw rejected
                }
        }
        animation?.firstFrame()
    }

    /** Only known decoder or source-geometry rejection has a permanent codec status. */
    private fun reportCodecRejection(callback: Messenger) {
        runCatching {
            writeAnimationStatus(
                callback.binder,
                AnimationStatusInfo(
                    session,
                    AnimationFrameProtocol.CODEC_REJECTED,
                    Process.isIsolated(),
                    checkSelfPermission(android.Manifest.permission.INTERNET) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED,
                    (application as dev.ipf.whitenoise.android.WhiteNoiseApplication)
                        .initializedAppState() != null,
                ),
            )
        }
    }

    private fun nativeWork(block: () -> Unit) {
        val killer =
            watchdog.schedule(
                { Process.killProcess(Process.myPid()) },
                MediaDecodePolicy.WATCHDOG_MS,
                TimeUnit.MILLISECONDS,
            )
        try {
            block()
        } finally {
            killer.cancel(false)
        }
    }

    private fun armIdle() {
        idle?.cancel(false)
        idle =
            watchdog.schedule(
                { Process.killProcess(Process.myPid()) },
                MediaDecodePolicy.DEADLINE_MS,
                TimeUnit.MILLISECONDS,
            )
    }

    private fun canaryErrno(data: android.os.Bundle): Int {
        val path = data.getString("canaryPath")?.takeIf { it.endsWith("/files/media-containment-canary") }
        if (!dev.ipf.whitenoise.android.BuildConfig.DEBUG || path == null) return -1
        return try {
            val fd = android.system.Os.open(path, android.system.OsConstants.O_RDONLY, 0)
            android.system.Os.close(fd)
            0
        } catch (failure: android.system.ErrnoException) {
            failure.errno
        }
    }

    override fun onDestroy() {
        watchdog.shutdownNow()
        codecThread.quitSafely()
        super.onDestroy()
        if (Process.isIsolated()) Process.killProcess(Process.myPid())
    }
}
