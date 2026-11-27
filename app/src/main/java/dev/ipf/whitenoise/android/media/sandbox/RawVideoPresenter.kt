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
            retained?.let { releaseLocked(it.bitmap) }
            retained = frame
            dirty = true
            scheduleLocked()
        }
    }

    /** Redraws the retained frame, for example after the display surface was recreated. */
    fun redraw() {
        synchronized(lock) {
            if (closed || retained == null) return
            dirty = true
            scheduleLocked()
        }
    }

    /** Whether a frame for [seek] is already retained for display. */
    fun holds(seek: Long): Boolean = synchronized(lock) { retained?.seek == seek }

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
        synchronized(lock) {
            retained?.let { releaseLocked(it.bitmap, pooled = false) }
            retained = null
            dirty = false
            drainPoolLocked()
        }
    }

    fun seekTo(seek: Long) {
        synchronized(presentationLock) {
            allowedSeek = seek
            synchronized(lock) {
                retained?.let { releaseLocked(it.bitmap) }
                retained = null
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
                retained?.let { releaseLocked(it.bitmap, pooled = false) }
                retained = null
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
                    val next = retained
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
                val submittedRevision =
                    synchronized(presentationLock) {
                        if (closed || frame.seek != allowedSeek || attemptRevision != presentationRevision) return@synchronized null
                        var submitted: Long? = null
                        owner.submitIfCurrent {
                            val revision = output.surfaceRevision
                            if (draw(frame, revision)) submitted = revision
                        }
                        submitted
                    }
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

    private fun draw(
        frame: RawVideoFrame,
        surfaceRevision: Long,
    ): Boolean {
        val probe = observation ?: return output.render(frame.bitmap, frame.rotation, expectedRevision = surfaceRevision)
        val mediaBefore = owner.mediaPositionUs()
        val before = SystemClock.elapsedRealtimeNanos()
        val submitted = output.render(frame.bitmap, frame.rotation, probe, surfaceRevision)
        val after = SystemClock.elapsedRealtimeNanos()
        probe.submission(frame.activation, frame.seek, frame.ptsUs, mediaBefore, owner.mediaPositionUs(), before, after, submitted)
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
            bitmap === drawing -> deferred = bitmap
            pooled && !closed && reusable(bitmap) -> {
                pool.addFirst(bitmap)
                while (pool.size > POOL_SIZE) pool.removeLast().recycle()
            }
            else -> bitmap.recycle()
        }
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
