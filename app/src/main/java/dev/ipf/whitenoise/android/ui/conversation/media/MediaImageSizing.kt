package dev.ipf.whitenoise.android.ui.conversation.media

/** The shorter pixel side from an imeta `dim` ("WxH"), for the small-source rule. */
internal fun sourceShortSideFromDim(dim: String?): Int? {
    val parts = dim?.split('x', 'X', ignoreCase = true)?.takeIf { it.size == 2 } ?: return null
    val sides = parts.mapNotNull { part -> part.trim().toIntOrNull()?.takeIf { it > 0 } }
    return if (sides.size == 2) sides.min() else null
}

/**
 * Parse the imeta `dim` field ("WxH") into a width/height aspect ratio.
 * Returns null when [dim] is null, blank, malformed, or non-positive on
 * either axis. Caller falls back to [MediaBubbleHeight] in that case.
 */
internal fun aspectRatioFromDim(dim: String?): Float? {
    val parts = dim?.takeIf { it.isNotBlank() }?.split('x', 'X', ignoreCase = true)?.takeIf { it.size == 2 }
    val sides = parts?.mapNotNull { part -> part.trim().toIntOrNull()?.takeIf { it > 0 } }
    return sides?.takeIf { it.size == 2 }?.let { (width, height) -> width.toFloat() / height.toFloat() }
}

/** A GIF keeps the shared rich-content canvas width and the prototype's fixed banner height. */
internal fun isGifAttachmentMediaType(mediaType: String?): Boolean = mediaType.equals("image/gif", ignoreCase = true)

/** Width and height in dp for one photo or video, following the prototype's SingleMediaLayout. */
internal fun singleMediaSizeDp(
    ratio: Float?,
    sourceShortSidePx: Int? = null,
): Pair<Float, Float> {
    if (ratio == null || ratio <= 0f) return SINGLE_MEDIA_MAX_EXTENT_DP to SINGLE_MEDIA_MAX_EXTENT_DP
    var height = SINGLE_MEDIA_MAX_EXTENT_DP
    var width = (height * ratio).coerceAtMost(SINGLE_MEDIA_MAX_EXTENT_DP)
    val destinationShort = minOf(width, height)
    val smallSource = sourceShortSidePx != null && destinationShort > sourceShortSidePx
    if (smallSource && destinationShort > SMALL_SOURCE_DISPLAY_EXTENT_DP) {
        val scale = SMALL_SOURCE_DISPLAY_EXTENT_DP / destinationShort
        width *= scale
        height *= scale
    }
    return width to height
}
