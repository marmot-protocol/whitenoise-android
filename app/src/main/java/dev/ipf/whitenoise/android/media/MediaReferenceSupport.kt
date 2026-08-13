package dev.ipf.whitenoise.android.media

import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.marmotkit.parseMediaImetaTag

/**
 * Android-only media helpers.
 *
 * MarmotKit owns the encrypted-media wire format and its validation. Android
 * only asks MarmotKit to parse tags for optimistic/legacy records that do not
 * yet have an authoritative [dev.ipf.marmotkit.TimelineMessageRecordFfi.media]
 * projection.
 */
object MediaReferenceSupport {
    private const val TAG_NAME = "imeta"

    /**
     * Parse optimistic/compatibility tags through MarmotKit. Projected timeline
     * rows must consume their typed `media` list directly, including when it is
     * empty (an empty projection is authoritative).
     */
    fun parseAllImetaTags(
        tags: List<MessageTagFfi>,
        sourceEpoch: ULong,
    ): List<MediaAttachmentReferenceFfi> =
        tags.mapNotNull { tag ->
            if (tag.values.firstOrNull() != TAG_NAME) return@mapNotNull null
            // Invalid tags are intentionally omitted. This also keeps local JVM
            // tests deterministic: they do not load Android's ABI-specific
            // native library, while runtime builds package it normally.
            runCatching { parseMediaImetaTag(tag, sourceEpoch) }.getOrNull()
        }

    fun parseImetaTag(
        tags: List<MessageTagFfi>,
        sourceEpoch: ULong,
    ): MediaAttachmentReferenceFfi? = parseAllImetaTags(tags, sourceEpoch).firstOrNull()

    fun isImageMedia(ref: MediaAttachmentReferenceFfi): Boolean = ref.mediaType.startsWith("image/", ignoreCase = true)

    fun isAudioMedia(ref: MediaAttachmentReferenceFfi): Boolean = ref.mediaType.startsWith("audio/", ignoreCase = true)

    fun isVideoMedia(ref: MediaAttachmentReferenceFfi): Boolean = ref.mediaType.startsWith("video/", ignoreCase = true)
}
