package dev.ipf.whitenoise.android.core

import java.net.URI
import java.util.Locale

/** A bounded GIPHY media envelope emitted by White Noise on iOS. */
@Suppress("ReturnCount") // Trust-boundary parsing fails closed at each rejected envelope or URL property.
data class RemoteGiphyMedia(
    val url: String,
    val attribution: String?,
) {
    /** MIME hint and fetch URL; legacy MP4 renditions map to the same CDN GIF rendition used by iOS playback. */
    fun imageRequest(): ImageRequest? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val extension = uri.path?.substringAfterLast('.', missingDelimiterValue = "")?.lowercase(Locale.ROOT)
        val requestUrl =
            if (extension == "mp4") {
                val gifPath = uri.rawPath.dropLast(3) + "gif"
                URI(uri.scheme, uri.rawAuthority, gifPath, uri.rawQuery, uri.rawFragment).toASCIIString()
            } else {
                url
            }
        if (!isAllowedMediaUrl(requestUrl)) return null
        return ImageRequest(
            url = requestUrl,
            mediaType = if (extension == "webp") "image/webp" else "image/gif",
        )
    }

    /** Bounded decoder inputs derived entirely from the validated envelope URL. */
    data class ImageRequest(
        val url: String,
        val mediaType: String,
    )

    companion object {
        const val MAX_WIRE_TEXT_LENGTH = 2304
        const val MAX_MEDIA_URL_LENGTH = 2048
        const val MAX_ATTRIBUTION_LENGTH = 80
        private const val ATTRIBUTION_PREFIX = "via GIPHY"
        private const val CREATOR_SEPARATOR = " · "
        private const val HTTPS_PORT = 443
        private val numberedMediaHost = Regex("media\\d+\\.giphy\\.com", RegexOption.IGNORE_CASE)
        private val allowedExtensions = setOf("gif", "webp", "mp4")

        /** Parses the exact two-line text envelope used by the iOS GIPHY picker. */
        fun parse(raw: String): RemoteGiphyMedia? {
            if (raw.length > MAX_WIRE_TEXT_LENGTH) return null
            val newline = raw.indexOf('\n')
            if (newline <= 0 || raw.indexOf('\n', newline + 1) != -1) return null
            val url = raw.substring(0, newline)
            val attributionLine = raw.substring(newline + 1)
            val attribution =
                when {
                    attributionLine == ATTRIBUTION_PREFIX -> null
                    attributionLine.startsWith(ATTRIBUTION_PREFIX + CREATOR_SEPARATOR) ->
                        attributionLine.removePrefix(ATTRIBUTION_PREFIX + CREATOR_SEPARATOR)
                    else -> return null
                }
            if (attribution != null && !isExactAttribution(attribution)) return null
            if (!isAllowedMediaUrl(url)) return null
            return RemoteGiphyMedia(url = url, attribution = attribution)
        }

        /** Detects the bounded GIPHY envelope shape for compact previews without leaking its CDN URL. */
        fun isEnvelopeText(raw: String?): Boolean {
            if (raw == null || raw.length > MAX_WIRE_TEXT_LENGTH) return false
            val newline = raw.indexOf('\n')
            if (newline <= 0) return false
            val tail = raw.substring(newline + 1)
            val attributionShaped = tail.startsWith(ATTRIBUTION_PREFIX) || ATTRIBUTION_PREFIX.startsWith(tail)
            return attributionShaped && isAllowedGiphyAuthority(raw.substring(0, newline).trim())
        }

        /** Restricts media loads, including redirects, to approved GIPHY CDN URLs and formats. */
        fun isAllowedMediaUrl(raw: String): Boolean {
            if (raw.length > MAX_MEDIA_URL_LENGTH || raw.any { it == '\r' || it == '\n' }) return false
            val uri = runCatching { URI(raw) }.getOrNull() ?: return false
            if (!hasAllowedAuthority(uri)) return false
            val extension = uri.path?.substringAfterLast('.', missingDelimiterValue = "")?.lowercase(Locale.ROOT)
            return extension in allowedExtensions
        }

        /** URL-only host predicate used at every redirect hop by [SafeHttpsGet]. */
        fun isAllowedMediaHost(host: String?): Boolean {
            val normalized = host?.lowercase(Locale.ROOT) ?: return false
            return normalized == "media.giphy.com" ||
                normalized == "i.giphy.com" ||
                numberedMediaHost.matches(normalized)
        }

        private fun isAllowedGiphyAuthority(raw: String): Boolean {
            if (raw.length > MAX_MEDIA_URL_LENGTH) return false
            val uri = runCatching { URI(raw) }.getOrNull() ?: return false
            return hasAllowedAuthority(uri)
        }

        private fun hasAllowedAuthority(uri: URI): Boolean =
            uri.scheme.equals("https", ignoreCase = true) &&
                uri.host != null &&
                uri.rawUserInfo.isNullOrEmpty() &&
                (uri.port == -1 || uri.port == HTTPS_PORT) &&
                isAllowedMediaHost(uri.host)

        private fun isExactAttribution(raw: String): Boolean =
            raw.codePointCount(0, raw.length) <= MAX_ATTRIBUTION_LENGTH &&
                ProfileSanitizer.displayName(raw) == raw
    }
}
