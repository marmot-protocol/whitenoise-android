package dev.ipf.darkmatter.media

import dev.ipf.darkmatter.core.HostSafety
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * One image-search hit. URLs are already validated as `https://` non-private
 * hosts at this point; the source-host and dimensions labels are display-only
 * and may be null when the upstream payload omits them.
 */
data class ImageSearchResult(
    val imageUrl: String,
    val thumbnailUrl: String?,
    val sourceHost: String?,
    val dimensionsLabel: String?,
    val title: String,
)

/** Search-time failure shape. Matches iOS L10n copy keys 1:1. */
sealed class ImageSearchException(
    message: String,
) : Exception(message) {
    class EmptyQuery : ImageSearchException("empty query")

    class MissingToken : ImageSearchException("missing token")

    class BadResponse : ImageSearchException("bad response")
}

interface ImageSearchClient {
    suspend fun search(query: String): List<ImageSearchResult>
}

/**
 * Port of the iOS `DuckDuckGoImageSearchClient`. Two-step handshake:
 *  1. `GET https://duckduckgo.com/?q=...&iax=images&ia=images` — scrape a
 *     `vqd` token out of the returned HTML.
 *  2. `GET https://duckduckgo.com/i.js?l=us-en&o=json&q=...&vqd=...&p=1` —
 *     returns a JSON list of image results.
 *
 * No new dependency: `HttpURLConnection` + `org.json` (both in the Android
 * SDK). Mirrors the existing `AvatarImageLoader` networking style.
 */
class DuckDuckGoImageSearchClient(
    private val timeoutMillis: Int = 12_000,
) : ImageSearchClient {
    override suspend fun search(query: String): List<ImageSearchResult> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) throw ImageSearchException.EmptyQuery()

        val landingUrl = buildLandingUrl(trimmed)
        val landingHtml = httpGetString(landingUrl) ?: throw ImageSearchException.BadResponse()
        val token = extractVqdToken(landingHtml) ?: throw ImageSearchException.MissingToken()

        val apiUrl = buildApiUrl(trimmed, token)
        val apiBody = httpGetString(apiUrl) ?: throw ImageSearchException.BadResponse()
        return runCatching { decodeResults(apiBody) }.getOrElse { throw ImageSearchException.BadResponse() }
    }

    private fun httpGetString(url: URL): String? {
        val connection = (url.openConnection() as? HttpURLConnection) ?: return null
        return try {
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.instanceFollowRedirects = true
            connection.requestMethod = "GET"
            // Browser-shaped headers — without these DuckDuckGo's HTML landing
            // page short-circuits to an empty body and the vqd token can't be
            // extracted. Mirrors the iOS client's `User-Agent: Mozilla/5.0`.
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            connection.setRequestProperty("Accept", "application/json,text/html;q=0.9,*/*;q=0.8")
            val code = connection.responseCode
            if (code !in 200..299) return null
            connection.inputStream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
        } catch (_: IOException) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun decodeResults(body: String): List<ImageSearchResult> {
        val json = JSONObject(body)
        val results = json.optJSONArray("results") ?: return emptyList()
        val seen = mutableSetOf<String>()
        val out = mutableListOf<ImageSearchResult>()
        for (i in 0 until results.length()) {
            val raw = results.optJSONObject(i) ?: continue
            val image = sanitizeImageUrl(raw.optString("image", "")) ?: continue
            if (!seen.add(image)) continue
            val thumbnail = sanitizeImageUrl(raw.optString("thumbnail", ""))
            val sourcePage = raw.optString("url", "").takeIf { it.isNotBlank() }
            out +=
                ImageSearchResult(
                    imageUrl = image,
                    thumbnailUrl = thumbnail,
                    sourceHost = sourceHostFor(sourcePage ?: image),
                    dimensionsLabel = dimensionsLabel(raw.optInt("width", 0), raw.optInt("height", 0)),
                    title = raw.optString("title", "").trim(),
                )
        }
        return out
    }

    private fun sourceHostFor(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return runCatching { URI(raw.trim()).host }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun dimensionsLabel(
        width: Int,
        height: Int,
    ): String? {
        if (width <= 0 || height <= 0) return null
        return "${width}x$height"
    }

    private fun buildLandingUrl(query: String): URL {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return URL("https://duckduckgo.com/?q=$encoded&iax=images&ia=images")
    }

    private fun buildApiUrl(
        query: String,
        token: String,
    ): URL {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val encodedToken = URLEncoder.encode(token, "UTF-8")
        return URL(
            "https://duckduckgo.com/i.js?l=us-en&o=json&q=$encodedQuery&vqd=$encodedToken&p=1",
        )
    }

    /** Three regex shapes mirroring iOS — DuckDuckGo varies the HTML format
     *  across response paths, and missing a shape would silently kill the
     *  feature the day they ship a template change. */
    private fun extractVqdToken(html: String): String? {
        val patterns =
            listOf(
                Regex("""vqd\s*[:=]\s*['"]([^'"]+)['"]"""),
                Regex(""""vqd"\s*:\s*"([^"]+)""""),
                Regex("""vqd=([^&"'\\]+)"""),
            )
        for (pattern in patterns) {
            val match = pattern.find(html) ?: continue
            val token =
                match.groupValues
                    .getOrNull(1)
                    ?.trim()
                    .orEmpty()
            if (token.isNotEmpty()) return token.replace("&amp;", "&")
        }
        return null
    }

    /** HTTPS-only filter + schemeless `//host/path` upgrade (DuckDuckGo
     *  returns these for some image sources). Defense-in-depth via
     *  `HostSafety.isPrivateOrLoopbackHost` so a stray result can't point
     *  the avatar fetch at a local-network address. */
    private fun sanitizeImageUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var candidate = raw.trim()
        if (candidate.startsWith("//")) candidate = "https:$candidate"
        val parsed = runCatching { URI(candidate) }.getOrNull() ?: return null
        val scheme = parsed.scheme?.lowercase() ?: return null
        if (scheme != "https") return null
        val host = parsed.host ?: return null
        if (host.isBlank()) return null
        if (HostSafety.isPrivateOrLoopbackHost(host)) return null
        return candidate
    }
}
