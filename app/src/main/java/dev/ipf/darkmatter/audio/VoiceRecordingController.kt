package dev.ipf.darkmatter.audio

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** Hold-to-record controller. Slide left to cancel, slide up to lock,
 *  release to send. Auto-stops at [MAX_RECORDING_MS]. */
@Stable
class VoiceRecordingController(
    private val context: Context,
    private val outputDirectory: File,
    private val scope: CoroutineScope,
    private val onPermissionRequest: () -> Boolean,
    private val onRecordingComplete: (file: File, durationMs: Long) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    companion object {
        const val MAX_RECORDING_MS: Long = 60_000L
    }

    var isRecording: Boolean by mutableStateOf(false)
        private set
    var locked: Boolean by mutableStateOf(false)
        private set

    private val _elapsedMs = mutableLongStateOf(0L)
    val elapsedMs: Long get() = _elapsedMs.longValue

    var dragOffsetPx: Float by mutableStateOf(0f)
        private set
    var verticalOffsetPx: Float by mutableStateOf(0f)
        private set
    var willCancel: Boolean by mutableStateOf(false)
        private set
    var willLock: Boolean by mutableStateOf(false)
        private set

    private var recorder: VoiceRecorder? = null
    private var tickJob: Job? = null

    fun start(): Boolean {
        if (isRecording) return true
        if (!onPermissionRequest()) return false
        // Pause AFTER permission is confirmed — a denied prompt shouldn't
        // also silence whatever the user was listening to.
        VoicePlaybackController.pause()
        val file =
            File(
                outputDirectory,
                "voice-${System.currentTimeMillis()}.${VoiceRecorder.FILE_EXTENSION}",
            )
        val r = VoiceRecorder(context, file)
        return try {
            r.start()
            recorder = r
            isRecording = true
            locked = false
            _elapsedMs.longValue = 0L
            dragOffsetPx = 0f
            verticalOffsetPx = 0f
            willCancel = false
            willLock = false
            tickJob =
                scope.launch(Dispatchers.Main) {
                    val started = System.nanoTime()
                    while (isActive) {
                        val elapsed = (System.nanoTime() - started) / 1_000_000L
                        _elapsedMs.longValue = elapsed
                        if (elapsed >= MAX_RECORDING_MS) {
                            stop()
                            break
                        }
                        delay(50L)
                    }
                }
            true
        } catch (t: Throwable) {
            recorder = null
            isRecording = false
            onError(t)
            false
        }
    }

    fun stop() {
        val r = recorder ?: return
        recorder = null
        tickJob?.cancel()
        tickJob = null
        isRecording = false
        locked = false
        dragOffsetPx = 0f
        verticalOffsetPx = 0f
        willCancel = false
        willLock = false
        val result = r.stop()
        if (result == null) {
            onError(IllegalStateException("voice recording too short"))
        } else {
            onRecordingComplete(result.file, result.durationMs)
        }
    }

    fun cancel() {
        val r = recorder ?: return
        recorder = null
        tickJob?.cancel()
        tickJob = null
        isRecording = false
        locked = false
        dragOffsetPx = 0f
        verticalOffsetPx = 0f
        willCancel = false
        willLock = false
        r.cancel()
    }

    fun lock() {
        if (!isRecording || locked) return
        locked = true
        dragOffsetPx = 0f
        verticalOffsetPx = 0f
        willCancel = false
        willLock = false
    }

    fun updateDrag(
        deltaX: Float,
        deltaY: Float,
        cancelThresholdPx: Float,
        lockThresholdPx: Float,
    ) {
        if (!isRecording || locked) return
        dragOffsetPx = deltaX.coerceAtMost(0f)
        verticalOffsetPx = deltaY.coerceAtMost(0f)
        willCancel = (-deltaX) > cancelThresholdPx
        willLock = (-deltaY) > lockThresholdPx
    }

    fun release() {
        cancel()
    }
}
