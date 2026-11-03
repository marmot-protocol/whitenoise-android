@file:Suppress("MagicNumber") // Fixed PCM units, renderer clocks and Player protocol constants.

package dev.ipf.whitenoise.android.media.sandbox

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView

/** Surface ownership stays in the parent. The decoder sees selected bytes and raw output memory only. */
internal class RawVideoOutput(
    private val onAvailable: () -> Unit = {},
) : AutoCloseable {
    private val lock = Any()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var output: Any? = null
    private var surface: Surface? = null
    private var ownedSurface = false
    private var textureIdentity: SurfaceTexture? = null
    private var holder: SurfaceHolder? = null
    private var texture: TextureView? = null
    private val holderCallback =
        object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = updateHolder(holder)

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int,
            ) = updateHolder(holder)

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                synchronized(lock) { if (this@RawVideoOutput.holder === holder) surface = null }
            }
        }
    private val textureCallback =
        object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                value: SurfaceTexture,
                width: Int,
                height: Int,
            ) = updateTexture(value)

            override fun onSurfaceTextureSizeChanged(
                value: SurfaceTexture,
                width: Int,
                height: Int,
            ) = Unit

            override fun onSurfaceTextureUpdated(value: SurfaceTexture) = Unit

            override fun onSurfaceTextureDestroyed(value: SurfaceTexture): Boolean {
                synchronized(lock) { if (textureIdentity === value) releaseSurface() }
                return true
            }
        }

    /**
     * Detaches the previous display target and installs callbacks for the replacement.
     * Direct/holder surfaces remain borrowed; only wrappers created for textures are owned here.
     */
    fun attach(next: Any?) {
        close()
        synchronized(lock) {
            output = next
            when (next) {
                null -> Unit
                is Surface -> surface = next
                is SurfaceView -> attachHolder(next.holder)
                is SurfaceHolder -> attachHolder(next)
                is TextureView -> {
                    texture = next
                    next.surfaceTextureListener = textureCallback
                    if (next.isAvailable) next.surfaceTexture?.let(::updateTexture)
                }
                else -> error("Unsupported raw video output")
            }
        }
    }

    /**
     * Borrows a holder surface and registers lifecycle updates for that exact holder.
     */
    private fun attachHolder(next: SurfaceHolder) {
        holder = next
        next.addCallback(holderCallback)
        updateHolder(next)
    }

    /**
     * Refreshes a current holder under the output lock and signals an available valid surface.
     */
    private fun updateHolder(next: SurfaceHolder) {
        synchronized(lock) {
            if (holder === next) surface = next.surface.takeIf { it.isValid }
            if (surface != null) onAvailable()
        }
    }

    /**
     * Rejects stale texture callbacks and owns at most one wrapper for the current texture.
     */
    private fun updateTexture(value: SurfaceTexture) {
        synchronized(lock) {
            if (texture?.surfaceTexture !== value) return
            if (textureIdentity === value && surface?.isValid == true) {
                onAvailable()
                return
            }
            releaseSurface()
            surface = Surface(value)
            ownedSurface = true
            textureIdentity = value
            onAvailable()
        }
    }

    /** The raw bitmap and rotation have already passed the parent protocol validator. */
    fun render(
        bitmap: Bitmap,
        rotation: Int,
        observation: RawAvPlaybackObservation? = null,
    ): Boolean =
        synchronized(lock) {
            require(rotation in setOf(0, 90, 180, 270))
            val target = surface?.takeIf { it.isValid } ?: return false
            try {
                val start = if (observation == null) 0L else android.os.SystemClock.elapsedRealtimeNanos()
                val canvas = target.lockCanvas(null)
                val acquired = if (observation == null) 0L else android.os.SystemClock.elapsedRealtimeNanos()
                var drawn = 0L
                try {
                    drawRawVideoFrame(canvas, bitmap, rotation, paint)
                    if (observation != null) drawn = android.os.SystemClock.elapsedRealtimeNanos()
                } finally {
                    target.unlockCanvasAndPost(canvas)
                }
                observation?.canvas(start, acquired, drawn, android.os.SystemClock.elapsedRealtimeNanos())
                true
            } catch (_: Surface.OutOfResourcesException) {
                false
            } catch (_: IllegalArgumentException) {
                false
            } catch (_: IllegalStateException) {
                false
            }
        }

    /**
     * Detaches only if [expected] is null or is the exact current target, rejecting stale clears.
     */
    fun clear(expected: Any?) {
        synchronized(lock) {
            if (expected != null && output !== expected) return
            close()
        }
    }

    /**
     * Releases only a texture wrapper owned here and forgets borrowed surface references.
     */
    private fun releaseSurface() {
        if (ownedSurface) surface?.release()
        surface = null
        ownedSurface = false
        textureIdentity = null
    }

    /**
     * Attempts to replace the current displayed frame with black; tolerates platform surface loss.
     */
    private fun clearFrame() {
        val target = surface?.takeIf { it.isValid } ?: return
        try {
            val canvas = target.lockCanvas(null)
            try {
                canvas.drawColor(Color.BLACK)
            } finally {
                target.unlockCanvasAndPost(canvas)
            }
        } catch (_: Surface.OutOfResourcesException) {
            // A lost display surface has no reusable frame.
        } catch (_: IllegalArgumentException) {
            // Surface teardown may race platform destruction.
        } catch (_: IllegalStateException) {
            // Surface teardown may race platform destruction.
        }
    }

    /**
     * Clears the frame, removes owned callbacks and releases texture wrappers under the output lock.
     * Borrowed display surfaces and decoder bitmaps remain caller-owned.
     */
    override fun close() {
        synchronized(lock) {
            clearFrame()
            holder?.removeCallback(holderCallback)
            holder = null
            texture?.let { if (it.surfaceTextureListener === textureCallback) it.surfaceTextureListener = null }
            texture = null
            output = null
            releaseSurface()
        }
    }
}

/** Draws validated raw pixels in square-pixel display geometry; surface lifetime stays with the caller. */
internal fun drawRawVideoFrame(
    canvas: Canvas,
    bitmap: Bitmap,
    rotation: Int,
    paint: Paint,
) {
    canvas.drawColor(Color.BLACK)
    val rotated = rotation == 90 || rotation == 270
    val width = if (rotated) bitmap.height else bitmap.width
    val height = if (rotated) bitmap.width else bitmap.height
    val scale = minOf(canvas.width.toFloat() / width, canvas.height.toFloat() / height)
    val checkpoint = canvas.save()
    try {
        canvas.translate(canvas.width / 2f, canvas.height / 2f)
        canvas.rotate(rotation.toFloat())
        canvas.scale(scale, scale)
        canvas.translate(-bitmap.width / 2f, -bitmap.height / 2f)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
    } finally {
        canvas.restoreToCount(checkpoint)
    }
}
