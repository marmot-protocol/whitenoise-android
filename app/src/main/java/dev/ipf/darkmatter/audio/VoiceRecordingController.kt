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

/**
 * Bridges the [VoiceRecorder] state machine to Compose. The composer reads
 * [isRecording] and [elapsedMs] to drive its UI; the conversation screen
 * owns this instance and wires the permission grant + final-send callback.
 *
 * Lifecycle: instantiate once per conversation screen; pass the same
 * instance to [ComposerBar]. Call [release] from a `DisposableEffect`
 * `onDispose` so a backgrounded recording doesn't leak a `MediaRecorder`.
 */
@Stable
class VoiceRecordingController(
    private val context: Context,
    private val outputDirectory: File,
    private val scope: CoroutineScope,
    private val onPermissionRequest: () -> Boolean,
    private val onRecordingComplete: (file: File, durationMs: Long) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    var isRecording: Boolean by mutableStateOf(false)
        private set

    private val _elapsedMs = mutableLongStateOf(0L)
    val elapsedMs: Long get() = _elapsedMs.longValue

    private var recorder: VoiceRecorder? = null
    private var tickJob: Job? = null

    /**
     * Attempt to begin recording. Returns true if the recorder started.
     * False means the caller blocked us — typically a missing
     * `RECORD_AUDIO` permission, in which case [onPermissionRequest] has
     * already fired a system prompt and the user can press-and-hold again
     * after granting.
     */
    fun start(): Boolean {
        if (isRecording) return true
        if (!onPermissionRequest()) return false
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
            _elapsedMs.longValue = 0L
            tickJob =
                scope.launch(Dispatchers.Main) {
                    val started = System.nanoTime()
                    while (isActive) {
                        _elapsedMs.longValue = (System.nanoTime() - started) / 1_000_000L
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

    /**
     * Stop the recording. On success, fires [onRecordingComplete]; on a
     * too-short record or encoder error, fires [onError] (no completion).
     */
    fun stop() {
        val r = recorder ?: return
        recorder = null
        tickJob?.cancel()
        tickJob = null
        isRecording = false
        val result = r.stop()
        if (result == null) {
            onError(IllegalStateException("voice recording too short"))
        } else {
            onRecordingComplete(result.file, result.durationMs)
        }
    }

    /** Discard the in-flight recording. Safe to call when not recording. */
    fun cancel() {
        val r = recorder ?: return
        recorder = null
        tickJob?.cancel()
        tickJob = null
        isRecording = false
        r.cancel()
    }

    /**
     * Release any in-flight recording. Call on screen dispose so a held mic
     * + sudden navigate-away doesn't leak the underlying `MediaRecorder`.
     */
    fun release() {
        cancel()
    }
}
