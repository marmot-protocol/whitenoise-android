package dev.ipf.whitenoise.android.media.sandbox

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable

/** Displays only owned raw pixels. No ImageDecoder or compressed bytes exist in this drawable. */
internal class IsolatedAnimationDrawable(
    private var frame: Bitmap,
    private val session: AnimationSessionControl,
) : Drawable(),
    Animatable,
    java.io.Closeable {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var running = false

    @Volatile private var closed = false
    private val width = frame.width
    private val height = frame.height

    override fun getIntrinsicWidth() = width

    override fun getIntrinsicHeight() = height

    override fun draw(canvas: Canvas) {
        if (!closed && !frame.isRecycled) canvas.drawBitmap(frame, null, bounds, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Legacy Drawable opacity")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    override fun isRunning() = running && !closed

    override fun start() {
        if (!closed && session.isOpen()) {
            running = true
            session.play()
        }
    }

    override fun stop() {
        running = false
        session.pause()
    }

    fun accept(next: Bitmap) {
        if (closed || next.width != width || next.height != height) {
            next.recycle()
            return
        }
        val previous = frame
        frame = next
        invalidateSelf()
        previous.recycle()
    }

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
