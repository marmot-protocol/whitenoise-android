package dev.ipf.whitenoise.android.media.sandbox

import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor

/** Platform extractors see only the selected descriptor, never a path or network URL. */
internal fun probeMediaFromDescriptor(
    input: ParcelFileDescriptor,
    edge: Int,
    poster: Boolean,
    offset: Long = 0L,
    length: Long = input.statSize,
): MediaProbe? =
    MediaMetadataRetriever().use { retriever ->
        retriever.setDataSource(input.fileDescriptor, offset, length)
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val bitmap =
            if (poster && MediaDecodePolicy.sourceAllowed(width, height)) {
                val scale = minOf(1.0, edge.toDouble() / maxOf(width, height))
                retriever.getScaledFrameAtTime(
                    0L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    (width * scale).toInt().coerceAtLeast(1),
                    (height * scale).toInt().coerceAtLeast(1),
                )
            } else {
                null
            }
        MediaProbe(width, height, duration.coerceIn(0, MAX_MEDIA_DURATION_MS), bitmap)
    }

internal const val MAX_MEDIA_DURATION_MS = 24L * 60 * 60 * 1000
internal const val MAX_MEDIA_SOURCE_BYTES = 512L * 1024 * 1024

internal fun validMediaRange(
    size: Long,
    offset: Long,
    length: Long,
): Boolean = offset >= 0L && length > 0L && length <= size && offset <= size - length
