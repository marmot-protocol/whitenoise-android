package dev.ipf.whitenoise.android.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.Locale

/**
 * The single SSRF-hardened HTTPS GET used by every outbound fetch in the app
 * (NIP-05 resolution, avatar loading, image search). Manual redirect handling
 * re-validates the destination at EVERY hop, because
 * `HttpURLConnection.instanceFollowRedirects = true` would silently follow an
 * `https`→`http` downgrade or a redirect to a private/loopback host.
 *
 * Each hop must satisfy, in order:
 *  - scheme is `https`;
 *  - no embedded credentials (`user:pass@host` can mask the real authority and
 *    leak userinfo to the host);
 *  - the port is the implicit default (`-1`) or an explicit `443` — an explicit
 *    non-standard port is an authority trick and a way to reach an unintended
 *    internal service;
 *  - the host is not a private/loopback literal ([HostSafety.isPrivateOrLoopbackHost]);
 *  - the caller's [hostAllowed] predicate accepts the URL (e.g. a host pin);
 *  - resolve-time DNS-rebinding check: no resolved address is internal.
 *
 * The body is bounded regardless of the declared `Content-Length` (which can
 * lie). Returns the raw bytes, or null on any downgrade, disallowed hop,
 * non-2xx, oversize body, or IO error. Callers decode (UTF-8 / bitmap).
 */
object SafeHttpsGet {
    const val DEFAULT_MAX_REDIRECT_HOPS = 5

    private val SENSITIVE_REDIRECT_HEADERS =
        setOf(
            "authorization",
            "cookie",
            "proxy-authorization",
        )

    fun get(
        url: String,
        maxBodyBytes: Int,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        requestHeaders: Map<String, String> = emptyMap(),
        maxRedirectHops: Int = DEFAULT_MAX_REDIRECT_HOPS,
        hostAllowed: (URL) -> Boolean = { true },
    ): ByteArray? {
        var currentSpec = url
        var hops = 0
        while (true) {
            val parsed = runCatching { URL(currentSpec) }.getOrNull() ?: return null
            if (parsed.protocol?.lowercase(Locale.ROOT) != "https") return null
            val host = parsed.host
            if (host.isNullOrBlank()) return null
            if (!parsed.userInfo.isNullOrEmpty()) return null
            if (parsed.port != -1 && parsed.port != 443) return null
            if (HostSafety.isPrivateOrLoopbackHost(host)) return null
            if (!hostAllowed(parsed)) return null
            val resolved = runCatching { InetAddress.getAllByName(host) }.getOrNull()
            if (resolved.isNullOrEmpty() || resolved.any { HostSafety.isPrivateOrLoopbackAddress(it) }) {
                return null
            }

            val connection = (parsed.openConnection() as? HttpURLConnection) ?: return null
            try {
                connection.connectTimeout = connectTimeoutMillis
                connection.readTimeout = readTimeoutMillis
                connection.instanceFollowRedirects = false
                connection.requestMethod = "GET"
                headersForHop(requestHeaders, original = URL(url), current = parsed).forEach { (name, value) ->
                    connection.setRequestProperty(name, value)
                }
                val code = connection.responseCode
                when {
                    code in 300..399 -> {
                        if (hops >= maxRedirectHops) return null
                        val location = connection.getHeaderField("Location") ?: return null
                        currentSpec = runCatching { URL(parsed, location).toString() }.getOrNull() ?: return null
                        hops += 1
                        // Loop re-validates the post-redirect URL with the same
                        // scheme + host-safety checks as the initial request.
                    }
                    code !in 200..299 -> return null
                    else -> {
                        if (connection.contentLengthLong > maxBodyBytes) return null
                        return connection.inputStream.use { readBounded(it, maxBodyBytes) }
                    }
                }
            } catch (_: IOException) {
                return null
            } finally {
                connection.disconnect()
            }
        }
    }

    internal fun headersForHop(
        requestHeaders: Map<String, String>,
        original: URL,
        current: URL,
    ): Map<String, String> {
        if (sameOrigin(original, current)) return requestHeaders
        return requestHeaders.filterKeys { it.lowercase(Locale.ROOT) !in SENSITIVE_REDIRECT_HEADERS }
    }

    private fun sameOrigin(
        first: URL,
        second: URL,
    ): Boolean =
        first.protocol.equals(second.protocol, ignoreCase = true) &&
            first.host.equals(second.host, ignoreCase = true) &&
            effectivePort(first) == effectivePort(second)

    private fun effectivePort(url: URL): Int =
        when {
            url.port != -1 -> url.port
            url.protocol.equals("https", ignoreCase = true) -> 443
            url.protocol.equals("http", ignoreCase = true) -> 80
            else -> url.defaultPort
        }

    /** [get] decoded as UTF-8, or null on any failure or oversize body. */
    fun getUtf8(
        url: String,
        maxBodyBytes: Int,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        requestHeaders: Map<String, String> = emptyMap(),
        maxRedirectHops: Int = DEFAULT_MAX_REDIRECT_HOPS,
        hostAllowed: (URL) -> Boolean = { true },
    ): String? =
        get(
            url = url,
            maxBodyBytes = maxBodyBytes,
            connectTimeoutMillis = connectTimeoutMillis,
            readTimeoutMillis = readTimeoutMillis,
            requestHeaders = requestHeaders,
            maxRedirectHops = maxRedirectHops,
            hostAllowed = hostAllowed,
        )?.toString(Charsets.UTF_8)

    private fun readBounded(
        input: InputStream,
        limit: Int,
    ): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            if (total > limit) return null
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}
