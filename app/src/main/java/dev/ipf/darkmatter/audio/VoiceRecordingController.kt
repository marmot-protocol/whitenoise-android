package dev.ipf.darkmatter.audio

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    // Read lazily at record-start so a media-quality change mid-session takes
    // effect on the next recording without re-creating this controller.
    private val bitrateProvider: () -> Int = { VoiceRecorder.DEFAULT_BITRATE_BPS },
) {
    companion object {
        // Safety cap to prevent runaway recordings if the hold gesture leaks
        // (e.g. parent intercepts the up event). Five minutes is well past
        // the comfortable-voice-note range; longer payloads should be sent
        // as audio file attachments, not held-mic captures.
        const val MAX_RECORDING_MS: Long = 5L * 60L * 1000L
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
        val r = VoiceRecorder(context, file, bitrateProvider())
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
        // Finalize off the main thread: MediaRecorder.stop() flushes/finalizes
        // the MP4 container and can block for tens-to-hundreds of ms (worse on
        // slow storage), causing jank/ANR exactly as the record bar animates
        // away. UI state is already reset above; deliver the result on Main once
        // the container is finalized. See #372.
        scope.launch(Dispatchers.Main) {
            try {
                val result = withContext(Dispatchers.IO) { r.stop() }
                if (result == null) {
                    onError(IllegalStateException("voice recording too short"))
                } else {
                    onRecordingComplete(result.file, result.durationMs)
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                // VoiceRecorder.stop() is exception-safe today (returns null on
                // failure), but route any unexpected finalize error to onError
                // too — matching start()'s contract — rather than dropping it on
                // the scope's handler.
                onError(t)
            }
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
        // Same off-main finalize as stop() (#372); a cancel has nothing to
        // deliver, so just release the recorder in the background.
        scope.launch(Dispatchers.IO) { r.cancel() }
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
