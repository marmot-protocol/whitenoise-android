package dev.ipf.whitenoise.android.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.state.StalenessGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.TimeUnit

/** Hold-to-record controller. Slide left to cancel, slide up to lock,
 *  release to send. Auto-stops at [MAX_RECORDING_MS]. */
@Stable
class VoiceRecordingController internal constructor(
    private val context: Context,
    private val outputDirectory: File,
    private val scope: CoroutineScope,
    private val onPermissionRequest: () -> Boolean,
    private val onRecordingComplete: (file: File, durationMs: Long) -> Unit,
    private val onError: (Throwable) -> Unit,
    // Read lazily at record-start so a media-quality change mid-session takes
    // effect on the next recording without re-creating this controller.
    private val bitrateProvider: () -> Int = { VoiceRecorder.DEFAULT_BITRATE_BPS },
    private val microphoneCaptures: MicrophoneCaptureCoordinator? = null,
    private val recorderFactory: (Context, File, Int) -> VoiceRecordingSession = { recorderContext, file, bitrate ->
        VoiceRecorder(recorderContext, file, bitrate)
    },
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val recorderDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    companion object {
        // Safety cap to prevent runaway recordings if the hold gesture leaks
        // (e.g. parent intercepts the up event). Five minutes is well past
        // the comfortable-voice-note range; longer payloads should be sent
        // as audio file attachments, not held-mic captures.
        const val MAX_RECORDING_MS: Long = 5L * 60L * 1000L

        // Keep capturing this long after the user releases before finalizing the
        // encoder, so the trailing word isn't clipped when they release as their
        // last syllable ends. Short enough to stay imperceptible as send latency.
        const val RECORDING_TAIL_MS: Long = 400L

        internal val STALE_VOICE_TEMP_AGE_MS: Long = TimeUnit.HOURS.toMillis(24)

        internal fun sweepStaleVoiceTempFiles(
            outputDirectory: File,
            nowMillis: Long = System.currentTimeMillis(),
            staleAgeMillis: Long = STALE_VOICE_TEMP_AGE_MS,
        ): Int {
            val cutoff = nowMillis - staleAgeMillis
            return outputDirectory
                .listFiles { file ->
                    file.isFile &&
                        file.name.startsWith("voice-") &&
                        file.name.endsWith(".${VoiceRecorder.FILE_EXTENSION}") &&
                        file.lastModified() in 1 until cutoff
                }?.count { runCatching { it.delete() }.getOrDefault(false) }
                ?: 0
        }
    }

    var isRecording: Boolean by mutableStateOf(false)
        private set
    var locked: Boolean by mutableStateOf(false)
        private set

    private val _elapsedMs = mutableLongStateOf(0L)
    val elapsedMs: Long get() = _elapsedMs.longValue

    var dragOffsetPx: Float by mutableFloatStateOf(0f)
        private set
    var verticalOffsetPx: Float by mutableFloatStateOf(0f)
        private set
    var willCancel: Boolean by mutableStateOf(false)
        private set
    var willLock: Boolean by mutableStateOf(false)
        private set

    private var recorder: VoiceRecordingSession? = null
    private var tickJob: Job? = null

    // Releases the native recorder independently of [scope]: the conversation's
    // composition scope is cancelled exactly when the screen closes, which is
    // the moment a mid-recording teardown must still free the mic and the
    // output file descriptor. Releases are idempotent, so this never
    // double-frees a recorder that stop() already finalized.
    private val recorderScope = CoroutineScope(SupervisorJob() + recorderDispatcher)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private val microphoneLeaseLock = Any()
    private var microphoneLeaseHeld = false

    init {
        // Best-effort startup cleanup: a filesystem hiccup (e.g. a
        // SecurityException from listFiles on a restricted directory) must not
        // reach the scope's default handler and crash controller construction.
        // Swallow non-cancellation failures; stale temp files get swept on a
        // later init.
        recorderScope.launch {
            try {
                sweepStaleVoiceTempFiles(outputDirectory)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // ignore
            }
        }
    }

    // The in-flight stop() finalize (the post-release tail + encoder stop) and a
    // signal to cut its tail short when a new recording starts during it.
    private var finalizeJob: Job? = null
    private var tailCut: CompletableDeferred<Unit>? = null
    private var restarting = false
    private var restartJob: Job? = null
    private var startJob: Job? = null
    private val startRequests = StalenessGuard()

    /** Starts or resumes the single recording request accepted by this controller. */
    fun start(): Boolean {
        if (isRecording) return true
        if (!onPermissionRequest()) return false
        if (!acquireMicrophoneLease()) {
            onError(IllegalStateException("Microphone is already in use"))
            return false
        }

        val pending = finalizeJob?.takeIf { it.isActive }
        if (pending != null) {
            // A previous take is still finalizing its tail and holds the mic and
            // audio focus. Cut the tail to release the mic now, reuse that focus,
            // and open the new recorder only once the old one has stopped —
            // opening a second recorder mid-capture throws "mic busy" on devices
            // without concurrent capture.
            restarting = true
            tailCut?.complete(Unit)
            VoicePlaybackController.pause()
            isRecording = true
            resetRecordingUiState()
            val generation = nextStartGeneration()
            restartJob =
                scope.launch(mainDispatcher) {
                    try {
                        pending.join()
                        restarting = false
                        if (isRecording && startRequests.isCurrent(generation)) beginRecording(generation)
                    } finally {
                        completeRestart()
                    }
                }
            return true
        }

        // Request transient audio focus AFTER permission is confirmed (a denied
        // prompt shouldn't disturb playback). Focus pauses other apps' media for
        // the duration of the capture and resumes it when abandoned on
        // stop/cancel. A denied grant means the mic is unavailable (e.g. an
        // active call) — surface it rather than capture competing audio.
        if (!requestRecordingFocus()) {
            releaseMicrophoneLease()
            onError(IllegalStateException("Couldn't start recording — audio is in use"))
            return false
        }
        VoicePlaybackController.pause()
        isRecording = true
        resetRecordingUiState()
        val generation = nextStartGeneration()
        val launched = scope.launch(mainDispatcher) { beginRecording(generation) }
        startJob = launched
        launched.invokeOnCompletion {
            if (startJob === launched) startJob = null
            val captureStopped = !isRecording && recorder == null
            val noPendingFinalize = finalizeJob?.isActive != true && restartJob?.isActive != true
            if (captureStopped && noPendingFinalize) {
                releaseMicrophoneLease()
            }
        }
        return true
    }

    /** Clears restart bookkeeping and releases the microphone lease after a stopped restart. */
    private fun completeRestart() {
        restartJob = null
        restarting = false
        if (!isRecording && recorder == null) releaseMicrophoneLease()
    }

    /** Publishes a prepared recorder only if [generation] still owns the start. */
    private suspend fun beginRecording(generation: Long): Boolean {
        val file =
            File(
                outputDirectory,
                "voice-${System.currentTimeMillis()}.${VoiceRecorder.FILE_EXTENSION}",
            )
        val r = recorderFactory(context, file, bitrateProvider())
        return try {
            withContext(recorderDispatcher) { r.start() }
            if (!isRecording || !startRequests.isCurrent(generation)) {
                withContext(recorderDispatcher) { r.cancel() }
                return false
            }
            recorder = r
            isRecording = true
            resetRecordingUiState()
            tickJob =
                scope.launch(mainDispatcher) {
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
        } catch (c: CancellationException) {
            releaseRecorderAfterStartFailure(r)
            recorder = null
            if (isRecording) {
                isRecording = false
                resetRecordingUiState()
                abandonRecordingFocus()
            }
            releaseMicrophoneLease()
            throw c
        } catch (t: Throwable) {
            releaseRecorderAfterStartFailure(r)
            recorder = null
            val reportError = isRecording
            if (isRecording) {
                abandonRecordingFocus()
                isRecording = false
            }
            releaseMicrophoneLease()
            if (reportError) onError(t)
            false
        }
    }

    /** Accepts a new recorder-start request and returns its token. */
    private fun nextStartGeneration(): Long = startRequests.advance()

    /** Makes any recorder still preparing in the background ineligible to publish. */
    private fun invalidatePendingStart() {
        startRequests.advance()
    }

    private suspend fun releaseRecorderAfterStartFailure(recorder: VoiceRecordingSession) {
        withContext(NonCancellable + recorderDispatcher) {
            runCatching { recorder.cancel() }
        }
    }

    private fun resetRecordingUiState() {
        locked = false
        _elapsedMs.longValue = 0L
        dragOffsetPx = 0f
        verticalOffsetPx = 0f
        willCancel = false
        willLock = false
    }

    // A restart deferred the new recorder's creation while isRecording is already
    // true; if the user stops/cancels in that window, abort the deferred start so
    // it can't create an orphaned recorder, and release the reused focus. The old
    // take's finalize keeps delivering its result independently.
    private fun abortPendingRestart(): Boolean {
        val restart = restartJob ?: return false
        if (!restart.isActive) return false
        restart.cancel()
        restartJob = null
        restarting = false
        invalidatePendingStart()
        isRecording = false
        resetRecordingUiState()
        abandonRecordingFocus()
        return true
    }

    fun stop() {
        if (abortPendingRestart()) return
        val r =
            recorder ?: run {
                if (isRecording) {
                    invalidatePendingStart()
                    isRecording = false
                    resetRecordingUiState()
                    abandonRecordingFocus()
                    val pendingStart = startJob
                    if (pendingStart == null) releaseMicrophoneLease() else pendingStart.cancel()
                }
                return
            }
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
        val cut = CompletableDeferred<Unit>()
        tailCut = cut
        finalizeJob =
            scope.launch(mainDispatcher, start = CoroutineStart.UNDISPATCHED) {
                try {
                    // Keep the encoder running a short tail so the trailing word
                    // isn't clipped. Only the send path (stop) pays this; cancel
                    // never does. A new start() completes `cut` to end the tail
                    // early and free the mic. The recorder captures until r.stop().
                    withTimeoutOrNull(RECORDING_TAIL_MS) { cut.await() }
                    val result = withContext(recorderDispatcher) { r.stop() }
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
                } finally {
                    // UNDISPATCHED starts this try/finally before stop() returns,
                    // even when the composition scope is cancelled in the same
                    // frame. Finish native teardown in NonCancellable so no
                    // lifecycle cancellation can expose a free shared lease
                    // while MediaRecorder is still capturing.
                    withContext(NonCancellable + recorderDispatcher) {
                        r.cancel()
                    }
                    // A restart reuses this take's focus for the next recording.
                    if (!restarting) {
                        abandonRecordingFocus()
                        releaseMicrophoneLease()
                    }
                }
            }
    }

    fun cancel() {
        if (abortPendingRestart()) return
        val r = recorder
        if (r == null && isRecording) {
            invalidatePendingStart()
            isRecording = false
            resetRecordingUiState()
            abandonRecordingFocus()
            val pendingStart = startJob
            if (pendingStart == null) releaseMicrophoneLease() else pendingStart.cancel()
            return
        }
        recorder = null
        tickJob?.cancel()
        tickJob = null
        isRecording = false
        locked = false
        dragOffsetPx = 0f
        verticalOffsetPx = 0f
        willCancel = false
        willLock = false
        // Abandon focus unconditionally: during a restart the previous recorder
        // is already null while the reused focus is still held, so a teardown
        // here (e.g. composition disposal mid-restart) must release the focus
        // even when there's no recorder left to cancel. Idempotent when unheld.
        abandonRecordingFocus()
        // Same off-main finalize as stop() (#372); a cancel has nothing to
        // deliver, so just release the recorder in the background. No tail delay
        // — the take was discarded. Released on the lifecycle-independent scope
        // so a teardown that races composition disposal still frees the mic.
        if (r != null) {
            recorderScope.launch {
                r.cancel()
                releaseMicrophoneLease()
            }
        } else if (finalizeJob?.isActive == true) {
            // stop() already detached the recorder into its finalize job. End
            // the tail and cancel delivery, but keep the lease until that job's
            // NonCancellable finally has released the native recorder.
            tailCut?.complete(Unit)
            finalizeJob?.cancel()
        } else {
            releaseMicrophoneLease()
        }
    }

    private fun requestRecordingFocus(): Boolean {
        val am = audioManager ?: return true
        val attrs =
            AudioAttributes
                .Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build()
        val req =
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .build()
        return (am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            .also { granted -> if (granted) focusRequest = req }
    }

    private fun abandonRecordingFocus() {
        val am = audioManager ?: return
        focusRequest?.let { am.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private fun acquireMicrophoneLease(): Boolean {
        return synchronized(microphoneLeaseLock) {
            if (microphoneLeaseHeld) return@synchronized true
            val acquired = microphoneCaptures?.tryAcquire(this) ?: true
            microphoneLeaseHeld = acquired
            acquired
        }
    }

    private fun releaseMicrophoneLease() {
        synchronized(microphoneLeaseLock) {
            if (!microphoneLeaseHeld) return
            microphoneLeaseHeld = false
            microphoneCaptures?.release(this)
        }
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
        // Match the actual lock gesture, which also requires staying out of the
        // cancel zone; otherwise the hint shows "armed" in the up+left overlap
        // region where release would actually cancel.
        willLock = (-deltaY) > lockThresholdPx && (-deltaX) <= cancelThresholdPx
    }

    fun release() {
        cancel()
    }
}
