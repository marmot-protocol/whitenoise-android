package dev.ipf.whitenoise.android.ui.conversation.media

import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.state.PendingAttachment

/** One frame and the duration read from a video that has not been sent yet. */
internal data class PendingVideoPosterFrame(
    val bitmap: Bitmap?,
    val durationMs: Long,
)

/** Whether this queued attachment is a video, and so owns a poster rather than a file pill. */
internal val PendingAttachment.isPendingVideo: Boolean
    get() = mediaType.startsWith("video/", ignoreCase = true)

/** Whether this queued attachment belongs in the visual bubble at all — a photo, a GIF or a video. */
internal val PendingAttachment.isPendingVisualMedia: Boolean
    get() = isPendingVideo || mediaType.startsWith("image/", ignoreCase = true)

/**
 * Reads a poster frame and duration from a video that is still queued for upload (#2732).
 *
 * A message that has not been sent has no materialized file to seek in, but the upload bytes are
 * already retained in memory for the retry path. Reading them through an in-memory [MediaDataSource]
 * keeps that the only copy: nothing plaintext is written to disk, so nothing has to be cleaned up
 * or excluded from backup afterwards. The frame is scaled to the same bubble thumbnail edge the
 * confirmed poster uses, so a 4K clip cannot hold a ~33 MB bitmap per visible bubble.
 *
 * Call this off the main thread. Returns an empty frame when the bytes carry no decodable video.
 */
internal fun pendingVideoPosterFrame(
    bytes: ByteArray,
    extractPoster: Boolean,
    maxEdgePx: Int = MediaPipeline.THUMBNAIL_MAX_EDGE_PX,
): PendingVideoPosterFrame {
    if (bytes.isEmpty()) return PendingVideoPosterFrame(null, 0L)
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(ByteArrayMediaDataSource(bytes))
        val bitmap =
            if (extractPoster) {
                retriever.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxEdgePx, maxEdgePx)
            } else {
                null
            }
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        PendingVideoPosterFrame(bitmap, duration)
    } catch (_: RuntimeException) {
        PendingVideoPosterFrame(null, 0L)
    } catch (_: OutOfMemoryError) {
        PendingVideoPosterFrame(null, 0L)
    } finally {
        runCatching { retriever.release() }
    }
}

/** Seekable view of bytes already held in memory, so no plaintext copy is written to read them. */
private class ByteArrayMediaDataSource(
    private val bytes: ByteArray,
) : MediaDataSource() {
    /** Copies at most [size] bytes from [position], or -1 once the source is exhausted. */
    override fun readAt(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        size: Int,
    ): Int {
        val available = (bytes.size - position).coerceIn(0L, size.toLong()).toInt()
        if (available > 0) System.arraycopy(bytes, position.toInt(), buffer, offset, available)
        return if (position >= bytes.size) -1 else available
    }

    /** The retained payload's exact length; the retriever needs it to seek. */
    override fun getSize(): Long = bytes.size.toLong()

    /** Nothing to release: the bytes belong to the pending attachment, not to this view. */
    override fun close() = Unit
}
