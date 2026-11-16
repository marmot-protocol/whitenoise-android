package dev.ipf.whitenoise.android.ui.conversation.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.SystemClock
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.ipf.whitenoise.android.core.RemoteGiphyMedia
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.media.decodeOwnedMedia
import dev.ipf.whitenoise.android.media.sandbox.IsolatedAnimationDrawable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

private const val PREVIEW_EDGE_PX = 160
private const val PREVIEW_MAX_FRAMES = 10
private const val PREVIEW_MIN_FRAME_MS = 80L
private const val PREVIEW_MAX_FRAME_MS = 500L
private const val PREVIEW_CAPTURE_MS = 1_500L

internal data class GiphyPreviewFrame(
    val image: ImageBitmap,
    val durationMs: Long,
)

/** A short loop of validated, decoded pixels. The tile owns these frames independently of the worker. */
internal class GiphyPreview(
    val frames: List<GiphyPreviewFrame>,
) {
    /** Releases the tile's pixels when it leaves the viewport or the query changes. */
    fun release() {
        frames.forEach { it.image.asAndroidBitmap().recycle() }
    }
}

/** Limits in-flight downloads and serializes brief captures on the isolated animation worker. */
internal class GiphyPreviewLoader {
    private val downloads = Semaphore(2)
    private val captures = Semaphore(1)

    /** Captures the actual send rendition and releases the decoder before displaying the loop. */
    suspend fun load(media: RemoteGiphyMedia): GiphyPreview? =
        downloads.withPermit {
            val request = media.imageRequest() ?: return@withPermit null
            val bytes = fetchRemoteGiphyBytes(request) ?: return@withPermit null
            captures.withPermit {
                val drawable =
                    decodeOwnedMedia(
                        decode = { MediaPipeline.decodeAnimatedDrawable(bytes, PREVIEW_EDGE_PX) },
                        discard = { (it as? java.io.Closeable)?.close() },
                    )
                try {
                    (drawable as? IsolatedAnimationDrawable)?.let { captureGiphyPreview(it) }
                } finally {
                    (drawable as? java.io.Closeable)?.close()
                }
            }
        }
}

private data class CapturedFrame(
    val image: ImageBitmap,
    val receivedAtMs: Long,
)

/** Samples worker-delivered frames on the drawing thread. A still or stalled source cannot enable Send. */
internal suspend fun captureGiphyPreview(drawable: IsolatedAnimationDrawable): GiphyPreview? {
    val frames = mutableListOf<CapturedFrame>()
    val updates = Channel<Unit>(Channel.CONFLATED)
    var handedOff = false
    drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
    drawable.callback = GiphyPreviewFrameCallback(updates)
    try {
        frames += drawable.captureFrame()
        var frameNumber = drawable.presentedFrame
        drawable.start()
        withTimeoutOrNull(PREVIEW_CAPTURE_MS) {
            while (frames.size < PREVIEW_MAX_FRAMES) {
                updates.receive()
                val earliestSample = frames.last().receivedAtMs + PREVIEW_MIN_FRAME_MS
                delay((earliestSample - SystemClock.uptimeMillis()).coerceAtLeast(0L))
                val latestFrame = drawable.presentedFrame
                if (latestFrame != frameNumber) {
                    frames += drawable.captureFrame()
                    frameNumber = latestFrame
                }
            }
        }
        if (frames.size < 2) return null
        return GiphyPreview(frames.toPreviewFrames()).also { handedOff = true }
    } finally {
        drawable.callback = null
        updates.close()
        if (!handedOff) frames.forEach { it.image.asAndroidBitmap().recycle() }
    }
}

/** Copies a validated frame at the worker's bounded output size. */
private fun IsolatedAnimationDrawable.captureFrame(): CapturedFrame {
    val pixels = Bitmap.createBitmap(intrinsicWidth, intrinsicHeight, Bitmap.Config.ARGB_8888)
    draw(Canvas(pixels))
    return CapturedFrame(pixels.asImageBitmap(), SystemClock.uptimeMillis())
}

/** Replays the sampled timing and gives the last frame the preceding frame's display time. */
private fun List<CapturedFrame>.toPreviewFrames(): List<GiphyPreviewFrame> {
    val intervals =
        zipWithNext { frame, next ->
            (next.receivedAtMs - frame.receivedAtMs).coerceIn(PREVIEW_MIN_FRAME_MS, PREVIEW_MAX_FRAME_MS)
        }
    return mapIndexed { index, frame ->
        GiphyPreviewFrame(frame.image, intervals.getOrElse(index) { intervals.last() })
    }
}

/** Receives invalidations from the isolated frame stream, which schedules its own playback. */
private class GiphyPreviewFrameCallback(
    private val updates: Channel<Unit>,
) : Drawable.Callback {
    /** Wakes the sampler after the worker replaces its last frame. */
    override fun invalidateDrawable(who: Drawable) {
        updates.trySend(Unit)
    }

    /** The isolated animation session owns the frame clock. */
    override fun scheduleDrawable(
        who: Drawable,
        what: Runnable,
        `when`: Long,
    ) = Unit

    /** The sampler registers no callbacks with a host scheduler. */
    override fun unscheduleDrawable(
        who: Drawable,
        what: Runnable,
    ) = Unit
}
