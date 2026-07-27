package dev.ipf.whitenoise.android.media

import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.marmotkit.parseMediaImetaTag
import dev.ipf.whitenoise.android.core.HostSafety
import java.net.InetAddress
import java.net.URI
import java.util.Locale

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
    private const val BLOSSOM_LOCATOR_KIND = "blossom-v1"
    private const val HTTPS_DEFAULT_PORT = 443

    private data class ParsedFetchableLocator(
        val host: String,
        val nativeValue: String,
    )

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

    /**
     * Returns a copy of [reference] whose fetchable locators are rewritten from
     * the authority parsed and validated here before the value crosses into the
     * native downloader. This is Android's defense-in-depth SSRF/DNS-rebinding
     * gate; it is intentionally separate from MarmotKit's wire parser.
     */
    @Suppress("ReturnCount")
    internal fun safeDownloadReference(
        reference: MediaAttachmentReferenceFfi,
        resolve: (String) -> List<InetAddress>?,
    ): MediaAttachmentReferenceFfi? {
        val safeLocators =
            reference.locators.map { locator ->
                if (locator.kind != BLOSSOM_LOCATOR_KIND) return@map locator
                val parsed = parseFetchableLocator(locator.value) ?: return null
                if (unsafeFetchableLocatorHost(parsed, resolve) != null) return null
                locator.copy(value = parsed.nativeValue)
            }
        return reference.copy(locators = safeLocators)
    }

    private fun unsafeFetchableLocatorHost(
        parsed: ParsedFetchableLocator,
        resolve: (String) -> List<InetAddress>?,
    ): String? {
        val unsafe =
            HostSafety.isPrivateOrLoopbackHost(parsed.host) ||
                resolve(parsed.host).let { resolved ->
                    resolved.isNullOrEmpty() || resolved.any { HostSafety.isPrivateOrLoopbackAddress(it) }
                }
        return parsed.host.takeIf { unsafe }
    }

    @Suppress("ReturnCount")
    private fun parseFetchableLocator(raw: String): ParsedFetchableLocator? {
        if (raw.isBlank()) return null
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase(Locale.ROOT) != "https") return null
        if (!uri.rawUserInfo.isNullOrEmpty()) return null
        if (uri.port != -1 && uri.port != HTTPS_DEFAULT_PORT) return null
        val host =
            uri.host
                ?.trim()
                ?.removeSurrounding("[", "]")
                ?.lowercase(Locale.ROOT)
                ?.takeIf { it.isNotBlank() }
                ?: return null
        return ParsedFetchableLocator(host = host, nativeValue = nativeLocatorValue(uri, host))
    }

    private fun nativeLocatorValue(
        uri: URI,
        host: String,
    ): String =
        buildString {
            append("https://")
            append(if (host.contains(':')) "[$host]" else host)
            append(uri.rawPath.orEmpty())
            uri.rawQuery?.let { append('?').append(it) }
            uri.rawFragment?.let { append('#').append(it) }
        }

    fun isImageMedia(ref: MediaAttachmentReferenceFfi): Boolean = ref.mediaType.startsWith("image/", ignoreCase = true)

    fun isAudioMedia(ref: MediaAttachmentReferenceFfi): Boolean = ref.mediaType.startsWith("audio/", ignoreCase = true)

    fun isVideoMedia(ref: MediaAttachmentReferenceFfi): Boolean = ref.mediaType.startsWith("video/", ignoreCase = true)
}
