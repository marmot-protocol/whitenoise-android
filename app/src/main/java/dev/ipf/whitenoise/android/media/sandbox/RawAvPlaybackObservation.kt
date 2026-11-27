package dev.ipf.whitenoise.android.media.sandbox

import android.graphics.Bitmap
import android.os.SystemClock

/** Optional synchronous scalar recorder; implementations must be bounded, nonblocking and never throw.
 * Null is the production default: no record allocation, diagnostic clock reads or observer calls.
 * A successful submission is canvas-post completion, never physical display presentation.
 */
@Suppress("LongParameterList") // Primitive records avoid allocating frame objects inside output ownership fences.
internal interface RawAvPlaybackObservation {
    /** Reports one fenced sink attempt; -1 PTS denotes an untimed preparation poster. */
    fun submission(
        activation: Long,
        seek: Long,
        ptsUs: Long,
        mediaBeforeUs: Long,
        mediaAfterUs: Long,
        startNs: Long,
        endNs: Long,
        submitted: Boolean,
    )

    /** Reports successful canvas acquisition/draw/post phases, excluding the outer surface-monitor wait. */
    fun canvas(
        acquireStartNs: Long,
        acquiredNs: Long,
        drawEndNs: Long,
        postEndNs: Long,
    ) = Unit

    /** Reports an authenticated READY UID/PID and activation; retirement still requires the external lifetime trace. */
    fun worker(
        activation: Long,
        uid: Int,
        pid: Int,
        timeNs: Long,
    ) = Unit

    /** Samples the actual device head/underruns; lifetime is a sink identity, not a global track counter. */
    fun audio(
        activation: Long,
        seek: Long,
        lifetime: Int,
        timeNs: Long,
        hardwareFrames: Long,
        underruns: Int,
    ) = Unit
}

/** Measures an observed render while the caller holds its existing output fences. */
@Suppress("LongParameterList") // The observer measures the same bitmap, position and generation as the renderer.
internal fun RawAvPlaybackObservation.renderAndObserve(
    video: RawVideoOutput,
    bitmap: Bitmap,
    rotation: Int,
    generation: RawAvGeneration,
    ptsUs: Long,
    positionUs: () -> Long,
): Boolean {
    val mediaBefore = positionUs()
    val start = SystemClock.elapsedRealtimeNanos()
    val submitted = video.render(bitmap, rotation, this)
    val end = SystemClock.elapsedRealtimeNanos()
    submission(generation.activation, generation.seek, ptsUs, mediaBefore, positionUs(), start, end, submitted)
    return submitted
}
