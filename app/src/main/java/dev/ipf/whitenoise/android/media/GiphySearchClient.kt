package dev.ipf.whitenoise.android.media

import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.core.RemoteGiphyMedia
import dev.ipf.whitenoise.android.core.SafeHttpsGet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import kotlin.coroutines.resume

private const val GIPHY_API_HOST = "api.giphy.com"
private const val MAX_RESULTS = 24
private const val MAX_RESPONSE_BYTES = 1024 * 1024
internal const val GIPHY_MAX_MEDIA_BYTES = 5 * 1024 * 1024
internal const val GIPHY_QUERY_MAX_LENGTH = 50

/** The compact picker preview and separately selected message rendition of one result. */
internal data class GiphySearchResult(
    val title: String?,
    val preview: RemoteGiphyMedia,
    val media: RemoteGiphyMedia,
)

/** Direct, user-initiated search. The key and query never follow redirects. */
internal class GiphySearchClient(
    private val apiKey: String,
) {
    /** Returns null for a failed request or malformed response, and an empty list for no usable results. */
    suspend fun search(
        query: String,
        language: String,
    ): List<GiphySearchResult>? =
        withContext(Dispatchers.IO) {
            val url = giphySearchUrl(query, apiKey, language) ?: return@withContext emptyList()
            val bytes =
                suspendCancellableCoroutine { continuation ->
                    val response =
                        SafeHttpsGet.get(
                            url = url,
                            maxBodyBytes = MAX_RESPONSE_BYTES,
                            connectTimeoutMillis = 5_000,
                            readTimeoutMillis = 8_000,
                            requestHeaders = mapOf("Accept" to "application/json"),
                            maxRedirectHops = 0,
                            hostAllowed = { it.host.equals(GIPHY_API_HOST, ignoreCase = true) },
                            contentTypeAllowed = { it?.substringBefore(';')?.trim().equals("application/json", true) },
                            registerCancellation = { cancel ->
                                continuation.invokeOnCancellation { cancel() }
                            },
                        )
                    continuation.resume(response)
                }
            bytes?.let { decodeGiphySearchResults(it.decodeToString()) }
        }
}

/** Builds a URL containing only GIPHY's search inputs, with no account identifiers. */
internal fun giphySearchUrl(
    query: String,
    apiKey: String,
    language: String,
): String? {
    val trimmed = query.trim()
    val length = trimmed.codePointCount(0, trimmed.length)
    if (apiKey.isBlank() || length !in 1..GIPHY_QUERY_MAX_LENGTH) return null
    val parameters =
        mapOf(
            "api_key" to apiKey,
            "q" to trimmed,
            "limit" to MAX_RESULTS.toString(),
            "rating" to "pg-13",
            "lang" to language.ifBlank { "en" },
        )
    val encoded = parameters.entries.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }
    return "https://$GIPHY_API_HOST/v1/gifs/search?$encoded"
}

/** Selects compatible GIF renditions while retaining the provider's ordering and safe creator attribution. */
internal fun decodeGiphySearchResults(body: String): List<GiphySearchResult>? {
    val data =
        try {
            JSONObject(body).getJSONArray("data")
        } catch (_: JSONException) {
            return null
        }
    return (0 until minOf(data.length(), MAX_RESULTS))
        .mapNotNull { data.optJSONObject(it)?.let(::giphyResult) }
        .distinctBy { it.media.url }
}

/** Rejects a result when no rendition fits the existing GIPHY bubble's media limits. */
private fun giphyResult(item: JSONObject): GiphySearchResult? {
    val images = item.optJSONObject("images") ?: return null
    val original = images.optJSONObject("original")
    val originalFrames = original?.optString("frames")?.toIntOrNull()
    val url =
        sequenceOf("original", "downsized_medium", "downsized", "fixed_height", "fixed_width", "fixed_width_small")
            .mapNotNull { images.optJSONObject(it)?.let { rendition -> boundedGiphyRendition(rendition, originalFrames) } }
            .firstOrNull() ?: return null
    val preview =
        sequenceOf("fixed_width_small", "fixed_width")
            .mapNotNull { images.optJSONObject(it)?.let { rendition -> boundedGiphyRendition(rendition, originalFrames) } }
            .firstOrNull() ?: url
    val user = item.optJSONObject("user")
    val attribution =
        sequenceOf(
            user?.opt("display_name") as? String,
            user?.opt("username") as? String,
            item.opt("username") as? String,
        ).mapNotNull { ProfileSanitizer.displayName(it)?.trimEnd() }.firstOrNull()
    return GiphySearchResult(
        title = ProfileSanitizer.displayName(item.opt("title") as? String),
        preview = RemoteGiphyMedia(preview, null),
        media = RemoteGiphyMedia(url, attribution),
    )
}

/** Checks provider metadata against the same size and animation budgets as the local decoder. */
private fun boundedGiphyRendition(
    rendition: JSONObject,
    originalFrames: Int?,
): String? {
    val url = rendition.optString("url", "")
    if (!RemoteGiphyMedia.isAllowedMediaUrl(url)) return null
    val path = URI(url).path.orEmpty().lowercase(Locale.ROOT)
    val width = rendition.optInt("width")
    val height = rendition.optInt("height")
    val frames = rendition.optInt("frames", originalFrames ?: 1)
    val pixels = width.toLong() * height
    val fitsCanvas =
        width in 1..MAX_ANIMATION_SOURCE_EDGE_PX &&
            height in 1..MAX_ANIMATION_SOURCE_EDGE_PX &&
            pixels <= MAX_ANIMATION_SOURCE_CANVAS_PIXELS
    val fitsWork = frames in 1..MAX_ANIMATION_SOURCE_FRAMES && pixels * frames <= MAX_ANIMATION_SOURCE_WORK_PIXELS
    return url.takeIf {
        val fitsMedia = path.endsWith(".gif") && rendition.optInt("size") in 1..GIPHY_MAX_MEDIA_BYTES
        fitsMedia && fitsCanvas && fitsWork
    }
}
