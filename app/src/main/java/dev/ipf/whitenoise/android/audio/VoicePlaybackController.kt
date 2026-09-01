package dev.ipf.whitenoise.android.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.util.Log
import android.util.LruCache
import dev.ipf.whitenoise.android.state.StalenessGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

internal class VoicePlaybackRequestSerializer {
    private val mutex = Mutex()

    suspend fun <T> withSerializedPlayback(block: suspend () -> T): T = mutex.withLock { block() }
}

/**
 * Process-wide voice playback singleton. Only one MediaPlayer is active at
 * a time — starting playback on a new key implicitly stops any prior clip.
 *
 * Bubbles subscribe to [state] and decide their visual from [PlaybackState.key]
 * vs. their own message-attachment key.
 */
object VoicePlaybackController {
    private const val TAG = "VoicePlaybackController"
    private const val TICK_INTERVAL_MS = 60L
    private const val DUCK_VOLUME = 0.2f

    // Cap on cached per-clip durations. Each entry is a boxed Int keyed by an
    // absolute file path; without a bound the map held one entry per distinct
    // voice clip ever probed for the process lifetime (see #230). 256 is far
    // more than the clips visible in any realistic scroll window, while
    // keeping worst-case memory flat regardless of session length.
    private const val DURATION_CACHE_MAX_ENTRIES = 256

    /**
     * Voice playback speeds available to the user. Tap the bubble's speed
     * pill to cycle. Persists across pause/resume and across clips so a
     * "give me everything faster" preference carries forward.
     */
    val speedOptions: FloatArray = floatArrayOf(1f, 1.5f, 2f)

    data class PlaybackState(
        val key: String? = null,
        val isPlaying: Boolean = false,
        val positionMs: Int = 0,
        val durationMs: Int = 0,
        val speed: Float = 1f,
    )

    enum class PlaybackStartResult {
        Started,
        Resumed,
        PrepareFailed,
        FocusDenied,
        StartFailed,
        Superseded,
    }

    data class PlaybackFailure(
        val key: String,
        val invalidatesCache: Boolean,
    )

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _failures = MutableSharedFlow<PlaybackFailure>(extraBufferCapacity = 8)
    val failures: SharedFlow<PlaybackFailure> = _failures.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Count-bounded LRU (android.util.LruCache is internally synchronized).
    // Mirrors AvatarImageLoader's bounded-cache approach for the same class
    // of process-lifetime leak.
    private val durationCache = LruCache<String, Int>(DURATION_CACHE_MAX_ENTRIES)

    private var player: MediaPlayer? = null
    private var currentKey: String? = null
    private var currentOwnerKey: String? = null
    private var tickerJob: Job? = null
    private var currentSpeed: Float = 1f
    private val playbackRequests = StalenessGuard()

    // play() suspends while MediaPlayer prepares on IO; serialize callers so
    // only one prepared player can ever reach start()/assignment.
    private val playSerializer = VoicePlaybackRequestSerializer()

    private var resumeOnAudioFocusGain = false
    private var duckedForAudioFocusLoss = false
    private val speechAudioAttributes: AudioAttributes =
        AudioAttributes
            .Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build()

    /** Call once from Application.onCreate so playback can request audio focus. */
    fun attach(context: Context) {
        AudioFocusOwner.attach(context)
    }

    /** Stops playback when another audio owner takes focus. */
    internal fun stopForAudioHandoff() {
        stop()
    }

    private data class CompletionCallback(
        val ownerKey: String,
        val token: Any,
        val callback: (completedKey: String) -> Unit,
    )

    private var completionCallback: CompletionCallback? = null

    /**
     * Fires once when MediaPlayer reports completion, scoped to the owner that
     * started playback. Conversation screens use this to chain into the next
     * voice attachment without letting a completion from a previous screen drive
     * the currently visible screen.
     */
    fun registerCompletionCallback(
        ownerKey: String,
        callback: (completedKey: String) -> Unit,
    ): () -> Unit {
        val token = Any()
        completionCallback = CompletionCallback(ownerKey, token, callback)
        return {
            if (completionCallback?.token === token) {
                completionCallback = null
            }
        }
    }

    /**
     * Cycle to the next speed in [speedOptions] and apply it to the active
     * player if one is running. Returns the new speed so callers can
     * persist it in their UI. MediaPlayer.PlaybackParams is supported from
     * API 23 onward.
     */
    fun cycleSpeed(): Float {
        var idx = 0
        for (i in speedOptions.indices) {
            if (speedOptions[i] == currentSpeed) {
                idx = i
                break
            }
        }
        currentSpeed = speedOptions[(idx + 1) % speedOptions.size]
        applySpeedToActive()
        _state.value = _state.value.copy(speed = currentSpeed)
        return currentSpeed
    }

    private fun applySpeedToActive() {
        val mp = player ?: return
        runCatching {
            val wasPlaying = mp.isPlaying
            mp.playbackParams = mp.playbackParams.setSpeed(currentSpeed)
            // setPlaybackParams can flip the player into playing state on
            // some devices; force the caller's intent.
            if (!wasPlaying && mp.isPlaying) mp.pause()
        }.onFailure { Log.w(TAG, "voice_playback_speed_update_failed") }
    }

    /**
     * Quick metadata probe so a bubble can show the clip's total duration
     * before the user taps Play. Result cached per file path.
     */
    suspend fun probeDuration(file: File): Int {
        val path = file.absolutePath
        durationCache.get(path)?.let { return it }
        val probed =
            withContext(Dispatchers.IO) {
                runCatching {
                    MediaMetadataRetriever().use { mmr ->
                        mmr.setDataSource(path)
                        mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0
                    }
                }.getOrDefault(0)
            }
        // Only cache a real duration. A transient probe (file still being
        // written, momentary IO error) yields 0; caching that would pin the
        // clip to "no duration" for the entry's lifetime, so leave it uncached
        // and let the next display retry.
        if (probed > 0) durationCache.put(path, probed)
        return probed
    }

    /**
     * Start playback for [key] backed by [file]. If [key] is already the
     * paused track, resume from the existing position; otherwise tear down
     * the current player and start fresh. Any other key playing is stopped.
     */
    suspend fun play(
        key: String,
        file: File,
        ownerKey: String? = null,
    ): PlaybackStartResult = playSerializer.withSerializedPlayback { playLocked(key, file, ownerKey) }

    /** Prepares one player and publishes it only while this request remains newest. */
    private suspend fun playLocked(
        key: String,
        file: File,
        ownerKey: String?,
    ): PlaybackStartResult {
        // A user tap after transient loss must not wait forever for an OEM to
        // deliver AUDIOFOCUS_GAIN. Drop the retained request so requestFocus()
        // below performs a fresh arbitration and can still deny us cleanly.
        if (resumeOnAudioFocusGain) abandonFocus()
        clearAudioFocusInterruption(restoreVolume = true)
        if (currentKey == key && player != null) {
            // User-paused playback abandons focus, so reacquire it before
            // resuming. A user retry after transient loss also arrives here
            // after dropping its retained request above.
            if (!requestFocus()) {
                // Focus denied — stay paused rather than playing unfocused.
                return PlaybackStartResult.FocusDenied
            }
            val activePlayer = player ?: return PlaybackStartResult.StartFailed
            if (!startCurrentPlayer(activePlayer)) return PlaybackStartResult.StartFailed
            currentOwnerKey = ownerKey
            _state.value =
                _state.value.copy(
                    key = key,
                    isPlaying = true,
                    durationMs = activePlayer.duration,
                )
            startTicker()
            return PlaybackStartResult.Resumed
        }
        val prepareGeneration = nextPlaybackGeneration()
        releasePlayerInternal()
        _state.value = PlaybackState(key = key, isPlaying = false, speed = currentSpeed)
        val mp =
            withContext(Dispatchers.IO) {
                runCatching {
                    MediaPlayer().apply {
                        setAudioAttributes(
                            AudioAttributes
                                .Builder()
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .build(),
                        )
                        setDataSource(file.absolutePath)
                        prepare()
                    }
                }.onFailure { Log.w(TAG, "voice_playback_prepare_failed") }
                    .getOrNull()
                    ?.also {
                        // If the caller was cancelled while prepare() ran (the
                        // clip scrolled away, the screen was left, a sign-out
                        // landed), withContext discards this result on resume and
                        // releasePlayerInternal can never find it — release here
                        // so the native player + its file descriptor don't leak.
                        // See #370.
                        if (!isActive) it.runCatching { release() }
                    }?.takeIf { isActive }
            } ?: run {
                _state.value = PlaybackState()
                return PlaybackStartResult.PrepareFailed
            }
        if (!playbackRequests.isCurrent(prepareGeneration)) {
            mp.runCatching { release() }
            return PlaybackStartResult.Superseded
        }
        // MediaPlayer instantiated on Dispatchers.IO has no Looper → its
        // callbacks fire on an internal MediaPlayer thread. State that we
        // also touch from Main (player, currentKey, _state) must only be
        // mutated on Main; hop through scope.launch.
        mp.setOnCompletionListener { p ->
            scope.launch {
                // Bind to this player instance. These callbacks fire on the
                // MediaPlayer thread and hop to Main, so a completion for a clip
                // whose player was already torn down by a newer play() can land
                // mid-transition. Without this guard it would reset state or
                // auto-advance the wrong clip (onCompletion of the old key). See #470.
                if (player !== p) return@launch
                val completed = currentKey
                val completedOwner = currentOwnerKey
                releasePlayerInternal()
                _state.value = PlaybackState()
                val callback = completionCallback
                if (completed != null && completedOwner != null && callback?.ownerKey == completedOwner) {
                    callback.callback(completed)
                }
            }
        }
        mp.setOnErrorListener { p, what, extra ->
            scope.launch {
                Log.w(TAG, "MediaPlayer error what=$what extra=$extra")
                // Same stale-callback guard as completion (#470): only the active
                // player's error tears playback down; a superseded player has
                // already been released by the play() that replaced it.
                if (player === p) {
                    val failedKey = currentKey
                    releasePlayerInternal()
                    _state.value = PlaybackState()
                    if (failedKey != null) {
                        _failures.emit(PlaybackFailure(failedKey, invalidatesCache = true))
                    }
                }
            }
            true
        }
        if (!requestFocus()) {
            mp.runCatching { release() }
            _state.value = PlaybackState()
            return PlaybackStartResult.FocusDenied
        }
        if (!startPreparedNewPlayer(mp)) return PlaybackStartResult.StartFailed
        player = mp
        currentKey = key
        currentOwnerKey = ownerKey
        // Mirror probeDuration's positivity guard (#275): some streams report
        // a non-positive duration at start() time. Caching that value would
        // pin the clip to "no duration" via the durationCache read short-circuit
        // and silently re-introduce the bug #275 fixed in probeDuration.
        val reportedDurationMs = mp.duration
        if (reportedDurationMs > 0) durationCache.put(file.absolutePath, reportedDurationMs)
        applySpeedToActive()
        _state.value =
            PlaybackState(
                key = key,
                isPlaying = true,
                positionMs = 0,
                durationMs = reportedDurationMs,
                speed = currentSpeed,
            )
        startTicker()
        return PlaybackStartResult.Started
    }

    private fun startCurrentPlayer(mp: MediaPlayer): Boolean =
        startPlayer(mp, "MediaPlayer resume failed") {
            releasePlayerInternal()
            _state.value = PlaybackState()
        }

    private fun startPreparedNewPlayer(mp: MediaPlayer): Boolean =
        startPlayer(mp, "MediaPlayer start failed") {
            abandonFocus()
            mp.runCatching { release() }
            _state.value = PlaybackState()
        }

    private fun startPlayer(
        mp: MediaPlayer,
        failureMessage: String,
        onFailure: () -> Unit,
    ): Boolean =
        runCatching { mp.start() }
            .onFailure {
                Log.w(TAG, failureMessage)
                onFailure()
            }.isSuccess

    private fun requestFocus(): Boolean =
        AudioFocusOwner.acquireWithFocusChanges(
            owner = AudioFocusOwner.Owner.Voice,
            audioAttributes = speechAudioAttributes,
            focusGain = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            onFocusChange = ::handleAudioFocusChange,
            onOwnerSurrender = ::stopForAudioHandoff,
        )

    private fun abandonFocus() {
        AudioFocusOwner.release(AudioFocusOwner.Owner.Voice)
    }

    private fun handleAudioFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pauseForTransientAudioFocusLoss()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> duckForTransientAudioFocusLoss()
            AudioManager.AUDIOFOCUS_GAIN -> restoreAfterAudioFocusGain()
        }
    }

    private fun pauseForTransientAudioFocusLoss() {
        val mp = player ?: return
        val wasPlaying =
            runCatching { mp.isPlaying }.getOrElse {
                releaseAfterPlayerControlFailure("MediaPlayer state query failed during transient focus loss")
                return
            }
        if (!wasPlaying) return
        val pauseFailure = runCatching { mp.pause() }.exceptionOrNull()
        if (pauseFailure != null) {
            releaseAfterPlayerControlFailure("MediaPlayer transient pause failed")
            return
        }
        resumeOnAudioFocusGain = true
        _state.value =
            _state.value.copy(
                isPlaying = false,
                positionMs = runCatching { mp.currentPosition }.getOrDefault(_state.value.positionMs),
            )
        stopTicker()
    }

    private fun duckForTransientAudioFocusLoss() {
        val mp = player ?: return
        val wasPlaying =
            runCatching { mp.isPlaying }.getOrElse {
                releaseAfterPlayerControlFailure("MediaPlayer state query failed while ducking")
                return
            }
        if (!wasPlaying) return
        if (runCatching { mp.setVolume(DUCK_VOLUME, DUCK_VOLUME) }.isSuccess) {
            duckedForAudioFocusLoss = true
        } else {
            pauseForTransientAudioFocusLoss()
        }
    }

    private fun restoreAfterAudioFocusGain() {
        val mp = player
        if (duckedForAudioFocusLoss) {
            val restoreFailure = mp?.runCatching { setVolume(1f, 1f) }?.exceptionOrNull()
            if (restoreFailure != null) {
                releaseAfterPlayerControlFailure("MediaPlayer volume restore failed")
                return
            }
            duckedForAudioFocusLoss = false
        }
        if (!resumeOnAudioFocusGain) return
        resumeOnAudioFocusGain = false
        if (mp == null || !startCurrentPlayer(mp)) return
        _state.value =
            _state.value.copy(
                isPlaying = true,
                durationMs = runCatching { mp.duration }.getOrDefault(_state.value.durationMs),
            )
        startTicker()
    }

    private fun clearAudioFocusInterruption(restoreVolume: Boolean) {
        if (restoreVolume && duckedForAudioFocusLoss) {
            val restoreFailure = player?.runCatching { setVolume(1f, 1f) }?.exceptionOrNull()
            if (restoreFailure != null) {
                releaseAfterPlayerControlFailure("MediaPlayer volume restore failed")
                return
            }
        }
        resumeOnAudioFocusGain = false
        duckedForAudioFocusLoss = false
    }

    private fun releaseAfterPlayerControlFailure(message: String) {
        Log.w(TAG, message)
        releasePlayerInternal()
        _state.value = PlaybackState()
    }

    /** Pause the active player (no-op if nothing is active). */
    fun pause() {
        nextPlaybackGeneration()
        clearAudioFocusInterruption(restoreVolume = true)
        val mp =
            player ?: run {
                _state.value = _state.value.copy(isPlaying = false)
                stopTicker()
                abandonFocus()
                return
            }
        val wasPlaying =
            runCatching { mp.isPlaying }.getOrElse {
                releaseAfterPlayerControlFailure("MediaPlayer state query failed while pausing")
                return
            }
        if (wasPlaying) {
            val pauseFailure = runCatching { mp.pause() }.exceptionOrNull()
            if (pauseFailure != null) {
                releaseAfterPlayerControlFailure("MediaPlayer pause failed")
                return
            }
        }
        _state.value =
            _state.value.copy(
                isPlaying = false,
                positionMs = runCatching { mp.currentPosition }.getOrDefault(_state.value.positionMs),
            )
        stopTicker()
        // Release focus while user-paused so other apps stop being ducked for
        // the (potentially indefinite) pause. A transient system pause uses a
        // separate path and deliberately retains focus for the paired gain.
        abandonFocus()
    }

    /** Seek the active player to [positionMs] (clamped to duration). */
    fun seekTo(
        key: String,
        positionMs: Int,
    ) {
        val mp = player ?: return
        if (currentKey != key) return
        // Read mp.duration once, inside a guard: if the player has been driven
        // into the Error state but not yet nulled (OnError/OnCompletion post
        // releasePlayerInternal() to Main, leaving a window where player != null
        // and the player is in Error), getDuration() throws IllegalStateException.
        // Fall back to the last known duration so a seek can never crash the UI.
        val duration = runCatching { mp.duration }.getOrDefault(_state.value.durationMs)
        val clamped = positionMs.coerceIn(0, duration.coerceAtLeast(0))
        runCatching { mp.seekTo(clamped) }
        _state.value =
            _state.value.copy(
                positionMs = clamped,
                durationMs = if (duration > 0) duration else _state.value.durationMs,
            )
    }

    /** Stop and release the active player. */
    fun stop() {
        nextPlaybackGeneration()
        releasePlayerInternal()
        _state.value = PlaybackState()
    }

    /** Invalidates older player preparation and returns the new request token. */
    private fun nextPlaybackGeneration(): Long = playbackRequests.advance()

    private fun releasePlayerInternal() {
        stopTicker()
        clearAudioFocusInterruption(restoreVolume = false)
        player?.let { mp ->
            runCatching { if (mp.isPlaying) mp.stop() }
            runCatching { mp.release() }
        }
        player = null
        currentKey = null
        currentOwnerKey = null
        abandonFocus()
    }

    private fun startTicker() {
        stopTicker()
        tickerJob =
            scope.launch {
                while (true) {
                    val mp = player ?: break
                    if (!mp.isPlaying) break
                    // Only positionMs advances per tick. durationMs is constant
                    // for a clip and was set (with the >0 guard) at start(); re-reading
                    // mp.duration each tick is a needless JNI call and a non-positive
                    // mid-stream report would clobber the cached value (#275 family, #470).
                    _state.value =
                        _state.value.copy(
                            isPlaying = true,
                            positionMs = mp.currentPosition,
                        )
                    delay(TICK_INTERVAL_MS)
                }
            }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }
}
