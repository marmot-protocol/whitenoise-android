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
            listener =
                object : RawAvPlaybackEngine.Listener {
                    override fun onChanged() {
                        if (!released) invalidateState()
                    }
                },
        )

    override fun getState(): State {
        val current = engine.snapshot()
        val tracks = tracks(current)
        val durationUs = current.durationMs.takeIf { it > 0 }?.times(1000) ?: C.TIME_UNSET
        val firstFrame = current.renderedFrame > reportedFrame
        reportedFrame = current.renderedFrame
        return State
            .Builder()
            .setAvailableCommands(commands)
            .setPlayWhenReady(current.playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(
                when (current.status) {
                    RawAvPlaybackEngine.Status.Idle -> Player.STATE_IDLE
                    RawAvPlaybackEngine.Status.Buffering -> Player.STATE_BUFFERING
                    RawAvPlaybackEngine.Status.Ready -> Player.STATE_READY
                    RawAvPlaybackEngine.Status.Ended -> Player.STATE_ENDED
                },
            ).setIsLoading(current.status == RawAvPlaybackEngine.Status.Buffering)
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

    override fun handlePrepare(): ListenableFuture<*> = engine.prepare()

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        engine.setPlaying(playWhenReady)
        return done()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> = engine.seekToAsync(positionMs)

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        require(playbackParameters.pitch == 1f)
        if (engine.setSpeed(playbackParameters.speed)) parameters = playbackParameters
        return done()
    }

    override fun handleSetVolume(volume: Float): ListenableFuture<*> {
        if (engine.setVolume(volume) && volume > 0) unmuteVolumeValue = volume
        return done()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        require(repeatMode in Player.REPEAT_MODE_OFF..Player.REPEAT_MODE_ALL)
        repeatModeValue = repeatMode
        engine.setRepeat(repeatMode != Player.REPEAT_MODE_OFF)
        return done()
    }

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

    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> {
        engine.attachVideo(videoOutput)
        return done()
    }

    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> {
        engine.clearVideo(videoOutput)
        return done()
    }

    override fun handleStop(): ListenableFuture<*> {
        engine.stop()
        return done()
    }

    override fun handleRelease(): ListenableFuture<*> {
        released = true
        engine.close()
        return done()
    }

    fun setHandleAudioBecomingNoisy(enabled: Boolean) = engine.setHandleAudioBecomingNoisy(enabled)

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

    private fun playbackError(failure: IOException): PlaybackException {
        var cause: Throwable? = failure
        repeat(8) {
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
