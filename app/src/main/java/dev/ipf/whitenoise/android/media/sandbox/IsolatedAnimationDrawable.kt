package dev.ipf.whitenoise.android.media.sandbox

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import dev.ipf.whitenoise.android.media.PreemptibleAnimation
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first

/** Displays only owned raw pixels. No ImageDecoder or compressed bytes exist in this drawable. */
@Suppress("TooManyFunctions") // Drawable/Animatable overrides and retirement belong to the same pixel owner.
internal class IsolatedAnimationDrawable(
    private var frame: Bitmap,
    private val session: AnimationSessionControl,
) : Drawable(),
    Animatable,
    PreemptibleAnimation,
    java.io.Closeable {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var running = false
    internal var presentedFrame: Long = 1L
        private set

    @Volatile private var closed = false
    private val width = frame.width
    private val height = frame.height

    /** Keeps layout tied to the first validated frame, even after later frames replace its pixels. */
    override fun getIntrinsicWidth() = width

    /** Reports the same fixed decode geometry that [accept] enforces for every subsequent frame. */
    override fun getIntrinsicHeight() = height

    /** Draws only the owned raw frame; retired or recycled pixels never reach the host canvas. */
    override fun draw(canvas: Canvas) {
        if (!closed && !frame.isRecycled) canvas.drawBitmap(frame, null, bounds, paint)
    }

    /** Applies presentation opacity without changing the isolated decoder or its frame ownership. */
    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    /** Applies a host paint filter to already-decoded pixels without rewriting the retained frame. */
    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    /** Decoded frames may contain alpha, so callers must not assume an opaque background. */
    @Deprecated("Legacy Drawable opacity")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    /** Reports this drawable's playback request, without treating it as proof of worker liveness. */
    override fun isRunning() = running && !closed

    /** Starts frame delivery only for an open session; a retired drawable cannot resurrect its worker. */
    override fun start() {
        if (!closed && session.isOpen()) {
            running = true
            session.play()
        }
    }

    /** Pauses production while retaining the last displayed frame until ownership is closed. */
    override fun stop() {
        running = false
        session.pause()
    }

    /** Only an explicit-viewer takeover permits the owner to request a replacement decoder. */
    override suspend fun awaitPreemption() {
        session.preempted?.first { it } ?: awaitCancellation()
    }

    /** A worker loss wakes the same mounted owner, without claiming its cause is known. */
    override suspend fun awaitTermination(): dev.ipf.whitenoise.android.media.AnimationTermination {
        val reasons = session.termination
        return if (reasons != null) {
            checkNotNull(reasons.first { it != null })
        } else {
            awaitPreemption()
            dev.ipf.whitenoise.android.media.AnimationTermination.Preempted
        }
    }

    /** Takes ownership of one validated frame and recycles late, mismatched or superseded pixels. */
    fun accept(next: Bitmap) {
        // The worker may finish its one outstanding frame after PAUSE. ACK still happens in
        // the session, but a stopped drawable must retain the pixels already on screen.
        val inactive = closed || !running
        if (inactive || next.width != width || next.height != height) {
            next.recycle()
            return
        }
        val previous = frame
        frame = next
        presentedFrame++
        invalidateSelf()
        previous.recycle()
    }

    /** Retires the session once and releases its final bitmap/callback on the drawing thread. */
    override fun close() {
        if (closed) return
        closed = true
        running = false
        session.close()
        val dispose =
            Runnable {
                frame.recycle()
                callback = null
            }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            dispose.run()
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post(dispose)
        }
    }
}
