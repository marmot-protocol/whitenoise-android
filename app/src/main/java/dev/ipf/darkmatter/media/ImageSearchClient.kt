package dev.ipf.darkmatter.media

import dev.ipf.darkmatter.core.HostSafety
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Maximum manual-redirect hops the image-search client will follow before
 *  bailing. Matches `AvatarImageLoader`'s cap. */
private const val MAX_IMAGE_SEARCH_REDIRECT_HOPS = 5

// Upper bound on a search response body. The vqd-token HTML page and the JSON
// results are tens of KB; 4 MiB is generous headroom. A hostile or MITM'd
// endpoint could otherwise stream an unbounded body and exhaust memory. See #144.
private const val MAX_IMAGE_SEARCH_BODY_BYTES = 4 * 1024 * 1024

/**
 * Single source of truth for "is this raw string an avatar-safe HTTPS URL".
 *
 * Returns the normalized form on success, null on rejection. Rules:
 *  - non-blank after trim
 *  - scheme `https` (with a `//host/path` shorthand auto-upgraded to https)
 *  - host non-empty and NOT a private / loopback address (defense in depth
 *    against SSRF via a crafted result that resolves to RFC-1918 / 127/8)
 *
 * Shared between the search client (filtering decoded results) and the
 * sheet's live preview avatar so the two cannot drift on policy.
 */
fun sanitizeHttpsAvatarUrl(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    var candidate = raw.trim()
    if (candidate.startsWith("//")) candidate = "https:$candidate"
    val parsed = runCatching { URI(candidate) }.getOrNull() ?: return null
    val scheme = parsed.scheme?.lowercase() ?: return null
    if (scheme != "https") return null
    val host = parsed.host ?: return null
    if (host.isBlank()) return null
    // Reject embedded credentials: `user@` can mask the real authority and the
    // userinfo can leak to the host.
    if (!parsed.rawUserInfo.isNullOrEmpty()) return null
    if (HostSafety.isPrivateOrLoopbackHost(host)) return null
    return candidate
}

/**
 * Search handshake fetches carry the user's query and the DDG vqd token. They
 * must stay on DuckDuckGo-controlled hosts; arbitrary public HTTPS redirects
 * are only acceptable for the final result image/thumbnail URLs.
 */
internal fun sanitizeDuckDuckGoFetchUrl(raw: String?): String? {
    val safe = sanitizeHttpsAvatarUrl(raw) ?: return null
    val parsed = runCatching { URI(safe) }.getOrNull() ?: return null
    if (!isDuckDuckGoFetchHost(parsed.host)) return null
    return safe
}

private fun isDuckDuckGoFetchHost(host: String?): Boolean {
    val normalized =
        host
            ?.trim()
            ?.removeSuffix(".")
            ?.lowercase()
            .orEmpty()
    return normalized == "duckduckgo.com" || normalized.endsWith(".duckduckgo.com")
}

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

/** Search-time failure shape. Each variant maps to a distinct toast string. */
sealed class ImageSearchException(
    message: String,
) : Exception(message) {
    class EmptyQuery : ImageSearchException("empty query")

    class MissingToken : ImageSearchException("missing token")

    class BadResponse : ImageSearchException("bad response")
}

/**
 * Provides image search results for a free-text query.
 *
 * Implementations MUST self-dispatch any blocking network work to an IO
 * dispatcher; callers may invoke `search` from a Main-bound coroutine
 * without remembering to hop themselves. The Compose UI relies on this
 * contract to keep the search spinner from janking.
 */
interface ImageSearchClient {
    suspend fun search(query: String): List<ImageSearchResult>
}

/**
 * DuckDuckGo image-search client. Two-step handshake:
 *  1. `GET https://duckduckgo.com/?q=...&iax=images&ia=images` — scrape a
 *     `vqd` token out of the returned HTML.
 *  2. `GET https://duckduckgo.com/i.js?l=us-en&o=json&q=...&vqd=...&p=1` —
 *     returns a JSON list of image results.
 *
 * No new dependency: `HttpURLConnection` + `org.json` (both in the Android
 * SDK). Networking style follows the existing `AvatarImageLoader`.
 */
class DuckDuckGoImageSearchClient(
    private val timeoutMillis: Int = 12_000,
) : ImageSearchClient {
    /**
     * Suspends on the IO dispatcher across all blocking network work so
     * callers can invoke this from a Main-dispatched coroutine without
     * remembering to hop themselves — getting the dispatcher wrong here
     * would jank the search bar's progress spinner.
     */
    override suspend fun search(query: String): List<ImageSearchResult> =
        withContext(Dispatchers.IO) {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) throw ImageSearchException.EmptyQuery()

            val landingUrl = buildLandingUrl(trimmed)
            val landingHtml = httpGetString(landingUrl) ?: throw ImageSearchException.BadResponse()
            val token = extractVqdToken(landingHtml) ?: throw ImageSearchException.MissingToken()

            val apiUrl = buildApiUrl(trimmed, token)
            val apiBody = httpGetString(apiUrl) ?: throw ImageSearchException.BadResponse()
            runCatching { decodeResults(apiBody) }.getOrElse { throw ImageSearchException.BadResponse() }
        }

    /**
     * Manual-redirect GET that re-validates HTTPS, host safety, and the
     * DuckDuckGo host pin at EVERY hop. `HttpURLConnection.instanceFollowRedirects = true`
     * would silently follow an https→http downgrade or a DDG→third-party hop
     * (leaking the user's query/token), so we hop ourselves with a bounded
     * counter and a per-hop validator.
     */
    private fun httpGetString(initial: URL): String? {
        var currentSpec = initial.toString()
        var hops = 0
        while (true) {
            val safeSpec = sanitizeDuckDuckGoFetchUrl(currentSpec) ?: return null
            val parsed = runCatching { URL(safeSpec) }.getOrNull() ?: return null
            // Re-validate the authority on the URL we actually open: URI (used by
            // the sanitizer) and URL can parse the authority differently, so guard
            // the host we connect to rather than trusting the URI's view.
            val host = parsed.host ?: return null
            if (!parsed.userInfo.isNullOrEmpty()) return null
            if (host.isBlank() || HostSafety.isPrivateOrLoopbackHost(host) || !isDuckDuckGoFetchHost(host)) {
                return null
            }
            // Resolve-time check closes the DNS-rebinding gap the literal-host check leaves open.
            val resolved = runCatching { InetAddress.getAllByName(host) }.getOrNull()
            if (resolved.isNullOrEmpty() || resolved.any { HostSafety.isPrivateOrLoopbackAddress(it) }) {
                return null
            }
            val connection = (parsed.openConnection() as? HttpURLConnection) ?: return null
            try {
                connection.connectTimeout = timeoutMillis
                connection.readTimeout = timeoutMillis
                connection.instanceFollowRedirects = false
                connection.requestMethod = "GET"
                // Browser-shaped headers — without these DuckDuckGo's HTML
                // landing page short-circuits to an empty body and the vqd
                // token can't be extracted.
                connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                connection.setRequestProperty("Accept", "application/json,text/html;q=0.9,*/*;q=0.8")
                val code = connection.responseCode
                when {
                    code in 300..399 -> {
                        if (hops >= MAX_IMAGE_SEARCH_REDIRECT_HOPS) return null
                        val location = connection.getHeaderField("Location") ?: return null
                        currentSpec = runCatching { URL(parsed, location).toString() }.getOrNull() ?: return null
                        hops += 1
                        // Loop continues; the next iteration re-validates the
                        // post-redirect URL with the same sanitizer used for
                        // the initial request.
                    }
                    code !in 200..299 -> return null
                    else -> {
                        if (connection.contentLengthLong > MAX_IMAGE_SEARCH_BODY_BYTES) return null
                        return connection.inputStream.use { readBounded(it, MAX_IMAGE_SEARCH_BODY_BYTES) }
                    }
                }
            } catch (_: IOException) {
                return null
            } finally {
                connection.disconnect()
            }
        }
    }

    /**
     * Read at most [limit] bytes from [input] and decode as UTF-8, or null if
     * the stream exceeds the cap (declared Content-Length can lie, so the read
     * loop enforces the bound regardless). See #144.
     */
    private fun readBounded(
        input: java.io.InputStream,
        limit: Int,
    ): String? {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            if (total > limit) return null
            output.write(buffer, 0, read)
        }
        return output.toByteArray().toString(StandardCharsets.UTF_8)
    }

    private fun decodeResults(body: String): List<ImageSearchResult> {
        val json = JSONObject(body)
        val results = json.optJSONArray("results") ?: return emptyList()
        val seen = mutableSetOf<String>()
        val out = mutableListOf<ImageSearchResult>()
        for (i in 0 until results.length()) {
            val raw = results.optJSONObject(i) ?: continue
            val image = sanitizeHttpsAvatarUrl(raw.optString("image", "")) ?: continue
            if (!seen.add(image)) continue
            val thumbnail = sanitizeHttpsAvatarUrl(raw.optString("thumbnail", ""))
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

    /** Three regex shapes — DuckDuckGo varies the HTML format across response
     *  paths, and missing a shape would silently kill the feature the day they
     *  ship a template change. */
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
}
