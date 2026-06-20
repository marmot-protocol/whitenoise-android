package dev.ipf.darkmatter.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Resolves a NIP-05 internet identifier (`<local>@<domain>`) to a Nostr public
 * key (64-char lowercase hex) by fetching the domain's
 * `/.well-known/nostr.json?name=<local>` document, per NIP-05.
 *
 * Security posture mirrors [dev.ipf.darkmatter.media.ImageSearchClient]: the
 * domain comes from untrusted, user-pasted input, so this is an SSRF vector.
 * We therefore:
 *  - force HTTPS (a NIP-05 well-known doc is always HTTPS),
 *  - reject private / loopback / link-local hosts via [HostSafety] at the
 *    initial host AND at every redirect hop,
 *  - follow redirects manually with a bounded hop count (the platform's
 *    auto-follow would silently chase an https→http downgrade or a
 *    public→private hop),
 *  - bound the response body so a hostile endpoint can't exhaust memory,
 *  - dispatch all blocking network work to [Dispatchers.IO] so callers can
 *    invoke from a Main-bound coroutine without janking.
 *
 * Only the local part is validated against the response and only an exact,
 * case-insensitive name match yields a key. A successful resolve returns the
 * lowercase hex pubkey; every failure (malformed input, network error, missing
 * or malformed document, no matching name, non-hex value) returns null.
 */
object Nip05Resolver {
    // <local>@<domain>, no whitespace or extra '@'. Same shape as
    // ProfileFieldValidation.INTERNET_IDENTIFIER; duplicated locally so the
    // resolver is self-contained and can split the two halves it needs.
    private val INTERNET_IDENTIFIER = Regex("^([^@\\s]+)@([^@\\s]+\\.[^@\\s]+)$")
    private val HEX_PUBKEY = Regex("^[0-9a-fA-F]{64}$")

    // NIP-05 local parts are restricted to a-z, 0-9, '-', '_', '.'. We accept
    // case-insensitively and lowercase before querying, matching how clients
    // canonicalise the name. A local part outside this set can't be a NIP-05
    // identifier, so we reject rather than send it upstream.
    private val LOCAL_PART = Regex("^[a-z0-9._-]+$")

    private const val MAX_REDIRECT_HOPS = 5

    // A well-known nostr.json is a small JSON map; 1 MiB is generous headroom.
    private const val MAX_BODY_BYTES = 1 * 1024 * 1024

    private const val DEFAULT_TIMEOUT_MILLIS = 12_000

    /**
     * Resolve [identifier] (`<local>@<domain>`) to a lowercase hex pubkey, or
     * null on any failure. Suspends on the IO dispatcher across all network
     * work.
     */
    suspend fun resolve(
        identifier: String,
        timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    ): String? =
        withContext(Dispatchers.IO) {
            val match = INTERNET_IDENTIFIER.matchEntire(identifier.trim()) ?: return@withContext null
            val local = match.groupValues[1].lowercase(Locale.ROOT)
            val domain = match.groupValues[2].lowercase(Locale.ROOT)
            if (!LOCAL_PART.matches(local)) return@withContext null
            // Block obviously-private domains before issuing any request. A bare
            // hostname (the common case) passes this literal check and a DNS
            // rebinding attack is out of scope here, matching HostSafety's
            // documented contract.
            if (HostSafety.isPrivateOrLoopbackHost(domain)) return@withContext null

            val url = buildUrl(local, domain) ?: return@withContext null
            val body = httpGetString(url, timeoutMillis) ?: return@withContext null
            decodePubkey(body, local)
        }

    private fun buildUrl(
        local: String,
        domain: String,
    ): URL? {
        val encodedName = URLEncoder.encode(local, "UTF-8")
        // The local part is already constrained to [a-z0-9._-] so this is a
        // plain query string; the domain is interpolated into the authority and
        // re-validated as a real host inside httpGetString.
        return runCatching { URL("https://$domain/.well-known/nostr.json?name=$encodedName") }.getOrNull()
    }

    /**
     * Manual-redirect GET that re-validates HTTPS and host safety at EVERY hop.
     * Returns the decoded body (UTF-8) or null on any non-2xx, oversize body,
     * downgrade, private-host hop, or IO error.
     */
    private fun httpGetString(
        initial: URL,
        timeoutMillis: Int,
    ): String? {
        var currentSpec = initial.toString()
        var hops = 0
        while (true) {
            val parsed = runCatching { URL(currentSpec) }.getOrNull() ?: return null
            if (parsed.protocol?.lowercase(Locale.ROOT) != "https") return null
            val host = parsed.host ?: return null
            if (!parsed.userInfo.isNullOrEmpty()) return null
            if (host.isBlank() || HostSafety.isPrivateOrLoopbackHost(host)) return null

            val connection = (parsed.openConnection() as? HttpURLConnection) ?: return null
            try {
                connection.connectTimeout = timeoutMillis
                connection.readTimeout = timeoutMillis
                connection.instanceFollowRedirects = false
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/json")
                val code = connection.responseCode
                when {
                    code in 300..399 -> {
                        if (hops >= MAX_REDIRECT_HOPS) return null
                        val location = connection.getHeaderField("Location") ?: return null
                        currentSpec = runCatching { URL(parsed, location).toString() }.getOrNull() ?: return null
                        hops += 1
                        // Loop re-validates the post-redirect URL with the same
                        // HTTPS + host-safety checks as the initial request.
                    }
                    code !in 200..299 -> return null
                    else -> {
                        if (connection.contentLengthLong > MAX_BODY_BYTES) return null
                        return connection.inputStream.use { readBounded(it, MAX_BODY_BYTES) }
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
     * Read at most [limit] bytes and decode as UTF-8, or null if the stream
     * exceeds the cap (a declared Content-Length can lie, so the read loop
     * enforces the bound regardless).
     */
    private fun readBounded(
        input: InputStream,
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

    /**
     * Pull the hex pubkey for [local] out of a nostr.json body. The document
     * shape is `{"names": {"<name>": "<hexpubkey>", ...}}`; we match the name
     * case-insensitively (NIP-05 names are case-insensitive) and accept only a
     * 64-char hex value, returned lowercase.
     */
    private fun decodePubkey(
        body: String,
        local: String,
    ): String? {
        val names = runCatching { JSONObject(body).optJSONObject("names") }.getOrNull() ?: return null
        // Exact key first (the common case), then a case-insensitive scan since
        // a server may have published the name with different casing.
        val direct = names.optString(local, "")
        if (HEX_PUBKEY.matches(direct)) return direct.lowercase(Locale.ROOT)
        val keys = names.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (!key.equals(local, ignoreCase = true)) continue
            val value = names.optString(key, "")
            if (HEX_PUBKEY.matches(value)) return value.lowercase(Locale.ROOT)
        }
        return null
    }
}
