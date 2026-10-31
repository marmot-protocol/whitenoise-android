@file:Suppress("MagicNumber") // Fixed PCM units, renderer clocks and Player protocol constants.

package dev.ipf.whitenoise.android.media.sandbox

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.delay
import java.io.IOException

internal data class RawAvPlaybackIntent(
    val alive: Boolean,
    val playing: Boolean,
    val activation: Long,
    val seek: Long,
    val positionUs: Long,
    val speed: Float,
    val gain: Float,
    val attributes: AudioAttributes,
)

internal interface RawAvRenderOwner {
    fun intent(): RawAvPlaybackIntent

    fun awaitPrepared()

    fun opened(
        info: AvStreamInfo,
        activation: Long,
    )

    fun seeked(
        activation: Long,
        seek: Long,
    )

    fun frame(
        bitmap: Bitmap,
        rotation: Int,
        activation: Long,
        seek: Long,
    ): Boolean

    fun ended(
        activation: Long,
        seek: Long,
        endUs: Long,
    )

    fun failed(
        activation: Long,
        seek: Long?,
        failure: IOException,
    )
}

/** One persistent caller drives this finite raw-output session, with no queued decode tasks. */
@Suppress("TooManyFunctions") // Clock, raw sinks and input ownership belong to one renderer session.
internal class RawAvRenderSession(
    private val context: Context,
    private val uri: Uri,
    private val wantVideo: Boolean,
    private val wantAudio: Boolean,
    private val owner: RawAvRenderOwner,
    private val pcmFactory: (Int, Int, AudioAttributes) -> RawPcmSink = ::RawPcmOutput,
) {
    private val clockLock = Any()
    private var audioAnchorUs = 0L
    private var wallAnchorUs = 0L
    private var wallStartedMs = 0L
    private var clockSpeed = 1f
    private var sourceHasAudio = false
    private var outputGain = 1f
    private var cancelledRun = -1L
    private var operationSeek: Long? = null
    private var eofWallClock = false

    @Volatile private var stream: IsolatedAvStream? = null

    @Volatile private var pcm: RawPcmSink? = null

    @Volatile var activeRun = -1L
        private set

    @Volatile var appliedSeek = -1L
        private set

    fun positionUs(): Long =
        synchronized(clockLock) {
            if (sourceHasAudio && !eofWallClock) {
                audioAnchorUs + (pcm?.positionUs() ?: 0)
            } else {
                RawAvTiming.wallPosition(wallAnchorUs, wallStartedMs, SystemClock.elapsedRealtime(), clockSpeed)
            }
        }

    @Suppress("TooGenericExceptionCaught") // Retirement closes the exact sink even when the device rejects pause.
    fun pause(): RawOutputFailure? =
        try {
            synchronized(clockLock) {
                cancelledRun = activeRun
                val selected = pcm
                pcm = null
                try {
                    selected?.pause()
                    null
                } catch (failure: Exception) {
                    RawOutputFailure("Raw audio pause refused", failure)
                } finally {
                    selected?.let { runCatching { it.close() } }
                }
            }
        } finally {
            stream?.close()
        }

    fun volume(value: Float): RawOutputFailure? =
        applyControl("volume") {
            pcm?.volume(value)
            outputGain = value
        }

    fun speed(value: Float): RawOutputFailure? =
        applyControl("speed") {
            pcm?.speed(value)
            wallAnchorUs = positionUs()
            wallStartedMs = SystemClock.elapsedRealtime()
            clockSpeed = value
        }

    @Suppress("TooGenericExceptionCaught") // A validated device control may still be refused by AudioTrack.
    private fun applyControl(name: String, action: () -> Unit): RawOutputFailure? =
        try {
            synchronized(clockLock) { action() }
            null
        } catch (failure: Exception) {
            RawOutputFailure("Raw audio $name refused", failure)
        }

    // A raw renderer failure retires the exact stream without invalidating source bytes.
    @Suppress("TooGenericExceptionCaught")
    suspend fun run(activation: Long) {
        var selected: IsolatedAvStream? = null
        try {
            operationSeek = null
            owner.awaitPrepared()
            if (!valid(activation)) return
            selected = IsolatedAvStream.openUri(context, uri, wantVideo, wantAudio)
            stream = selected
            activeRun = activation
            val info = selected.info
            synchronized(clockLock) { sourceHasAudio = wantAudio && info.hasAudio }
            owner.opened(info, activation)
            consume(selected, info, activation)
        } catch (failure: Exception) {
            if (valid(
                    activation,
                    operationSeek,
                )
            ) {
                owner.failed(activation, operationSeek, rawPlaybackFailure(failure))
            }
        } catch (failure: OutOfMemoryError) {
            if (valid(
                    activation,
                    operationSeek,
                )
            ) {
                owner.failed(activation, operationSeek, IOException("Raw playback output memory unavailable", failure))
            }
        } finally {
            selected?.close()
            if (stream === selected) stream = null
            pcm?.close()
            pcm = null
            activeRun = -1L
            appliedSeek = -1L
        }
    }

    private class Progress(
        var targetUs: Long,
    ) {
        var lastVideoPts = -1L
        var lastVideoDelta = 33_333L
        var audioEndUs = targetUs
    }

    private suspend fun consume(
        selected: IsolatedAvStream,
        info: AvStreamInfo,
        activation: Long,
    ) {
        val progress = Progress(owner.intent().positionUs)
        while (valid(activation)) {
            val intent = owner.intent()
            if (appliedSeek != intent.seek) {
                progress.targetUs = intent.positionUs
                applySeek(selected, intent)
                progress.lastVideoPts = -1L
                progress.audioEndUs = progress.targetUs
            }
            operationSeek = appliedSeek
            val frame = selected.read()
            if (!valid(activation, appliedSeek)) {
                (frame as? AvStreamFrame.Video)?.bitmap?.recycle()
                continue
            }
            when (frame) {
                is AvStreamFrame.Video -> consumeVideo(frame, info.rotationDegrees, activation, progress)
                is AvStreamFrame.Audio -> consumeAudioFrame(frame, activation, progress)
                AvStreamFrame.End -> finishFrames(info, activation, progress)
            }
        }
    }

    private suspend fun consumeVideo(
        frame: AvStreamFrame.Video,
        rotation: Int,
        activation: Long,
        progress: Progress,
    ) {
        if (frame.timestampUs < progress.targetUs || !waitForVideo(frame.timestampUs, activation)) {
            frame.bitmap.recycle()
            return
        }
        if (progress.lastVideoPts >= 0 && frame.timestampUs > progress.lastVideoPts) {
            progress.lastVideoDelta = frame.timestampUs - progress.lastVideoPts
        }
        progress.lastVideoPts = frame.timestampUs
        if (!owner.frame(frame.bitmap, rotation, activation, appliedSeek)) frame.bitmap.recycle()
    }

    private suspend fun consumeAudioFrame(
        frame: AvStreamFrame.Audio,
        activation: Long,
        progress: Progress,
    ) {
        val clipped =
            RawAvTiming.clipPcm(
                frame.bytes.size,
                frame.sampleRate,
                frame.channels,
                frame.timestampUs,
                progress.targetUs,
            )
        if (clipped.byteCount == 0) return
        consumeAudio(frame, clipped, activation)
        progress.audioEndUs =
            maxOf(
                progress.audioEndUs,
                RawAvTiming.pcmEnd(clipped.timestampUs, clipped.byteCount, frame.sampleRate, frame.channels),
            )
    }

    private suspend fun finishFrames(
        info: AvStreamInfo,
        activation: Long,
        progress: Progress,
    ) {
        val videoEnd = (progress.lastVideoPts + progress.lastVideoDelta.coerceIn(1000, 1_000_000)).coerceAtLeast(0)
        val observed = maxOf(progress.audioEndUs, videoEnd)
        val endUs = info.durationUs.takeIf { it > 0 } ?: observed
        drain(endUs, activation)
        if (valid(activation, appliedSeek)) owner.ended(activation, appliedSeek, endUs)
    }

    private fun applySeek(
        selected: IsolatedAvStream,
        intent: RawAvPlaybackIntent,
    ) = applySeek(intent, selected::seek)

    internal fun applySeek(
        intent: RawAvPlaybackIntent,
        seek: (Long) -> Unit,
    ) {
        operationSeek = intent.seek
        seek(intent.positionUs)
        val previous = synchronized(clockLock) { pcm.also { pcm = null } }
        previous?.close()
        synchronized(clockLock) {
            audioAnchorUs = intent.positionUs
            wallAnchorUs = intent.positionUs
            wallStartedMs = SystemClock.elapsedRealtime()
            // Accepted controls survive a blocked seek; never restore its older intent snapshot.
            eofWallClock = false
        }
        appliedSeek = intent.seek
        owner.seeked(intent.activation, intent.seek)
    }

    private suspend fun consumeAudio(
        frame: AvStreamFrame.Audio,
        clipped: RawAvTiming.PcmSlice,
        activation: Long,
    ) {
        val sink = pcm ?: createPcm(frame, owner.intent().positionUs, activation) ?: return
        if (sink.sampleRate != frame.sampleRate ||
            sink.channels != frame.channels
        ) {
            throw IOException("Raw audio layout changed")
        }
        val expected = synchronized(clockLock) { audioAnchorUs } + sink.writtenPositionUs()
        if (clipped.timestampUs > expected + 1000) writeSilence(sink, clipped.timestampUs - expected, activation)
        writePcm(sink, frame.bytes, clipped.offset, activation)
    }

    @Suppress("TooGenericExceptionCaught") // A refused new device output must close before it can be adopted.
    internal fun createPcm(
        frame: AvStreamFrame.Audio,
        anchorUs: Long,
        activation: Long,
    ): RawPcmSink? {
        val attributes = owner.intent().attributes
        var created: RawPcmSink? = null
        var adopted = false
        try {
            val output = pcmFactory(frame.sampleRate, frame.channels, attributes)
            created = output
            synchronized(clockLock) {
                if (cancelledRun == activation) return null
                output.speed(clockSpeed)
                output.volume(outputGain)
                output.play()
                audioAnchorUs = anchorUs
                pcm = output
                adopted = true
            }
            return output
        } catch (failure: Exception) {
            throw RawOutputFailure("Raw audio initialization refused", failure)
        } finally {
            if (!adopted) created?.let { runCatching { it.close() } }
        }
    }

    private fun valid(
        activation: Long,
        seek: Long? = null,
    ): Boolean =
        owner.intent().let {
            RawAvGeneration(activation, seek ?: 0).accepts(
                RawAvGeneration(it.activation, it.seek),
                it.alive,
                it.playing,
                seek != null,
            )
        }

    private suspend fun waitForVideo(
        timestampUs: Long,
        activation: Long,
    ): Boolean {
        while (valid(activation, appliedSeek)) {
            val clock = positionUs()
            if (RawAvTiming.videoIsDue(timestampUs, clock)) return true
            delay(((timestampUs - clock) / 1000).coerceIn(1, 10))
        }
        return false
    }

    private suspend fun writePcm(
        sink: RawPcmSink,
        bytes: ByteArray,
        offset: Int,
        activation: Long,
    ) {
        var position = offset
        while (position < bytes.size && valid(activation, appliedSeek)) {
            val written = sink.write(bytes, position)
            position += written
            if (written == 0) delay(5)
        }
    }

    private suspend fun writeSilence(
        sink: RawPcmSink,
        durationUs: Long,
        activation: Long,
    ) {
        var remaining = durationUs * sink.sampleRate / 1_000_000
        while (remaining > 0 && valid(activation, appliedSeek)) {
            val frames = minOf(remaining, (sink.sampleRate / 20).toLong()).toInt()
            writePcm(sink, ByteArray(frames * sink.channels * 2), 0, activation)
            remaining -= frames
        }
    }

    private suspend fun drain(
        endUs: Long,
        activation: Long,
    ) {
        while (pcm?.drained() == false && valid(activation, appliedSeek)) delay(5)
        if (sourceHasAudio) {
            synchronized(clockLock) {
                wallAnchorUs = positionUs()
                wallStartedMs = SystemClock.elapsedRealtime()
                eofWallClock = true
            }
        }
        while (!RawAvTiming.videoIsDue(endUs, positionUs()) && valid(activation, appliedSeek)) delay(5)
    }
}
