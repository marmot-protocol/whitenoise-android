@file:Suppress("MagicNumber") // Fixed PCM units, renderer clocks and Player protocol constants.

package dev.ipf.whitenoise.android.media.sandbox

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Presentation state only. Every compressed byte is parsed in the isolated raw-output worker.
 * One lock owns control/focus/seek/listener generations; bounded IO and sinks are separate helpers.
 * The optional metadata probe supports held-preparation tests; production defaults to isolation.
 */
@Suppress("TooManyFunctions", "LargeClass", "LongParameterList")
internal class RawAvPlaybackEngine(
    context: Context,
    private val uri: Uri,
    private val wantVideo: Boolean = true,
    private val wantAudio: Boolean = true,
    private val externalAudioFocus: Boolean = false,
    private val listener: Listener,
    prepareProbe: (() -> MediaProbe?)? = null,
) : AutoCloseable,
    RawAvRenderOwner,
    RawAvPausedPosterOwner {
    enum class Status { Idle, Buffering, Ready, Ended }

    data class Snapshot(
        val status: Status,
        val prepared: Boolean,
        val playWhenReady: Boolean,
        val playWhenReadyChangeReason: Int,
        val positionMs: Long,
        val durationMs: Long,
        val speed: Float,
        val volume: Float,
        val repeat: Boolean,
        val videoWidth: Int,
        val videoHeight: Int,
        val audioRate: Int,
        val audioChannels: Int,
        val error: IOException?,
        val renderedFrame: Long,
    )

    interface Listener {
        fun onChanged()

        fun onCompleted() = Unit
    }

    private val context =
        context.applicationContext.also {
            require(wantVideo || wantAudio)
            require(uri.scheme == "file" || uri.scheme == "content")
        }
    private val lock = Any()
    private val frameLock = Any()
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val epoch = owners.epoch()
    private var prepared = SettableFuture.create<Void>()
    private var preparationRequested = false
    private var prepareGeneration = 0L
    private var prepareJobGeneration = -1L
    private val probeSource: () -> MediaProbe? =
        prepareProbe ?: {
            IsolatedMediaDecoder.probeUri(this.context.contentResolver, uri, if (wantVideo) 1280 else 1)
        }
    private var prepareJob: Job? = null
    private var status = Status.Idle
    private var headerReady = false
    private var playIntent = false
    private var playWhenReadyChangeReason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
    private var released = false
    private var activation = 0L
    private var revision = 0L
    private var seekRevision = 0L
    private var pendingSeek: SettableFuture<Void>? = null
    private var targetUs = 0L
    private var pausedUs = 0L
    private var durationUs = 0L
    private var speed = 1f
    private var volume = 1f
    private var repeat = false
    private var width = 0
    private var height = 0
    private var rotation = 0
    private var rate = 0
    private var channels = 0
    private var error: IOException? = null
    private var renderedFrame = 0L
    private var resumeOnGain = false
    private var ducked = false
    private var handlesFocus = !externalAudioFocus
    private var attributes =
        AudioAttributes
            .Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()
    private var lastBitmap: Bitmap? = null
    private var lastBitmapRotation = 0
    private var lastBitmapSeek = -1L

    @Volatile private var redraw = false

    @Volatile private var firstFrameForOutput = false
    private val video =
        RawVideoOutput {
            redraw = true
            firstFrameForOutput = false
            signal.trySend(Unit)
        }
    private val renderer = RawAvRenderSession(this.context, uri, wantVideo, wantAudio, this)
    private val pausedPoster = RawAvPausedPoster(this.context, uri, scope, this)
    private val focus =
        RawAvAudioFocus(
            this.context,
            { synchronized(lock) { current() } },
            ::focusChanged,
            { reason -> pause(false, reason) },
            !externalAudioFocus,
        )

    init {
        if (owners.register(this, epoch)) scope.launch { worker() } else close()
    }

    fun snapshot(): Snapshot =
        synchronized(lock) {
            val rotated = rotation == 90 || rotation == 270
            Snapshot(
                status,
                headerReady,
                playIntent,
                playWhenReadyChangeReason,
                positionUsLocked() / 1000,
                durationUs / 1000,
                speed,
                volume,
                repeat,
                if (rotated) height else width,
                if (rotated) width else height,
                rate,
                channels,
                error,
                renderedFrame,
            )
        }

    fun positionMs(): Long = synchronized(lock) { positionUsLocked() / 1000 }

    private fun current(): Boolean = !released && owners.current(this, epoch)

    private fun positionUsLocked(): Long {
        val running = playIntent && status == Status.Ready
        val matching = renderer.activeRun == activation && renderer.appliedSeek == seekRevision
        if (!running || !matching) return pausedUs
        val position = renderer.positionUs().coerceAtLeast(0)
        return if (durationUs > 0) position.coerceAtMost(durationUs) else position
    }

    /** Paused preparation displays a poster without reserving a live playback worker. */
    fun prepare(): ListenableFuture<Void> =
        synchronized(lock) {
            if (!current()) {
                prepared.setException(IOException("Media source owner retired"))
            } else {
                val restart = status == Status.Idle || error != null || prepared.isCancelled
                if (restart) {
                    // An early paused seek may already wait for the initial unadmitted promise.
                    if (prepareGeneration != 0L || prepared.isDone) prepared = SettableFuture.create()
                    prepareGeneration++
                }
                preparationRequested = true
                error = null
                if (headerReady) {
                    if (restart) status = if (playIntent) Status.Buffering else Status.Ready
                    prepared.set(null)
                    if (restart && !playIntent) restorePosterLocked()
                } else {
                    status = Status.Buffering
                    startPreparationLocked()
                }
                notifyChanged()
            }
            prepared
        }

    private fun startPreparationLocked() {
        if (prepareJob != null) return
        val generation = prepareGeneration
        val completion = prepared
        prepareJobGeneration = generation
        prepareJob = scope.launch(start = CoroutineStart.LAZY) { probe(generation, completion) }
        checkNotNull(prepareJob).start()
    }

    private fun preparationCurrent(
        generation: Long,
        completion: SettableFuture<Void>,
    ): Boolean = current() && preparationRequested && prepareGeneration == generation && prepared === completion

    @Suppress("TooGenericExceptionCaught") // A refused optional poster does not disable explicit playback.
    private fun probe(generation: Long, completion: SettableFuture<Void>) {
        var poster: Bitmap? = null
        try {
            val requested =
                synchronized(lock) {
                    if (preparationCurrent(generation, completion)) {
                        RawAvPosterTarget(RawAvGeneration(activation, seekRevision), 0)
                    } else {
                        null
                    }
                } ?: return
            val probe = probeSource()
            poster = probe?.poster
            synchronized(lock) {
                if (!preparationCurrent(generation, completion)) return@synchronized
                durationUs = (probe?.durationMs ?: 0) * 1000
                width = probe?.width ?: 0
                height = probe?.height ?: 0
                val posterGeneration = RawAvGeneration(activation, seekRevision)
                val posterCurrent = requested.matches(posterGeneration, current(), playIntent, targetUs / 1000)
                if (wantVideo && poster != null && posterCurrent) {
                    retainFrame(checkNotNull(poster), 0)
                    poster = null
                }
                finishPreparation(completion)
            }
        } catch (_: Exception) {
            finishRefusedProbe(generation, completion)
        } catch (_: OutOfMemoryError) {
            finishRefusedProbe(generation, completion)
        } finally {
            poster?.recycle()
            finishProbe(generation)
        }
    }

    private fun finishProbe(generation: Long) {
        synchronized(lock) {
            if (prepareJobGeneration == generation) {
                prepareJob = null
                prepareJobGeneration = -1
            }
            // A stopped synchronous probe still owns its resources until return; only then start its successor.
            val preparing = current() && preparationRequested && !headerReady
            if (preparing && prepareGeneration != generation) {
                startPreparationLocked()
            }
        }
        signal.trySend(Unit)
    }

    private fun finishRefusedProbe(
        generation: Long,
        completion: SettableFuture<Void>,
    ) {
        synchronized(lock) {
            if (preparationCurrent(generation, completion)) finishPreparation(completion) else completion.cancel(false)
        }
    }

    private fun finishPreparation(completion: SettableFuture<Void>) {
        headerReady = true
        status = if (playIntent) Status.Buffering else Status.Ready
        completion.set(null)
        notifyChanged()
    }

    private fun restorePosterLocked() {
        redraw = true
        firstFrameForOutput = false
        val matching = synchronized(frameLock) { lastBitmap != null && lastBitmapSeek == seekRevision }
        if (wantVideo && !matching) {
            pendingSeek?.cancel(false)
            val completion = SettableFuture.create<Void>()
            pendingSeek = completion
            dispatchPausedSeek(completion)
        }
        signal.trySend(Unit)
    }

    /** Focus is acquired before a prior player is asked to surrender. */
    @Suppress("ReturnCount") // Pause, denied focus and retired ownership are independent early refusals.
    fun setPlaying(
        playing: Boolean,
        reason: Int = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
    ): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!playing) {
            pause(false, reason)
            return true
        }
        synchronized(lock) {
            if (!current()) return false
            if (playIntent) return true
        }
        if (handlesFocus && !acquireFocus()) return false
        val claim = owners.activate(this, epoch)
        if (!claim.accepted) {
            if (handlesFocus) focus.release()
            return false
        }
        if (claim.previous !== this) claim.previous?.pause(false)
        synchronized(lock) {
            if (!current()) return false
            val outputRefusal = renderer.volume(volume)
            if (outputRefusal != null) {
                failPlaybackLocked(outputRefusal)
                return false
            }
            if (status == Status.Ended) {
                targetUs = 0
                pausedUs = 0
                seekRevision++
            }
            pendingSeek?.cancel(false)
            pendingSeek = null
            error = null
            ducked = false
            playIntent = true
            playWhenReadyChangeReason = reason
            activation++
            revision++
            status = Status.Buffering
            resumeOnGain = false
            // Publish playing intent and its admitted preparation together before the renderer can observe either.
            prepare()
            notifyChanged()
        }
        registerNoisy()
        signal.trySend(Unit)
        return true
    }

    private fun pause(
        retainFocus: Boolean,
        reason: Int = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
    ) {
        var clockRefusal: RawOutputFailure? = null
        val expectedActivation =
            synchronized(lock) {
                if (playIntent) {
                    val position = runCatching { positionUsLocked() }
                    pausedUs = position.getOrDefault(pausedUs)
                    position.exceptionOrNull()?.let {
                        clockRefusal = RawOutputFailure("Raw audio clock unavailable", it)
                    }
                    targetUs = pausedUs
                    playWhenReadyChangeReason = reason
                    playIntent = false
                    pendingSeek?.cancel(false)
                    pendingSeek = null
                    activation++
                    revision++
                }
                // Repeated paused commands preserve the admitted poster/seek, but still surrender focus below.
                resumeOnGain = retainFocus
                if (headerReady && status != Status.Ended) status = Status.Ready
                notifyChanged()
                activation
            }
        val refusal = renderer.pause() ?: clockRefusal
        synchronized(lock) {
            if (refusal != null && current() && activation == expectedActivation) failPlaybackLocked(refusal)
        }
        unregisterNoisy()
        if (!retainFocus || refusal != null) releaseFocus(false)
        signal.trySend(Unit)
    }

    fun seekTo(positionMs: Long) {
        seekToAsync(positionMs)
    }

    fun seekToAsync(positionMs: Long): ListenableFuture<Void> {
        val completion = SettableFuture.create<Void>()
        synchronized(lock) {
            if (!current()) {
                completion.setException(IOException("Media source owner retired"))
                return completion
            }
            pendingSeek?.cancel(false)
            pendingSeek = completion
            targetUs = RawAvPosterTarget.positionUs(positionMs, durationUs)
            pausedUs = targetUs
            seekRevision++
            revision++
            firstFrameForOutput = false
            if (!playIntent) dispatchPausedSeek(completion)
            if (status == Status.Ended) status = Status.Ready
            notifyChanged()
        }
        signal.trySend(Unit)
        return completion
    }

    private fun dispatchPausedSeek(completion: SettableFuture<Void>) {
        if (wantVideo) {
            val target = RawAvPosterTarget(RawAvGeneration(activation, seekRevision), targetUs / 1000)
            pausedPoster.submit(RawAvPausedSeekRequest(target, completion))
        } else {
            completion.set(null)
            pendingSeek = null
        }
    }

    override fun posterCurrent(target: RawAvPosterTarget): Boolean =
        synchronized(lock) {
            target.matches(RawAvGeneration(activation, seekRevision), current(), playIntent, targetUs / 1000)
        }

    override fun posterResult(
        request: RawAvPausedSeekRequest,
        probe: MediaProbe?,
    ): Boolean =
        synchronized(lock) {
            val currentRequest = posterCurrent(request.target) && pendingSeek === request.completion
            if (!currentRequest || request.completion.isCancelled) return@synchronized false
            probe?.poster?.let {
                width = probe.width
                height = probe.height
                rotation = 0 // Automatic poster dimensions and pixels are already display-oriented.
                retainFrame(it, 0)
            }
            request.finish(true) // Acknowledgement and frame publication share the generation lock.
            pendingSeek = null
            signal.trySend(Unit)
            true
        }

    override fun seeked(
        activation: Long,
        seek: Long,
    ) {
        synchronized(lock) {
            if (!accepts(activation, seek)) return
            pendingSeek?.set(null)
            pendingSeek = null
        }
    }

    fun setSpeed(value: Float): Boolean {
        require(value.isFinite() && value in RawPcmCapacity.MIN_SPEED..RawPcmCapacity.MAX_SPEED)
        return synchronized(lock) {
            if (!current()) return@synchronized false
            val refusal = renderer.speed(value)
            if (refusal != null) {
                failPlaybackLocked(refusal)
                false
            } else {
                speed = value
                notifyChanged()
                signal.trySend(Unit)
                true
            }
        }
    }

    fun setVolume(value: Float): Boolean {
        require(value.isFinite() && value in 0f..1f)
        return synchronized(lock) {
            if (!current()) return@synchronized false
            val gain = value * if (ducked) 0.2f else 1f
            val refusal = renderer.volume(gain)
            if (refusal != null) {
                failPlaybackLocked(refusal)
                false
            } else {
                volume = value
                notifyChanged()
                true
            }
        }
    }

    fun setRepeat(value: Boolean) {
        synchronized(lock) {
            repeat = value
            notifyChanged()
        }
    }

    fun setAttributes(value: AudioAttributes) {
        synchronized(lock) { attributes = value }
    }

    fun setFocusHandling(enabled: Boolean) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!enabled && handlesFocus) releaseFocus(false)
        val acquire = enabled && !handlesFocus && synchronized(lock) { playIntent }
        handlesFocus = enabled
        if (acquire && !acquireFocus()) pause(false)
    }

    fun setHandleAudioBecomingNoisy(value: Boolean) = focus.noisy(value, synchronized(lock) { playIntent })

    fun attachVideo(output: Any?) {
        video.attach(output)
        redraw = true
        signal.trySend(Unit)
    }

    fun clearVideo(output: Any?) = video.clear(output)

    fun stop() {
        setPlaying(false)
        synchronized(lock) {
            preparationRequested = false
            prepareGeneration++
            if (!prepared.isDone) prepared.cancel(false)
            status = Status.Idle
            firstFrameForOutput = false
            notifyChanged()
        }
    }

    override fun intent(): RawAvPlaybackIntent =
        synchronized(lock) {
            RawAvPlaybackIntent(
                current(),
                playIntent,
                activation,
                seekRevision,
                targetUs,
                speed,
                volume * if (ducked) 0.2f else 1f,
                attributes,
            )
        }

    /** Consumers may wait for admitted preparation; stale work must never start a new producer. */
    override fun awaitPrepared() {
        val completion = synchronized(lock) { prepared }
        completion.get(24, TimeUnit.SECONDS)
    }

    override fun opened(
        info: AvStreamInfo,
        activation: Long,
    ) = synchronized(lock) {
        if (!current() || this.activation != activation || !playIntent) return@synchronized
        width = info.width
        height = info.height
        rotation = info.rotationDegrees
        rate = info.sampleRate
        channels = info.channels
        if (info.durationUs > 0) durationUs = info.durationUs
        status = Status.Ready
        notifyChanged()
    }

    override fun frame(
        bitmap: Bitmap,
        rotation: Int,
        activation: Long,
        seek: Long,
    ): Boolean {
        val accepted =
            synchronized(lock) {
                if (!accepts(activation, seek)) {
                    false
                } else {
                    retainFrame(bitmap, rotation)
                    true
                }
            }
        if (accepted) renderRetained()
        return accepted
    }

    override fun ended(
        activation: Long,
        seek: Long,
        endUs: Long,
    ) = synchronized(lock) {
        if (!accepts(activation, seek)) return@synchronized
        if (repeat) {
            targetUs = 0
            pausedUs = 0
            seekRevision++
            firstFrameForOutput = false
        } else {
            if (durationUs == 0L) durationUs = endUs
            pausedUs = durationUs.takeIf { it > 0 } ?: endUs
            targetUs = pausedUs
            playIntent = false
            status = Status.Ended
            revision++
            notifyChanged()
            notifyCompletion()
            releaseFocus(false)
            main.post { if (!intent().playing) unregisterNoisy() }
        }
    }

    override fun failed(
        activation: Long,
        seek: Long?,
        failure: IOException,
    ) = synchronized(lock) {
        val running = current() && playIntent && this.activation == activation
        val matching = seek == null || seekRevision == seek
        if (!running || !matching) return@synchronized
        failPlaybackLocked(failure)
    }

    private fun failPlaybackLocked(failure: IOException) {
        if (!current()) return
        pausedUs = runCatching { positionUsLocked() }.getOrDefault(pausedUs)
        targetUs = pausedUs
        pendingSeek?.setException(failure)
        pendingSeek = null
        preparationRequested = false
        prepareGeneration++
        if (!prepared.isDone) prepared.setException(failure)
        error = failure
        playIntent = false
        resumeOnGain = false
        ducked = false
        status = Status.Idle
        activation++
        revision++
        renderer.pause()?.let { if (it !== failure) failure.addSuppressed(it) }
        notifyChanged()
        releaseFocus(false)
        main.post { if (!intent().playing) unregisterNoisy() }
        signal.trySend(Unit)
    }

    private suspend fun worker() {
        try {
            while (scope.isActive) {
                signal.receive()
                renderRetained()
                while (scope.isActive && intent().let { it.alive && it.playing }) {
                    renderer.run(intent().activation)
                    renderRetained()
                }
            }
        } catch (
            _: CancellationException,
        ) {
            // Release revokes the worker and all callbacks.
        } catch (
            _: ClosedReceiveChannelException,
        ) {
            // Release closed the conflated command signal.
        } finally {
            renderer.pause()
            synchronized(frameLock) {
                lastBitmap?.recycle()
                lastBitmap = null
            }
        }
    }

    private fun accepts(
        activation: Long,
        seek: Long,
    ): Boolean {
        val generation = RawAvGeneration(activation, seek)
        return generation.accepts(RawAvGeneration(this.activation, seekRevision), current(), playIntent)
    }

    private fun retainFrame(
        bitmap: Bitmap,
        frameRotation: Int,
    ) {
        synchronized(frameLock) {
            lastBitmap?.recycle()
            lastBitmap = bitmap
            lastBitmapRotation = frameRotation
            lastBitmapSeek = seekRevision
        }
        redraw = true
    }

    private fun renderRetained() {
        synchronized(lock) {
            if (!current() || !redraw) return
            // Lock order: engine -> account registry -> frame -> surface. Reset closes outside registry.
            owners.withCurrent(this, epoch) {
                redraw = false
                val rendered =
                    synchronized(frameLock) {
                        lastBitmap?.let { video.render(it, lastBitmapRotation) } == true
                    }
                if (rendered && !firstFrameForOutput) {
                    firstFrameForOutput = true
                    renderedFrame++
                    notifyChanged()
                }
            }
        }
    }

    private fun notifyChanged() {
        val expected = revision
        main.post { if (synchronized(lock) { current() && revision == expected }) listener.onChanged() }
    }

    private fun notifyCompletion() {
        val expected = revision
        main.post {
            if (synchronized(
                    lock,
                ) { current() && revision == expected && status == Status.Ended }
            ) {
                listener.onCompleted()
            }
        }
    }

    private fun acquireFocus(): Boolean = focus.acquire(synchronized(lock) { attributes })

    private fun focusChanged(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> pause(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause(true, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> applyDucking(true)
            AudioManager.AUDIOFOCUS_GAIN -> {
                val resume = synchronized(lock) { resumeOnGain }
                if (applyDucking(false) && resume) {
                    setPlaying(true, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
                }
            }
        }
    }

    private fun applyDucking(value: Boolean): Boolean =
        synchronized(lock) {
            if (!current()) return@synchronized false
            val refusal = renderer.volume(volume * if (value) 0.2f else 1f)
            if (refusal != null) {
                failPlaybackLocked(refusal)
                false
            } else {
                ducked = value
                true
            }
        }

    private fun releaseFocus(removeOwner: Boolean) {
        val removedActive = removeOwner && owners.remove(this)
        val action = {
            val release = { if (handlesFocus) focus.release() }
            if (removeOwner) {
                if (removedActive) owners.withNoActive(release)
            } else {
                owners.withActive(this, release)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() }
    }

    private fun releaseRetiredFocus() {
        val action = { owners.withNoActive { if (handlesFocus) focus.release() } }
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() }
    }

    private fun registerNoisy() = focus.started()

    private fun unregisterNoisy() = focus.paused()

    override fun close() {
        synchronized(lock) {
            if (released) return
            pausedUs = positionUsLocked()
            targetUs = pausedUs
            playIntent = false
            released = true
            pendingSeek?.cancel(false)
            pendingSeek = null
            activation++
            revision++
        }
        renderer.pause()
        focus.close()
        releaseFocus(true)
        prepared.setException(IOException("Media source owner retired"))
        prepareJob?.cancel()
        scope.cancel()
        signal.close()
        synchronized(frameLock) {
            lastBitmap?.recycle()
            lastBitmap = null
        }
        video.close()
    }

    companion object {
        private val owners = RawAvAccountOwners<RawAvPlaybackEngine>()

        fun retireAll() {
            // Invalidate stream generations first; engines admitted after the parent flip must survive.
            IsolatedAvStream.retireActive()
            val retired = owners.retireAll()
            retired.active?.releaseRetiredFocus()
            retired.instances.forEach { it.close() }
        }
    }
}
