@file:Suppress("MagicNumber") // Fixed PCM units, renderer clocks and Player protocol constants.

package dev.ipf.whitenoise.android.media.sandbox

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.delay
import java.io.IOException
import kotlin.math.ceil

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
    /**
     * Snapshots current source/control generations and presentation settings for the renderer.
     */
    fun intent(): RawAvPlaybackIntent

    /**
     * Waits for admitted preparation before the renderer may open its streaming source.
     */
    fun awaitPrepared()

    /**
     * Offers stream metadata for one activation; the owner must reject retired activations.
     */
    fun opened(
        info: AvStreamInfo,
        activation: Long,
    )

    /**
     * Acknowledges the exact activation/seek pair after its stream seek and clock reset.
     */
    fun seeked(
        activation: Long,
        seek: Long,
    )

    /**
     * Offers ownership of one raw bitmap for the exact activation/seek pair.
     * True adopts the bitmap; false leaves recycling to the renderer.
     */
    fun frame(
        bitmap: Bitmap,
        rotation: Int,
        activation: Long,
        seek: Long,
    ): Boolean

    /** Carries frame PTS without changing existing test owners' four-argument ownership contract. */
    fun frame(
        bitmap: Bitmap,
        rotation: Int,
        activation: Long,
        seek: Long,
        timestampUs: Long,
    ): Boolean = frame(bitmap, rotation, activation, seek)

    /**
     * Offers terminal position in microseconds for the exact activation/seek pair.
     */
    fun ended(
        activation: Long,
        seek: Long,
        endUs: Long,
    )

    /**
     * Offers a typed failure for one activation and, when known, its seek generation.
     */
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
    /** Set once by the engine before its worker starts; null leaves ordinary playback diagnostics disabled. */
    var observation: RawAvPlaybackObservation? = null

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

    // The paused worker of this exact source; only run() parks it and only run()/close() take it.
    private var parked: IsolatedAvStream? = null
    private var closed = false
    private var startedRun = -1L
    private var refusedThrough = -1L

    @Volatile private var pcm: RawPcmSink? = null

    @Volatile var activeRun = -1L
        private set

    @Volatile var appliedSeek = -1L
        private set

    /** Samples only the currently owned real PCM sink, without exposing the device through UI getters. */
    fun observeAudio(observation: RawAvPlaybackObservation) {
        synchronized(clockLock) { (pcm as? RawPcmOutput)?.observeAudio(observation, activeRun, appliedSeek) }
    }

    /**
     * Reads media time in microseconds under the clock lock.
     * Uses played PCM while audio is active and the elapsed-time anchor for silent or drained audio.
     */
    fun positionUs(): Long =
        synchronized(clockLock) {
            if (sourceHasAudio && !eofWallClock) {
                audioAnchorUs + (pcm?.positionUs() ?: 0)
            } else {
                RawAvTiming.wallPosition(wallAnchorUs, wallStartedMs, SystemClock.elapsedRealtime(), clockSpeed)
            }
        }

    /**
     * Retires the current run and detaches and closes its PCM sink. The run then parks its healthy
     * stream for a warm resume of this same source instead of starting a new worker.
     * Device pause refusal is returned as an output failure; sink cleanup is still attempted.
     */
    @Suppress("TooGenericExceptionCaught") // Retirement closes the exact sink even when the device rejects pause.
    fun pause(): RawOutputFailure? =
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

    /**
     * Pauses output, then closes the running and parked streams; a later run never parks again.
     */
    fun close(): RawOutputFailure? =
        try {
            pause()
        } finally {
            val retired =
                synchronized(clockLock) {
                    closed = true
                    parked.also { parked = null }
                }
            retired?.close()
            stream?.close()
        }

    /**
     * Retires the parked worker and refuses parking for every activation started so far: playback
     * failure or another source's activation must not keep this worker alive.
     */
    fun retireWorker() {
        synchronized(clockLock) {
            refusedThrough = maxOf(refusedThrough, startedRun)
            parked.also { parked = null }
        }?.close()
    }

    /** Takes the parked worker only if it still serves this owner's current account generation. */
    private fun takeParked(activation: Long): IsolatedAvStream? {
        val candidate =
            synchronized(clockLock) {
                startedRun = maxOf(startedRun, activation)
                parked.also { parked = null }
            }
        val claimed = candidate?.takeIf { it.unpark() }
        if (claimed == null) candidate?.close()
        return claimed
    }

    /** Parks one healthy stream, replacing none: run() is serial and takes the parked worker first. */
    private fun park(
        selected: IsolatedAvStream,
        activation: Long,
    ) {
        val accepted =
            synchronized(clockLock) {
                if (closed || parked != null || activation <= refusedThrough) {
                    false
                } else {
                    parked = selected
                    true
                }
            }
        if (accepted) selected.park(PARKED_IDLE_MS) else selected.close()
    }

    /**
     * Applies gain under the clock lock and retains it for future sinks; returns typed device refusal.
     */
    fun volume(value: Float): RawOutputFailure? =
        applyControl("volume") {
            pcm?.volume(value)
            outputGain = value
        }

    /**
     * Applies the rate under the clock lock and reanchors elapsed time without a position jump.
     */
    fun speed(value: Float): RawOutputFailure? =
        applyControl("speed") {
            pcm?.speed(value)
            wallAnchorUs = positionUs()
            wallStartedMs = SystemClock.elapsedRealtime()
            clockSpeed = value
        }

    /**
     * Serializes a sink/clock control and converts platform exceptions to output failures.
     */
    @Suppress("TooGenericExceptionCaught") // A validated device control may still be refused by AudioTrack.
    private fun applyControl(name: String, action: () -> Unit): RawOutputFailure? =
        try {
            synchronized(clockLock) { action() }
            null
        } catch (failure: Exception) {
            RawOutputFailure("Raw audio $name refused", failure)
        }

    // A raw renderer failure retires the exact stream without invalidating source bytes.

    /**
     * Owns one isolated stream and sink through cleanup for a single playback activation.
     * Only failures still matching live activation/seek intent are reported to the owner.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun run(activation: Long) {
        var selected: IsolatedAvStream? = null
        var reusable = false
        try {
            operationSeek = null
            owner.awaitPrepared()
            if (!valid(activation)) return
            // A resumed activation seeks the parked worker; the first applied seek below always runs.
            selected = takeParked(activation) ?: IsolatedAvStream.openUri(context, uri, wantVideo, wantAudio)
            observation?.let { selected?.observeReady(it, activation) }
            stream = selected
            activeRun = activation
            val info = selected.info
            synchronized(clockLock) { sourceHasAudio = wantAudio && info.hasAudio }
            owner.opened(info, activation)
            consumeFrames(info, activation, selected::read, selected::seek)
            reusable = true
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
            if (stream === selected) stream = null
            pcm?.close()
            pcm = null
            activeRun = -1L
            appliedSeek = -1L
            // Any failure retires the worker; a paused or ended run keeps it for this source only.
            selected?.let { if (reusable) park(it, activation) else it.close() }
        }
    }

    private class PendingPcm(
        val frame: AvStreamFrame.Audio,
        var offset: Int,
        val limit: Int,
        val timestampUs: Long,
        var gapFrames: Long,
        var silence: ByteArray? = null,
    ) {
        val initialOffset = offset
    }

    private class Progress(
        var targetUs: Long,
        var videoEnded: Boolean,
        var audioEnded: Boolean,
    ) {
        var lastVideoPts = -1L
        var lastVideoDelta = 33_333L
        var audioEndUs = targetUs
        var prerollAudioUs = Long.MIN_VALUE
        var video: AvStreamFrame.Video? = null
        var audio: PendingPcm? = null
    }

    /**
     * Parent-only finite reader seam; production passes the exact owned isolated stream methods.
     * Ownership/preroll retries and final EOF exit keep bitmap and PCM cleanup under this exact owner.
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth", "LoopWithTooManyJumpStatements")
    internal suspend fun consumeFrames(
        info: AvStreamInfo,
        activation: Long,
        read: (Int) -> AvStreamFrame,
        seek: (Long) -> Unit,
    ) {
        synchronized(clockLock) { sourceHasAudio = wantAudio && info.hasAudio }
        val progress = Progress(owner.intent().positionUs, !wantVideo || !info.hasVideo, !wantAudio || !info.hasAudio)
        try {
            while (valid(activation)) {
                val intent = owner.intent()
                if (appliedSeek != intent.seek) {
                    progress.video?.bitmap?.recycle()
                    progress.video = null
                    progress.audio = null
                    progress.targetUs = intent.positionUs
                    applySeek(intent, seek)
                    progress.videoEnded = !wantVideo || !info.hasVideo
                    progress.audioEnded = !wantAudio || !info.hasAudio
                    progress.lastVideoPts = -1
                    progress.audioEndUs = progress.targetUs
                    progress.prerollAudioUs = Long.MIN_VALUE
                }
                if (!valid(activation, appliedSeek)) continue
                operationSeek = appliedSeek
                if (progress.video == null && !progress.videoEnded) {
                    when (val frame = read(AvStreamProtocol.VIDEO_TRACK)) {
                        is AvStreamFrame.Video -> {
                            if (!valid(activation, appliedSeek)) {
                                frame.bitmap.recycle()
                            } else if (frame.timestampUs < progress.targetUs) {
                                frame.bitmap.recycle()
                                drainPrerollAudio(progress, frame.timestampUs, activation, read)
                            } else {
                                progress.video = frame
                            }
                        }
                        AvStreamFrame.VideoEnd -> progress.videoEnded = true
                        else -> throw IOException("Unexpected output from preferred video read")
                    }
                    if (progress.video == null && !progress.videoEnded) continue
                }
                val nextVideo = progress.video
                if (nextVideo != null) {
                    ensureAudioForVideo(progress, nextVideo.timestampUs, activation, read)
                    val reserveTargetUs = audioReserveTargetUs(nextVideo.timestampUs)
                    refillAudioReserve(progress, reserveTargetUs, activation, read)
                    if (!valid(activation, appliedSeek)) continue
                    // Before owner.frame is called, this coroutine alone owns and must recycle the bitmap on failure.
                    var ready = false
                    try {
                        ready = waitForVideo(nextVideo.timestampUs, activation, progress, reserveTargetUs, read)
                    } finally {
                        if (!ready) {
                            progress.video = null
                            nextVideo.bitmap.recycle()
                        }
                    }
                    if (ready) {
                        // Once invoked, the owner may retain the bitmap even if its callback throws.
                        progress.video = null
                        if (progress.lastVideoPts >= 0 && nextVideo.timestampUs > progress.lastVideoPts) {
                            progress.lastVideoDelta = nextVideo.timestampUs - progress.lastVideoPts
                        }
                        progress.lastVideoPts = nextVideo.timestampUs
                        val adopted =
                            owner.frame(
                                nextVideo.bitmap,
                                info.rotationDegrees,
                                activation,
                                appliedSeek,
                                nextVideo.timestampUs,
                            )
                        if (!adopted) nextVideo.bitmap.recycle()
                    }
                } else if (!progress.audioEnded || progress.audio != null) {
                    feedAudioStep(progress, activation, read)
                }
                if (progress.videoEnded && progress.audioEnded && progress.audio == null) {
                    finishFrames(info, activation, progress)
                    break
                }
            }
        } finally {
            progress.video?.bitmap?.recycle()
            progress.video = null
            progress.audio = null
        }
    }

    /** Discard only pre-target PCM; retain one crossing-target suffix through the existing clip/adoption owner. */
    private fun drainPrerollAudio(
        progress: Progress,
        discardedVideoUs: Long,
        activation: Long,
        read: (Int) -> AvStreamFrame,
    ) {
        while (needsPrerollAudio(progress, discardedVideoUs, activation)) {
            val frame = read(AvStreamProtocol.AUDIO_TRACK)
            if (!valid(activation, appliedSeek)) {
                if (frame is AvStreamFrame.Video) frame.bitmap.recycle()
                return
            }
            when (frame) {
                is AvStreamFrame.Audio -> {
                    progress.prerollAudioUs = maxOf(progress.prerollAudioUs, frame.timestampUs)
                    adoptPcm(frame, progress, activation)
                }
                AvStreamFrame.AudioEnd -> progress.audioEnded = true
                is AvStreamFrame.Video -> {
                    frame.bitmap.recycle()
                    throw IOException("Unexpected video from preroll audio read")
                }
                else -> throw IOException("Unexpected output from preroll audio read")
            }
        }
    }

    /**
     * Requires preroll audio only while its live generation has not reached the discarded video timestamp.
     */
    private fun needsPrerollAudio(
        progress: Progress,
        discardedVideoUs: Long,
        activation: Long,
    ): Boolean {
        if (!sourceHasAudio || !valid(activation, appliedSeek)) return false
        return !progress.audioEnded && progress.audio == null && progress.prerollAudioUs < discardedVideoUs
    }

    /**
     * Feeds audio until the buffered media horizon covers the next video timestamp or audio drains.
     * All timestamps and the 1 ms horizon tolerance are expressed in microseconds.
     */
    private suspend fun ensureAudioForVideo(
        progress: Progress,
        timestampUs: Long,
        activation: Long,
        read: (Int) -> AvStreamFrame,
    ) {
        while (sourceHasAudio && !eofWallClock && valid(activation, appliedSeek)) {
            pumpAudio(progress)
            if (eofWallClock) return
            val horizon = synchronized(clockLock) { audioAnchorUs + (pcm?.bufferedPositionUs() ?: 0) }
            if (timestampUs <= horizon + 1000) return
            feedAudioStep(progress, activation, read)
        }
    }

    /** Freezes one source-time reserve target; subsequent playback progress cannot move this target. */
    private fun audioReserveTargetUs(timestampUs: Long): Long =
        synchronized(clockLock) {
            val reserveUs = ceil(AUDIO_RESERVE_US * clockSpeed.toDouble()).toLong()
            (maxOf(timestampUs, positionUs()) + reserveUs).coerceAtMost(AvStreamProtocol.MAX_DURATION_US)
        }

    /**
     * Uses existing device capacity for a soft reserve, without waiting on backpressure or known audio EOF.
     * Written credits cap source admission while DSP output still waits to enter the actual device buffer.
     */
    private suspend fun refillAudioReserve(
        progress: Progress,
        targetUs: Long,
        activation: Long,
        read: (Int) -> AvStreamFrame,
    ) {
        while (sourceHasAudio && !eofWallClock && valid(activation, appliedSeek)) {
            pumpAudio(progress)
            if (progress.audioEnded && progress.audio == null) return
            val covered =
                synchronized(clockLock) {
                    val sink = pcm
                    audioAnchorUs + (sink?.bufferedPositionUs() ?: 0) >= targetUs ||
                        audioAnchorUs + (sink?.writtenPositionUs() ?: 0) >= targetUs
                }
            if (covered || !feedAudioStep(progress, activation, read, waitOnBackpressure = false)) return
        }
    }

    /**
     * Writes at most one bounded audio quantum, preserving offsets across partial sink acceptance.
     * Gap silence and media bytes keep separate credits; an invalid accepted byte count is an output failure.
     * Returns whether input credit advanced; optional reserve work never sleeps on refused credit.
     */
    @Suppress("CyclomaticComplexMethod", "ThrowsCount") // Partial source credits and both EOF guards commit together.
    private suspend fun feedAudioStep(
        progress: Progress,
        activation: Long,
        read: (Int) -> AvStreamFrame,
        waitOnBackpressure: Boolean = true,
    ): Boolean {
        if (!valid(activation, appliedSeek)) return false
        if (progress.audio == null && !progress.audioEnded) {
            when (val frame = read(AvStreamProtocol.AUDIO_TRACK)) {
                is AvStreamFrame.Audio -> adoptPcm(frame, progress, activation)
                AvStreamFrame.AudioEnd -> progress.audioEnded = true
                is AvStreamFrame.Video -> {
                    frame.bitmap.recycle()
                    throw IOException("Unexpected video from preferred audio read")
                }
                else -> throw IOException("Unexpected output from preferred audio read")
            }
        }
        val pending = progress.audio
        var acceptedInput = false
        if (pending != null && valid(activation, appliedSeek)) {
            val sink = checkNotNull(pcm)
            val frames = pending.frame.sampleRate / 100
            val frameBytes = pending.frame.channels * 2
            val count: Int
            val accepted: Int
            if (pending.gapFrames > 0) {
                val silent = pending.silence ?: ByteArray(frames * frameBytes).also { pending.silence = it }
                count = minOf(pending.gapFrames, frames.toLong()).toInt() * frameBytes
                accepted = sink.write(silent, 0, count)
                if (accepted !in 0..count || accepted % frameBytes != 0) {
                    throw RawOutputFailure("Raw audio accepted invalid input credit")
                }
                pending.gapFrames -= accepted / frameBytes
            } else {
                count = minOf(pending.limit - pending.offset, frames * frameBytes)
                accepted = sink.write(pending.frame.bytes, pending.offset, count)
                if (accepted !in 0..count || accepted % frameBytes != 0) {
                    throw RawOutputFailure("Raw audio accepted invalid input credit")
                }
                pending.offset += accepted
                if (pending.offset == pending.limit) {
                    val acceptedBytes = pending.limit - pending.initialOffset
                    val startUs = pending.timestampUs
                    val audioEnd = RawAvTiming.pcmEnd(startUs, acceptedBytes, sink.sampleRate, sink.channels)
                    progress.audioEndUs = maxOf(progress.audioEndUs, audioEnd)
                    progress.audio = null
                }
            }
            acceptedInput = accepted > 0
            if (accepted == 0 && waitOnBackpressure) delay(5)
        } else if (progress.audioEnded && waitOnBackpressure) {
            delay(5)
        }
        pumpAudio(progress)
        return acceptedInput
    }

    /**
     * Clips pre-seek PCM, checks stable layout and retains one pending suffix for partial writes.
     * A timestamp gap beyond 1 ms becomes frame-counted silence before that suffix.
     */
    private fun adoptPcm(
        frame: AvStreamFrame.Audio,
        progress: Progress,
        activation: Long,
    ) {
        val size = frame.bytes.size
        val clipped = RawAvTiming.clipPcm(size, frame.sampleRate, frame.channels, frame.timestampUs, progress.targetUs)
        if (clipped.byteCount == 0 || !valid(activation, appliedSeek)) return
        val sink = pcm ?: createPcm(frame, progress.targetUs, activation) ?: return
        if (sink.sampleRate != frame.sampleRate || sink.channels != frame.channels) {
            throw IOException("Raw audio layout changed")
        }
        val expected = synchronized(clockLock) { audioAnchorUs } + sink.writtenPositionUs()
        val delta = clipped.timestampUs - expected
        val gap = if (delta > 1000) delta * sink.sampleRate / 1_000_000 else 0
        progress.audio = PendingPcm(frame, clipped.offset, clipped.offset + clipped.byteCount, clipped.timestampUs, gap)
    }

    /**
     * Pumps device progress/EOS and returns allowed video lead in microseconds.
     * After audio drains, reanchors an elapsed-time clock so remaining video can advance.
     */
    private fun pumpAudio(progress: Progress): Long {
        val sink = pcm
        val ending = progress.audioEnded && progress.audio == null
        val tolerance = sink?.prepareForWait(ending) ?: 1000L
        val drained = ending && (sink == null || sink.drained())
        if (drained && sourceHasAudio && !eofWallClock) {
            synchronized(clockLock) {
                wallAnchorUs = positionUs()
                wallStartedMs = SystemClock.elapsedRealtime()
                eofWallClock = true
            }
        }
        return tolerance
    }

    /**
     * Waits for device drain and terminal media time before notifying a still-current owner.
     * Uses declared positive duration or observed audio/video end when metadata has no duration.
     */
    private suspend fun finishFrames(
        info: AvStreamInfo,
        activation: Long,
        progress: Progress,
    ) {
        while (valid(activation, appliedSeek) && pcm?.drained() == false) {
            pumpAudio(progress)
            delay(5)
        }
        pumpAudio(progress)
        val videoEnd = (progress.lastVideoPts + progress.lastVideoDelta.coerceIn(1000, 1_000_000)).coerceAtLeast(0)
        val observed = maxOf(progress.audioEndUs, videoEnd)
        val endUs = info.durationUs.takeIf { it > 0 } ?: observed
        while (valid(activation, appliedSeek) && !RawAvTiming.videoIsDue(endUs, positionUs())) delay(5)
        if (valid(activation, appliedSeek)) owner.ended(activation, appliedSeek, endUs)
    }

    /**
     * Seeks the owned stream to a microsecond target, closes old PCM and resets both clock anchors.
     * Already accepted speed/gain controls survive a blocked seek; only then is the seek acknowledged.
     */
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

    /**
     * Creates and configures one sink before adoption under the clock lock.
     * A canceled activation refuses adoption; every created but unadopted sink receives a best-effort
     * close attempt, with cleanup exceptions suppressed.
     */
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

    /**
     * Checks live playing activation and, when supplied, seek identity against a fresh owner intent.
     */
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

    /**
     * Pumps audio while waiting for a microsecond timestamp within device priming tolerance.
     * Returns false when activation or seek is superseded, leaving bitmap cleanup to the caller.
     */
    private suspend fun waitForVideo(
        timestampUs: Long,
        activation: Long,
        progress: Progress,
        reserveTargetUs: Long,
        read: (Int) -> AvStreamFrame,
    ): Boolean {
        while (valid(activation, appliedSeek)) {
            var tolerance = pumpAudio(progress)
            var clock = positionUs()
            if (timestampUs > clock + tolerance) {
                refillAudioReserve(progress, reserveTargetUs, activation, read)
                if (!valid(activation, appliedSeek)) break
                tolerance = pumpAudio(progress)
                clock = positionUs()
            }
            if (timestampUs <= clock + tolerance) return true
            delay(((timestampUs - clock) / 1000).coerceIn(1, 10))
        }
        return false
    }

    private companion object {
        const val AUDIO_RESERVE_US = 100_000L

        /** A paused worker beyond this idle time is retired; resuming later starts a new worker. */
        const val PARKED_IDLE_MS = 30_000L
    }
}
