package dev.ipf.whitenoise.android.ui.conversation.nostr

import dev.ipf.whitenoise.android.core.HostSafety
import dev.ipf.whitenoise.android.core.nostr.NostrEvent
import java.net.URI
import java.util.Locale

internal data class NostrEventVideoMetadata(
    val url: String,
    val mimeType: String?,
    val dimensions: String?,
    val duration: String?,
)

/** The first author-ordered playable video variant. NIP-71 weights variants equally. */
internal fun NostrEvent.videoMetadata(): NostrEventVideoMetadata? =
    tags
        .asSequence()
        .filter { it.firstOrNull() == "imeta" }
        .map(::imetaProperties)
        .mapNotNull { properties ->
            val mimeType = properties["m"]?.firstOrNull()?.safeField()?.lowercase(Locale.ROOT)
            val url = properties["url"]?.firstOrNull()?.let(::safeNostrMediaUrl)
            if (url == null || !mimeType.isPlayableVideoMimeType()) {
                null
            } else {
                NostrEventVideoMetadata(
                    url = url,
                    mimeType = mimeType,
                    dimensions = properties["dim"]?.firstOrNull()?.safeField(),
                    duration = properties["duration"]?.firstOrNull()?.safeField(),
                )
            }
        }.firstOrNull()

private fun String?.isPlayableVideoMimeType(): Boolean {
    if (this == null) return true
    return startsWith("video/") || this in PLAYLIST_VIDEO_MIME_TYPES
}

private fun imetaProperties(tag: List<String>): Map<String, List<String>> =
    buildMap {
        tag.drop(1).forEach { field ->
            val separator = field.indexOf(' ')
            if (separator <= 0 || separator == field.lastIndex) return@forEach
            val name = field.substring(0, separator).lowercase(Locale.ROOT)
            val value = field.substring(separator + 1).trim()
            if (value.isNotEmpty()) put(name, get(name).orEmpty() + value)
        }
    }

/** Initial and redirected playback requests enforce the same public-HTTPS policy at dial time. */
internal fun safeNostrMediaUrl(raw: String?): String? {
    val candidate = raw?.trim().orEmpty()
    val uri = runCatching { URI(candidate) }.getOrNull()
    if (candidate.isEmpty() || uri == null) return null
    val usesSafeEndpoint =
        when {
            !uri.scheme.equals("https", ignoreCase = true) -> false
            uri.host.isNullOrBlank() -> false
            !uri.rawUserInfo.isNullOrEmpty() -> false
            uri.port != -1 && uri.port != HTTPS_PORT -> false
            else -> !HostSafety.isPrivateOrLoopbackHost(uri.host)
        }
    return candidate.takeIf { usesSafeEndpoint }
}

private const val HTTPS_PORT = 443
private val PLAYLIST_VIDEO_MIME_TYPES =
    setOf(
        "application/mpegurl",
        "application/vnd.apple.mpegurl",
        "application/x-mpegurl",
    )
