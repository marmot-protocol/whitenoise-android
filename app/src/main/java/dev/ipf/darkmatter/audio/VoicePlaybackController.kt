package dev.ipf.darkmatter.audio

import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
    private val durationCache = mutableMapOf<String, Int>()

    private var player: MediaPlayer? = null
    private var currentKey: String? = null
    private var tickerJob: Job? = null
    private var currentSpeed: Float = 1f

    /**
     * Fires once when MediaPlayer reports completion, with the key of the
     * clip that just finished. Conversation screen uses it to chain into
     * the next voice attachment.
     */
    var onCompletion: ((completedKey: String) -> Unit)? = null

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
        durationCache[path]?.let { return it }
        val probed =
            withContext(Dispatchers.IO) {
                runCatching {
                    MediaMetadataRetriever().use { mmr ->
                        mmr.setDataSource(path)
                        mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0
                    }
                }.getOrDefault(0)
            }
        durationCache[path] = probed
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
    ) {
        if (currentKey == key && player != null) {
            player?.start()
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
                }.onFailure { Log.w(TAG, "MediaPlayer prepare failed for ${file.name}", it) }
                    .getOrNull()
            } ?: run {
                _state.value = PlaybackState()
                return
            }
        mp.setOnCompletionListener {
            val completed = currentKey
            releasePlayerInternal()
            _state.value = PlaybackState()
            if (completed != null) onCompletion?.invoke(completed)
        }
        mp.setOnErrorListener { _, what, extra ->
            Log.w(TAG, "MediaPlayer error what=$what extra=$extra")
            releasePlayerInternal()
            _state.value = PlaybackState()
            true
        }
        mp.start()
        player = mp
        currentKey = key
        durationCache[file.absolutePath] = mp.duration
        applySpeedToActive()
        _state.value =
            PlaybackState(
                key = key,
                isPlaying = true,
                positionMs = 0,
                durationMs = mp.duration,
                speed = currentSpeed,
            )
        startTicker()
    }

    /** Pause the active player (no-op if nothing is active). */
    fun pause() {
        val mp = player ?: return
        if (mp.isPlaying) {
            mp.pause()
            _state.value =
                _state.value.copy(
                    isPlaying = false,
                    positionMs = mp.currentPosition,
                )
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
        val clamped = positionMs.coerceIn(0, mp.duration.coerceAtLeast(0))
        runCatching { mp.seekTo(clamped) }
        _state.value =
            _state.value.copy(
                positionMs = clamped,
                durationMs = if (mp.duration > 0) mp.duration else _state.value.durationMs,
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
    }

    private fun startTicker() {
        stopTicker()
        tickerJob =
            scope.launch {
                while (true) {
                    val mp = player ?: break
                    if (!mp.isPlaying) break
                    _state.value =
                        _state.value.copy(
                            isPlaying = true,
                            positionMs = mp.currentPosition,
                            durationMs = mp.duration,
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
