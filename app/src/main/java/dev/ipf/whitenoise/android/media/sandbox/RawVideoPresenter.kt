package dev.ipf.whitenoise.android.media.sandbox

import android.graphics.Bitmap
import android.os.Process
import android.os.SystemClock
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** One raw frame and the playback generation it belongs to. */
internal class RawVideoFrame(
    val bitmap: Bitmap,
    val rotation: Int,
    val activation: Long,
    val seek: Long,
    val ptsUs: Long,
)

/** Engine checks consulted on the display thread; both take the engine lock only briefly. */
internal interface RawVideoPresenterOwner {
    /** Submits only while this owner is registered for the current account. */
    fun submitIfCurrent(draw: () -> Unit)

    /** Reports one completed post of a frame retained for [seek]. */
    fun shown(
        seek: Long,
        surfaceRevision: Long,
        presentationRevision: Long,
    )

    /** Current media position for optional submission measurements. */
    fun mediaPositionUs(): Long
}

/**
 * Draws on a display thread while the worker decodes the next frame. A newer frame replaces one
 * that has not been drawn yet. A small pool reuses pixel memory during steady playback.
 *
 * The presentation lock orders seek changes and close with surface submission. Account retirement
 * uses its own submission fence so playback can continue reading account state during a draw.
 */
@Suppress("TooManyFunctions") // One owner orders frame memory, seeks, and submission.
internal class RawVideoPresenter(
    private val output: RawVideoOutput,
    private val owner: RawVideoPresenterOwner,
    private val observation: RawAvPlaybackObservation? = null,
    executor: Executor? = null,
) : AutoCloseable {
    private val lock = Any()
    private val presentationLock = Any()
    private var allowedSeek = 0L
    private var presentationRevision = 0L
    private val pool = ArrayDeque<Bitmap>()
    private var retained: RawVideoFrame? = null
    private var committed: RawVideoFrame? = null
    private var allowCommitted = false
    private var dirty = false
    private var scheduled = false
    private var drawing: Bitmap? = null
    private var deferred: Bitmap? = null
    private var closed = false
    private var poolWidth = 0
    private var poolHeight = 0
    private val ownedDisplay: ExecutorService? =
        if (executor != null) {
            null
        } else {
            Executors.newSingleThreadExecutor { task ->
                Thread({
                    try {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
                    } catch (_: SecurityException) {
                        // Keep the inherited priority when Android refuses the display boost.
                    }
                    task.run()
                }, "raw-video-display").apply { isDaemon = true }
            }
        }
    private val display: Executor = executor ?: checkNotNull(ownedDisplay)

    /** Takes ownership of [frame] and schedules its draw; the previously retained frame is released. */
    fun show(frame: RawVideoFrame) {
        synchronized(lock) {
            if (closed) {
                frame.bitmap.recycle()
                return
            }
            val replaced = retained
            retained = frame
            replaced?.let { releaseLocked(it.bitmap) }
            dirty = true
            scheduleLocked()
        }
    }

    /** Redraws current pixels, or the last posted frame while a paused seek is unresolved. */
    fun redraw(allowCommitted: Boolean = false) {
        synchronized(lock) {
            val frame = retained ?: committed.takeIf { allowCommitted }
            if (closed || frame == null) return
            this.allowCommitted = allowCommitted
            dirty = true
            scheduleLocked()
        }
    }

    /** A preparation poster can stand in for a playback frame only at the start. */
    fun holds(seek: Long, requireTimestamp: Boolean): Boolean =
        synchronized(lock) { retained?.let { it.seek == seek && (!requireTimestamp || it.ptsUs >= 0) } == true }

    /** The retained bitmap, for tests and diagnostics only. */
    internal fun retainedBitmap(): Bitmap? = synchronized(lock) { retained?.bitmap }

    /**
     * Returns pixel memory for a [width] x [height] raw frame, reusing a released bitmap when one fits.
     * Callers own the result exactly like a newly created bitmap.
     */
    fun obtain(
        width: Int,
        height: Int,
    ): Bitmap =
        synchronized(lock) {
            if (width != poolWidth || height != poolHeight) {
                drainPoolLocked()
                poolWidth = width
                poolHeight = height
            }
            pool.removeFirstOrNull()
        } ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    /** Drops the retained frame and pooled memory; a later frame may still be shown. */
    fun clear() {
        synchronized(presentationLock) {
            synchronized(lock) {
                presentationRevision++
                forgetFramesLocked()
                dirty = false
                drainPoolLocked()
            }
        }
    }

    fun seekTo(seek: Long) {
        synchronized(presentationLock) {
            allowedSeek = seek
            synchronized(lock) {
                val replaced = retained
                retained = null
                replaced?.let { releaseLocked(it.bitmap) }
                allowCommitted = false
                dirty = false
            }
        }
    }

    /** Stop queued posts and their callbacks while preserving the retained frame for a later redraw. */
    fun cancelPending() {
        synchronized(presentationLock) {
            synchronized(lock) {
                presentationRevision++
                dirty = false
            }
        }
    }

    /** Tests whether a completed draw can still update the engine after a stop or failure. */
    fun isCurrentPresentation(revision: Long): Boolean = synchronized(lock) { revision == presentationRevision }

    override fun close() {
        synchronized(presentationLock) {
            synchronized(lock) {
                if (closed) return
                closed = true
                forgetFramesLocked()
                drainPoolLocked()
            }
            output.close()
        }
        ownedDisplay?.shutdown()
    }

    private fun scheduleLocked() {
        if (scheduled) return
        scheduled = true
        display.execute(::drawPending)
    }

    /** Draws until no newer frame is pending; each draw happens outside the presenter lock. */
    private fun drawPending() {
        while (true) {
            val attemptRevision: Long
            val frame =
                synchronized(lock) {
                    val next = retained ?: committed.takeIf { allowCommitted }
                    if (closed || !dirty || next == null) {
                        scheduled = false
                        return
                    }
                    dirty = false
                    drawing = next.bitmap
                    attemptRevision = presentationRevision
                    next
                }
            var completed = false
            try {
                val submittedRevision = postCurrent(frame, attemptRevision)
                submittedRevision?.let { owner.shown(frame.seek, it, attemptRevision) }
                completed = true
            } finally {
                synchronized(lock) {
                    if (!completed) scheduled = false
                    drawing = null
                    deferred?.let { releaseLocked(it, pooled = !closed) }
                    deferred = null
                }
            }
        }
    }

    private fun postCurrent(frame: RawVideoFrame, attemptRevision: Long): Long? =
        synchronized(presentationLock) {
            if (closed || attemptRevision != presentationRevision || !canDraw(frame)) return@synchronized null
            var submitted: Long? = null
            owner.submitIfCurrent {
                val revision = output.surfaceRevision
                if (draw(frame, revision)) {
                    submitted = revision
                    synchronized(lock) {
                        val previous = committed
                        committed = frame
                        if (previous !== frame) previous?.let { releaseLocked(it.bitmap) }
                    }
                }
            }
            submitted
        }

    private fun canDraw(frame: RawVideoFrame): Boolean =
        frame.seek == allowedSeek || synchronized(lock) { allowCommitted && frame === committed }

    private fun draw(
        frame: RawVideoFrame,
        surfaceRevision: Long,
    ): Boolean {
        val probe = observation
        if (probe == null) return output.render(frame.bitmap, frame.rotation, expectedRevision = surfaceRevision)
        val mediaBefore = owner.mediaPositionUs()
        val before = SystemClock.elapsedRealtimeNanos()
        val submitted = output.render(frame.bitmap, frame.rotation, probe, surfaceRevision)
        val after = SystemClock.elapsedRealtimeNanos()
        probe.submission(
            frame.activation,
            frame.seek,
            frame.ptsUs,
            mediaBefore,
            owner.mediaPositionUs(),
            before,
            after,
            submitted,
        )
        return submitted
    }

    /**
     * Pools a released frame-sized bitmap or recycles anything else, such as posters.
     * A bitmap still being drawn is released by the display thread afterwards.
     */
    private fun releaseLocked(
        bitmap: Bitmap,
        pooled: Boolean = true,
    ) {
        when {
            bitmap === retained?.bitmap || bitmap === committed?.bitmap -> Unit
            bitmap === drawing -> deferred = bitmap
            pooled && !closed && reusable(bitmap) -> {
                pool.addFirst(bitmap)
                while (pool.size > POOL_SIZE) pool.removeLast().recycle()
            }
            else -> bitmap.recycle()
        }
    }

    private fun forgetFramesLocked() {
        val latest = retained?.bitmap
        val lastShown = committed?.bitmap
        retained = null
        committed = null
        latest?.let { releaseLocked(it, pooled = false) }
        if (lastShown !== latest) lastShown?.let { releaseLocked(it, pooled = false) }
    }

    private fun reusable(bitmap: Bitmap): Boolean =
        bitmap.width == poolWidth &&
            bitmap.height == poolHeight &&
            bitmap.isMutable &&
            bitmap.config == Bitmap.Config.ARGB_8888 &&
            !bitmap.isRecycled

    private fun drainPoolLocked() {
        while (pool.isNotEmpty()) pool.removeFirst().recycle()
    }

    private companion object {
        /** Enough for the frame being decoded while one is displayed and one waits for display. */
        const val POOL_SIZE = 2
    }
}
