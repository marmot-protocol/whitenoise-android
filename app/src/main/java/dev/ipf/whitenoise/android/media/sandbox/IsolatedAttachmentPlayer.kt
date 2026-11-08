@file:Suppress("MagicNumber") // Fixed PCM units, renderer clocks and Player protocol constants.

package dev.ipf.whitenoise.android.media.sandbox

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.io.FileNotFoundException
import java.io.IOException

/** Existing PlayerView and transport controls consume the same Player API, backed by raw pixels/PCM. */
@androidx.annotation.OptIn(UnstableApi::class)
@Suppress("TooManyFunctions") // Thin implementations of the existing Player control contract.
internal class IsolatedAttachmentPlayer(
    context: Context,
    uri: Uri,
    observation: RawAvPlaybackObservation? = null,
) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val itemUid = Any()
    private val mediaItem = MediaItem.fromUri(uri)
    private var attributes = AudioAttributes.DEFAULT
    private var parameters = PlaybackParameters.DEFAULT
    private var repeatModeValue = Player.REPEAT_MODE_OFF
    private var unmuteVolumeValue = 1f
    private var released = false
    private var reportedFrame = 0L
    private val engine =
        RawAvPlaybackEngine(
            context,
            uri,
            observation = observation,
            listener =
                object : RawAvPlaybackEngine.Listener {
                    /** Publishes engine changes on the player contract while refusing callbacks after release. */
                    override fun onChanged() {
                        if (!released) invalidateState()
                    }
                },
        )

    /** Samples the engine-owned AudioTrack from the bounded instrumentation driver, outside Main. */
    internal fun observeAudio() = engine.observeAudio()

    /** Projects the engine snapshot into Player state without parsing the selected compressed source. */
    override fun getState(): State {
        val current = engine.snapshot()
        val tracks = tracks(current)
        val durationUs = current.durationMs.takeIf { it > 0 }?.times(1000) ?: C.TIME_UNSET
        val firstFrame = current.renderedFrame > reportedFrame
        reportedFrame = current.renderedFrame
        return State
            .Builder()
            .setAvailableCommands(commands)
            .setPlayWhenReady(current.playWhenReady, current.playWhenReadyChangeReason)
            .setPlaybackState(
                // Media3 accepts a player error only in STATE_IDLE: an engine that failed while buffering is idle.
                if (current.error != null) {
                    Player.STATE_IDLE
                } else {
                    when (current.status) {
                        RawAvPlaybackEngine.Status.Idle -> Player.STATE_IDLE
                        RawAvPlaybackEngine.Status.Buffering -> Player.STATE_BUFFERING
                        RawAvPlaybackEngine.Status.Ready -> Player.STATE_READY
                        RawAvPlaybackEngine.Status.Ended -> Player.STATE_ENDED
                    }
                },
            ).setIsLoading(current.error == null && current.status == RawAvPlaybackEngine.Status.Buffering)
            .setPlaylist(
                listOf(
                    MediaItemData
                        .Builder(itemUid)
                        .setMediaItem(mediaItem)
                        .setTracks(tracks)
                        .setDurationUs(durationUs)
                        .setIsSeekable(true)
                        .build(),
                ),
            ).setCurrentMediaItemIndex(0)
            .setContentPositionMs(PositionSupplier { engine.positionMs() })
            .setContentBufferedPositionMs(PositionSupplier { engine.positionMs() })
            .setTotalBufferedDurationMs(PositionSupplier { 0 })
            .setSeekBackIncrementMs(C.DEFAULT_SEEK_BACK_INCREMENT_MS)
            .setSeekForwardIncrementMs(C.DEFAULT_SEEK_FORWARD_INCREMENT_MS)
            .setAudioAttributes(attributes)
            .setVolume(current.volume)
            .setUnmuteVolume(unmuteVolumeValue)
            .setPlaybackParameters(parameters)
            .setRepeatMode(repeatModeValue)
            .setVideoSize(VideoSize(current.videoWidth, current.videoHeight))
            .setNewlyRenderedFirstFrame(firstFrame)
            .setPlayerError(current.error?.let(::playbackError))
            .build()
    }

    /** Delegates preparation to the single raw playback owner and returns its asynchronous completion. */
    override fun handlePrepare(): ListenableFuture<*> = engine.prepare()

    /** Updates requested playback through the engine so focus, pause and lifetime ownership stay centralized. */
    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        engine.setPlaying(playWhenReady)
        return done()
    }

    /** Queues the target media position through the retained raw session rather than reopening the URI. */
    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> = engine.seekToAsync(positionMs)

    /** Applies bounded supported speed parameters through the existing raw PCM timing owner. */
    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        val speed = playbackParameters.speed
        if (
            playbackParameters.pitch == 1f &&
            speed.isFinite() &&
            speed in RawPcmCapacity.MIN_SPEED..RawPcmCapacity.MAX_SPEED
        ) {
            if (engine.setSpeed(speed)) parameters = playbackParameters
        }
        return done()
    }

    /** Keeps the Player volume projection and parent PCM gain synchronized. */
    override fun handleSetVolume(volume: Float): ListenableFuture<*> {
        if (engine.setVolume(volume) && volume > 0) unmuteVolumeValue = volume
        return done()
    }

    /** Accepts the supported repeat contract and updates the existing engine owner. */
    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        require(repeatMode in Player.REPEAT_MODE_OFF..Player.REPEAT_MODE_ALL)
        repeatModeValue = repeatMode
        engine.setRepeat(repeatMode != Player.REPEAT_MODE_OFF)
        return done()
    }

    /** Routes platform audio policy through the engine instead of exposing presentation to the worker. */
    override fun handleSetAudioAttributes(
        audioAttributes: AudioAttributes,
        handleAudioFocus: Boolean,
    ): ListenableFuture<*> {
        attributes = audioAttributes
        engine.setAttributes(
            android.media.AudioAttributes
                .Builder()
                .setUsage(audioAttributes.usage)
                .setContentType(audioAttributes.contentType)
                .setFlags(audioAttributes.flags)
                .build(),
        )
        engine.setFocusHandling(handleAudioFocus)
        return done()
    }

    /** Attaches the parent-owned presentation output; no Surface capability crosses worker IPC. */
    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> {
        engine.attachVideo(videoOutput)
        return done()
    }

    /** Clears only the matching parent presentation output so late removals cannot detach its replacement. */
    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> {
        engine.clearVideo(videoOutput)
        return done()
    }

    /** Stops playback through the existing owner while retaining the selected media position contract. */
    override fun handleStop(): ListenableFuture<*> {
        engine.stop()
        return done()
    }

    /** Revokes listener publication and releases the engine once through the Player lifecycle. */
    override fun handleRelease(): ListenableFuture<*> {
        released = true
        engine.close()
        return done()
    }

    /** Routes noisy-output policy to the parent playback owner and its receiver lifetime. */
    fun setHandleAudioBecomingNoisy(enabled: Boolean) = engine.setHandleAudioBecomingNoisy(enabled)

    /** Builds presentation-only track descriptions from validated raw metadata. */
    private fun tracks(state: RawAvPlaybackEngine.Snapshot): Tracks {
        val formats = ArrayList<Format>()
        if (state.videoWidth > 0 && state.videoHeight > 0) {
            formats +=
                Format
                    .Builder()
                    .setSampleMimeType(MimeTypes.VIDEO_RAW)
                    .setWidth(state.videoWidth)
                    .setHeight(state.videoHeight)
                    .build()
        }
        if (state.audioRate > 0 && state.audioChannels > 0) {
            formats +=
                Format
                    .Builder()
                    .setSampleMimeType(MimeTypes.AUDIO_RAW)
                    .setSampleRate(state.audioRate)
                    .setChannelCount(state.audioChannels)
                    .setPcmEncoding(C.ENCODING_PCM_16BIT)
                    .build()
        }
        return Tracks(
            formats.map { Tracks.Group(TrackGroup(it), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true)) },
        )
    }

    /** Maps only attributable missing-file or exact container refusals to cache-invalidating Player codes. */
    @Suppress("ReturnCount") // Preserve distinct attributed refusals and the bounded cause-chain fallback.
    private fun playbackError(failure: IOException): PlaybackException {
        var cause: Throwable? = failure
        repeat(8) {
            if (cause is RawContainerFailure) {
                return PlaybackException(
                    "Selected attachment container is malformed",
                    failure,
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                )
            }
            if (cause is FileNotFoundException) {
                return PlaybackException(
                    "Selected attachment file unavailable",
                    failure,
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                )
            }
            cause = cause?.cause
        }
        return PlaybackException(
            "Isolated attachment playback failed",
            failure,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
        )
    }

    /** Acknowledges synchronous presentation-only changes with an already completed Player future. */
    private fun done(): ListenableFuture<Void> = SettableFuture.create<Void>().apply { set(null) }

    companion object {
        private val commands =
            Player.Commands
                .Builder()
                .addAllReadOnlyCommands()
                .addAll(
                    Player.COMMAND_PLAY_PAUSE,
                    Player.COMMAND_PREPARE,
                    Player.COMMAND_STOP,
                    Player.COMMAND_RELEASE,
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                    Player.COMMAND_SEEK_BACK,
                    Player.COMMAND_SEEK_FORWARD,
                    Player.COMMAND_SET_SPEED_AND_PITCH,
                    Player.COMMAND_SET_REPEAT_MODE,
                    Player.COMMAND_SET_VOLUME,
                    Player.COMMAND_SET_AUDIO_ATTRIBUTES,
                    Player.COMMAND_SET_VIDEO_SURFACE,
                ).build()
    }
}
