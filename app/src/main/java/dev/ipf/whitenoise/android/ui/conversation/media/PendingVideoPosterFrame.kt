package dev.ipf.whitenoise.android.ui.conversation.media

import android.graphics.Bitmap
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
 * already retained in memory for the retry path. Passing them through an unlinked read-only descriptor
 * avoids a second full-video array: staging uses an unlinked temporary inode, with no surviving
 * plaintext pathname to restore or include in backups. The frame is scaled to the same bubble thumbnail edge the
 * confirmed poster uses, so a 4K clip cannot hold a ~33 MB bitmap per visible bubble.
 *
 * Call this off the main thread. Returns an empty frame when the bytes carry no decodable video.
 */
internal fun pendingVideoPosterFrame(
    bytes: ByteArray,
    extractPoster: Boolean,
    maxEdgePx: Int = MediaPipeline.THUMBNAIL_MAX_EDGE_PX,
): PendingVideoPosterFrame {
    val result =
        dev.ipf.whitenoise.android.media.sandbox.IsolatedMediaDecoder
            .probeBytes(bytes, maxEdgePx, extractPoster)
    return PendingVideoPosterFrame(result?.poster, result?.durationMs ?: 0L)
}
