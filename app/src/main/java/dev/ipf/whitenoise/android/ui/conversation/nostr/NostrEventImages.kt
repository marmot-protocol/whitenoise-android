package dev.ipf.whitenoise.android.ui.conversation.nostr

import dev.ipf.whitenoise.android.core.nostr.NostrEvent
import java.util.Locale

/** Discovers explicit image metadata without fetching it or guessing from arbitrary links. */
internal fun NostrEvent.imageMetadataUrls(): List<String> {
    val candidates = mutableListOf<String>()
    tags.filter { it.firstOrNull() == "imeta" }.take(MAX_IMAGE_TAGS).forEach { tag ->
        val fields = imetaProperties(tag)
        val mime = fields["m"]?.firstOrNull()?.substringBefore(';')?.lowercase(Locale.ROOT)
        if (mime in RASTER_IMAGE_MIMES || (kind == KIND_IMAGE && mime == null)) {
            fields["url"]?.firstOrNull()?.let(candidates::add)
        }
    }
    if (
        kind == KIND_FILE_METADATA &&
        firstTagValue("m")?.substringBefore(';')?.lowercase(Locale.ROOT) in RASTER_IMAGE_MIMES
    ) {
        firstTagValue("url")?.let(candidates::add)
    }
    listOf("image", "icon").forEach { name -> firstTagValue(name)?.let(candidates::add) }
    return candidates
        .asSequence()
        .filter { it.length <= MAX_IMAGE_URL_CHARS }
        .mapNotNull(::safeNostrMediaUrl)
        .distinct()
        .take(MAX_READER_IMAGES)
        .toList()
}

internal const val MAX_READER_IMAGES = 8
private const val MAX_IMAGE_TAGS = 32
private const val MAX_IMAGE_URL_CHARS = 2048
private const val KIND_IMAGE = 20
private const val KIND_FILE_METADATA = 1063
private val RASTER_IMAGE_MIMES = setOf("image/jpeg", "image/png", "image/webp", "image/gif")
