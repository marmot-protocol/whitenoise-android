package dev.ipf.whitenoise.android.media.sandbox

import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor

/** Historical debug feasibility experiment only; absent from release source. */
internal fun probeHistoricalMediaFromDescriptor(
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
