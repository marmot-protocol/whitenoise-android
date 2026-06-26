package dev.ipf.whitenoise.android.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

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

    // play() suspends while MediaPlayer prepares on IO; serialize callers so
    // only one prepared player can ever reach start()/assignment.
    private val playSerializer = VoicePlaybackRequestSerializer()

    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private val focusListener =
        AudioManager.OnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                -> pause()
            }
        }

    /** Call once from Application.onCreate so playback can request audio focus. */
    fun attach(context: Context) {
        audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
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
        }.onFailure { Log.w(TAG, "setSpeed($currentSpeed) failed", it) }
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
    ) {
        playSerializer.withSerializedPlayback { playLocked(key, file, ownerKey) }
    }

    private suspend fun playLocked(
        key: String,
        file: File,
        ownerKey: String?,
    ) {
        if (currentKey == key && player != null) {
            // Re-acquire focus before resuming: a transient focus loss
            // auto-paused us (focusListener → pause()) and abandoned focus,
            // so another app may now own it. Restarting without re-requesting
            // would let two streams play at once or get our start() clobbered.
            if (!requestFocus()) {
                // Focus denied — stay paused rather than playing unfocused.
                return
            }
            player?.start()
            currentOwnerKey = ownerKey
            _state.value =
                _state.value.copy(
                    key = key,
                    isPlaying = true,
                    durationMs = player?.duration ?: _state.value.durationMs,
                )
            startTicker()
            return
        }
        releasePlayerInternal()
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
                }.onFailure { Log.w(TAG, "MediaPlayer prepare failed", it) }
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
                return
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
                    releasePlayerInternal()
                    _state.value = PlaybackState()
                }
            }
            true
        }
        if (!requestFocus()) {
            mp.runCatching { release() }
            _state.value = PlaybackState()
            return
        }
        mp.start()
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
    }

    private fun requestFocus(): Boolean {
        val am = audioManager ?: return true
        val attrs =
            AudioAttributes
                .Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build()
        val req =
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(focusListener)
                .build()
        focusRequest = req
        return am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        val am = audioManager ?: return
        focusRequest?.let { am.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    /** Pause the active player (no-op if nothing is active). */
    fun pause() {
        val mp = player ?: return
        if (runCatching { mp.isPlaying }.getOrDefault(false)) {
            mp.pause()
            _state.value =
                _state.value.copy(
                    isPlaying = false,
                    positionMs = mp.currentPosition,
                )
            // Release focus while paused so other apps stop being ducked for
            // the (potentially indefinite) pause. Resuming re-requests focus
            // in playLocked(). Safe no-op if focus is not currently held.
            abandonFocus()
        }
        stopTicker()
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
        releasePlayerInternal()
        _state.value = PlaybackState()
    }

    private fun releasePlayerInternal() {
        stopTicker()
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
