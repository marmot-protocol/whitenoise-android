package dev.ipf.whitenoise.android.media.sandbox

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.PorterDuff
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.SharedMemory
import android.os.SystemClock
import dev.ipf.whitenoise.android.media.AnimationSourceAdmission
import dev.ipf.whitenoise.android.media.MAX_ANIMATION_SOURCE_CANVAS_PIXELS
import dev.ipf.whitenoise.android.media.MAX_ANIMATION_SOURCE_EDGE_PX
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.media.admitAnimationSource
import java.nio.ByteBuffer

/** Compressed bytes and the platform animation decoder never leave this permissionless process. */
internal class WorkerAnimation(
    bytes: ByteArray,
    edge: Int,
    private val output: SharedMemory,
    private val handler: Handler,
    private val publish: (Long, Int, Int) -> Unit,
    private val nativeWork: (() -> Unit) -> Unit,
    private val viewerOutput: ViewerAnimationOutput? = null,
) : Drawable.Callback {
    private val drawable: AnimatedImageDrawable
    private val bitmap: Bitmap
    private val canvas: Canvas
    private var playing = false
    private var drawing = false
    private var awaitingAck = false
    private var sequence = 0L
    private var lastFrameAt = 0L
    private var nextStep: Runnable? = null
    private var nextAt = 0L
    private val drawStep = Runnable { drawFrame() }

    init {
        require(bytes.size in 1..MediaDecodePolicy.MAX_INPUT_BYTES)
        val admission = admitAnimationSource(bytes) as? AnimationSourceAdmission.Admitted
        require(admission != null)
        if (viewerOutput == null) {
            require(edge in 1..AnimationFrameProtocol.MAX_EDGE)
        } else {
            require(viewerOutput == ViewerAnimationOutput.fromAdmission(admission))
            require(edge == maxOf(viewerOutput.width, viewerOutput.height))
            require(output.size == viewerOutput.capacityBytes)
        }
        drawable =
            requireNotNull(
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                    require(info.size.width in 1..MAX_ANIMATION_SOURCE_EDGE_PX)
                    require(info.size.height in 1..MAX_ANIMATION_SOURCE_EDGE_PX)
                    require(info.size.width.toLong() * info.size.height <= MAX_ANIMATION_SOURCE_CANVAS_PIXELS)
                    require(info.size.width == admission.canvasWidth && info.size.height == admission.canvasHeight)
                    val target =
                        if (viewerOutput == null) {
                            MediaPipeline.targetDimensions(info.size.width, info.size.height, edge)
                        } else {
                            viewerOutput.width to viewerOutput.height
                        }
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setTargetSize(target.first, target.second)
                } as? AnimatedImageDrawable,
            )
        val capacity =
            MediaDecodePolicy.pixelBytes(
                drawable.intrinsicWidth,
                drawable.intrinsicHeight,
                edge,
                output.size,
            )
        require(capacity != null)
        drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
        drawable.callback = this
        drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
        bitmap = Bitmap.createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
        canvas = Canvas(bitmap)
    }

    fun firstFrame() = drawFrame()

    fun play() {
        if (playing) return
        playing = true
        nativeWork { drawable.start() }
        if (!awaitingAck) queueDraw()
    }

    fun pause() {
        playing = false
        handler.removeCallbacksAndMessages(this)
        nextStep = null
        nativeWork { drawable.stop() }
    }

    fun acknowledge(value: Long) {
        if (!awaitingAck || value != sequence) return
        awaitingAck = false
        if (playing) {
            if (nextStep == null) queueDraw() else scheduleNext()
        }
    }

    override fun invalidateDrawable(who: Drawable) {
        if (playing && !awaitingAck) queueDraw()
    }

    override fun scheduleDrawable(
        who: Drawable,
        what: Runnable,
        `when`: Long,
    ) {
        nextStep = what
        nextAt = `when`
        if (playing && !awaitingAck && !drawing) scheduleNext()
    }

    override fun unscheduleDrawable(
        who: Drawable,
        what: Runnable,
    ) {
        handler.removeCallbacks(what, this)
        if (nextStep === what) nextStep = null
    }

    private fun scheduleNext() {
        val next = nextStep ?: return
        nextStep = null
        val at = maxOf(nextAt, lastFrameAt + AnimationFrameProtocol.MIN_FRAME_DELAY_MS)
        handler.postAtTime(next, this, at)
    }

    private fun queueDraw() {
        handler.removeCallbacks(drawStep)
        val at = maxOf(SystemClock.uptimeMillis(), lastFrameAt + AnimationFrameProtocol.MIN_FRAME_DELAY_MS)
        handler.postAtTime(drawStep, this, at)
    }

    private fun drawFrame() {
        if (awaitingAck) return
        nativeWork {
            drawing = true
            try {
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                drawable.draw(canvas)
                val buffer = output.mapReadWrite()
                try {
                    buffer.limit(bitmap.byteCount)
                    bitmap.copyPixelsToBuffer(buffer)
                } finally {
                    SharedMemory.unmap(buffer)
                }
                lastFrameAt = SystemClock.uptimeMillis()
                sequence++
                awaitingAck = true
                publish(sequence, bitmap.width, bitmap.height)
            } finally {
                drawing = false
            }
        }
    }
}
