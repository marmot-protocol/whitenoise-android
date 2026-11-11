package dev.ipf.whitenoise.android.audio

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
 *  release to review. Auto-stops at [MAX_RECORDING_MS]. */
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
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    private val onAutomaticStop: (VoiceRecordingStopReason) -> Unit = {},
) {
    companion object {
        // Keep the existing capture budget; the strip warns before this limit
        // and automatic finalization explains why recording stopped.
        const val MAX_RECORDING_MS: Long = 5L * 60L * 1000L
        const val LIMIT_WARNING_MS: Long = 30_000L
        const val LIMIT_HAPTIC_MS: Long = 10_000L

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

    /** True while another in-app sound holds focus; the take keeps its place until [resume]. */
    var isPaused: Boolean by mutableStateOf(false)
        private set

    // Takes whose tail is still being captured or whose container is still being written.
    private var finalizingTakes by mutableIntStateOf(0)

    /** True until every stopped take has released the microphone. */
    val isFinalizing: Boolean get() = finalizingTakes > 0

    private val _elapsedMs = mutableLongStateOf(0L)
    val elapsedMs: Long get() = _elapsedMs.longValue
    val remainingMs: Long get() = (MAX_RECORDING_MS - elapsedMs).coerceAtLeast(0L)
    val isNearLimit: Boolean get() = isRecording && remainingMs <= LIMIT_WARNING_MS

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

    @Volatile
    private var recordingFocusHeld = false
    private val microphoneLeaseLock = Any()
    private var microphoneLeaseHeld = false

    init {
        AudioFocusOwner.attach(context)
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

    // A discarded take's native close. A new take waits for it before opening a recorder (#3707).
    private var discardJob: Job? = null
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
            releaseMicrophoneLeaseUnlessDiscarding()
            onError(IllegalStateException("Couldn't start recording — audio is in use"))
            return false
        }
        VoicePlaybackController.pause()
        isRecording = true
        resetRecordingUiState()
        val generation = nextStartGeneration()
        val discarding = discardJob?.takeIf { it.isActive }
        val launched =
            scope.launch(mainDispatcher) {
                // Opening a second recorder while a discarded one still holds the mic can fail on devices
                // without concurrent capture, so wait for that close first.
                discarding?.join()
                beginRecording(generation)
            }
        startJob = launched
        launched.invokeOnCompletion {
            if (startJob === launched) startJob = null
            val captureStopped = !isRecording && recorder == null
            val noPendingFinalize =
                finalizeJob?.isActive != true && restartJob?.isActive != true && discardJob?.isActive != true
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
            // start() already resets the take. Preserve lock/drag intent accepted
            // while native prepare/start was suspended.
            startTick(r, baseElapsedMs = 0L)
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

    /** Publishes captured time from [baseElapsedMs] and stops the take at its limit or interruption. */
    private fun startTick(
        r: VoiceRecordingSession,
        baseElapsedMs: Long,
    ) {
        tickJob =
            scope.launch(mainDispatcher) {
                val started = elapsedRealtime() - baseElapsedMs
                while (isActive) {
                    val elapsed = (elapsedRealtime() - started).coerceAtLeast(0L)
                    _elapsedMs.longValue = elapsed.coerceAtMost(MAX_RECORDING_MS)
                    when {
                        r.captureInterrupted -> finish(VoiceRecordingStopReason.Interrupted)
                        elapsed >= MAX_RECORDING_MS -> finish(VoiceRecordingStopReason.LimitReached)
                    }
                    if (!isRecording) break
                    delay(50L)
                }
            }
    }

    /** Another in-app sound took focus: pause the take and keep its place. */
    private fun pauseCapture() {
        val r = recorder
        if (r == null) {
            // Nothing captured yet (still preparing or restarting): end the pending start.
            if (isRecording) interrupt()
        } else if (!isPaused) {
            tickJob?.cancel()
            tickJob = null
            if (runCatching { r.pause() }.isSuccess) isPaused = true else interrupt()
        }
    }

    /** Continues a paused take after the user asks for it; returns false when focus is unavailable. */
    fun resume(): Boolean {
        val r = recorder
        return when {
            r == null -> false
            !isPaused -> true
            !requestRecordingFocus() -> {
                onError(IllegalStateException("Couldn't resume recording — audio is in use"))
                false
            }
            else -> {
                VoicePlaybackController.pause()
                val resumed = runCatching { r.resume() }.isSuccess
                if (resumed) {
                    isPaused = false
                    startTick(r, baseElapsedMs = _elapsedMs.longValue)
                } else {
                    interrupt()
                }
                resumed
            }
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
        isPaused = false
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

    /** Finalizes an ordinary release after its short trailing-audio window. */
    fun stop() = finish()

    /** Preserve captured audio before Android can silence a background microphone. */
    fun interrupt() = finish(VoiceRecordingStopReason.Interrupted)

    /** Detaches capture once and starts review finalization, skipping the tail for automatic stops. */
    private fun finish(reason: VoiceRecordingStopReason? = null) {
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
        isPaused = false
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
                finalizeRecording(r, cut, reason)
            }
    }

    /** Finalized bytes belong either to review or to this cleanup, even if Main is cancelled. */
    private suspend fun finalizeRecording(
        recording: VoiceRecordingSession,
        cut: CompletableDeferred<Unit>,
        reason: VoiceRecordingStopReason?,
    ) {
        var finalized: VoiceRecordingResult? = null
        var delivered = false
        finalizingTakes++
        try {
            if (reason == null) withTimeoutOrNull(RECORDING_TAIL_MS) { cut.await() }
            val result =
                withContext(recorderDispatcher) {
                    recording.stop().also { finalized = it }
                }
            if (result == null) {
                onError(
                    if (reason == null) {
                        IllegalStateException("voice recording too short")
                    } else {
                        VoiceRecordingSaveException()
                    },
                )
            } else {
                // The callback owns the bytes from this point (including rejection).
                delivered = true
                onRecordingComplete(result.file, result.durationMs)
                if (reason != null) onAutomaticStop(reason)
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            onError(t)
        } finally {
            // stop may have committed bytes on IO just as withContext cancels
            // delivery to Main. The recorder no longer owns that completed file.
            try {
                withContext(NonCancellable + recorderDispatcher) {
                    try {
                        recording.cancel()
                    } finally {
                        if (!delivered) runCatching { finalized?.file?.delete() }
                    }
                }
            } finally {
                // Returning from IO can throw cancellation before Main resumes.
                if (!restarting) {
                    abandonRecordingFocus()
                    releaseMicrophoneLease()
                }
                finalizingTakes--
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
            if (pendingStart == null) releaseMicrophoneLeaseUnlessDiscarding() else pendingStart.cancel()
            return
        }
        recorder = null
        tickJob?.cancel()
        tickJob = null
        isRecording = false
        locked = false
        isPaused = false
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
            // A discarded take is still closing until the native recorder has released the microphone.
            finalizingTakes++
            discardJob =
                recorderScope.launch {
                    try {
                        r.cancel()
                    } finally {
                        withContext(NonCancellable + mainDispatcher) {
                            finalizingTakes--
                            // A newer take that holds a recorder or is still opening one owns the shared lease
                            // and releases it itself. A start that is only cancelling leaves the lease here,
                            // since it defers to this still-active job, and so does a stale isRecording flag.
                            if (recorder == null && startJob?.isActive != true && restartJob?.isActive != true) {
                                releaseMicrophoneLease()
                            }
                        }
                    }
                }
        } else if (finalizeJob?.isActive == true) {
            // stop() already detached the recorder into its finalize job. End
            // the tail and cancel delivery, but keep the lease until that job's
            // NonCancellable finally has released the native recorder.
            tailCut?.complete(Unit)
            finalizeJob?.cancel()
        } else {
            releaseMicrophoneLeaseUnlessDiscarding()
        }
    }

    /**
     * Acquires transient speech focus through the shared arbiter. Another app's
     * loss preserves the take for review; another in-app sound pauses it in place.
     */
    private fun requestRecordingFocus(): Boolean {
        val granted =
            AudioFocusOwner.acquireWithFocusChanges(
                owner = AudioFocusOwner.Owner.Recording,
                audioAttributes = AudioFocusOwner.ttsSpeechAttributes,
                focusGain = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
                onFocusChange = { change ->
                    if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        scope.launch(mainDispatcher) {
                            if (recordingFocusHeld) interrupt()
                        }
                    }
                },
                onOwnerSurrender = {
                    recordingFocusHeld = false
                    scope.launch(mainDispatcher) { pauseCapture() }
                },
            )
        if (granted) recordingFocusHeld = true
        return granted
    }

    private fun abandonRecordingFocus() {
        recordingFocusHeld = false
        AudioFocusOwner.release(AudioFocusOwner.Owner.Recording)
    }

    private fun acquireMicrophoneLease(): Boolean {
        return synchronized(microphoneLeaseLock) {
            if (microphoneLeaseHeld) return@synchronized true
            val acquired = microphoneCaptures?.tryAcquire(this) ?: true
            microphoneLeaseHeld = acquired
            acquired
        }
    }

    /**
     * Releases the lease now unless a discarded take is still closing its recorder, in which case that
     * cleanup releases it once the microphone is actually free (#3707).
     */
    private fun releaseMicrophoneLeaseUnlessDiscarding() {
        if (discardJob?.isActive == true) return
        releaseMicrophoneLease()
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
