package dev.ipf.whitenoise.android.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.util.Locale

internal object Lud16Address {
    private val ADDRESS = Regex("^([^@\\s]+)@([^@\\s]+)$")
    private val LOCAL_PART = Regex("^[a-zA-Z0-9._-]+$")
    private val DOMAIN_LABEL = Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")

    data class Parsed(
        val local: String,
        val domain: String,
    )

    fun parse(raw: String): Parsed? {
        val match = ADDRESS.matchEntire(raw.trim()) ?: return null
        val local = match.groupValues[1]
        val domain = match.groupValues[2].lowercase(Locale.ROOT)
        if (!LOCAL_PART.matches(local)) return null
        // The local part becomes a path segment; bare dot segments would
        // traverse out of /.well-known/lnurlp/.
        if (local == "." || local == "..") return null
        if (!isHostnameOnlyDomain(domain)) return null
        return Parsed(local = local, domain = domain)
    }

    private fun isHostnameOnlyDomain(domain: String): Boolean {
        if (domain.length > 253) return false
        val labels = domain.split('.')
        if (labels.size < 2) return false
        return labels.all { label ->
            label.isNotEmpty() && DOMAIN_LABEL.matches(label)
        }
    }
}

/**
 * Verifies a lud16 Lightning address (`<name>@<domain>`) by resolving its
 * LNURL-pay endpoint, `https://<domain>/.well-known/lnurlp/<name>`, per LUD-16.
 * The address only counts as valid when that endpoint answers with a
 * well-formed `payRequest` document.
 *
 * Security posture mirrors [Nip05Resolver]: the domain comes from untrusted,
 * user-typed input, so this is an SSRF vector. The fetch goes through the
 * shared [SafeHttpsGet] (HTTPS-only, default port, per-hop redirect
 * revalidation against private/loopback hosts, bounded body), and obviously
 * private/loopback domains and explicit ports are rejected before any request
 * is issued.
 *
 * Per issue #795 the domain is lowercased while the local part's case is
 * preserved; blank input is the caller's business (an empty field means "no
 * Lightning address" and skips resolution entirely).
 */
object Lud16Resolver {
    // An LNURL-pay descriptor is a small JSON object; 1 MiB is generous headroom.
    private const val MAX_BODY_BYTES = 1 * 1024 * 1024

    // Bounded so a dead domain fails the save in a few seconds, not forever.
    private const val DEFAULT_TIMEOUT_MILLIS = 8_000

    /**
     * Resolve [address] (`<name>@<domain>`) against its LNURL-pay endpoint.
     * Returns true only when the endpoint exists and serves a valid
     * `payRequest` document; false on malformed input, unsafe host, network
     * failure, non-2xx, or a malformed response. Suspends on the IO dispatcher
     * across all network work.
     */
    suspend fun resolve(
        address: String,
        timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val url = wellKnownUrl(address) ?: return@withContext false
            val body =
                SafeHttpsGet.getUtf8(
                    url = url,
                    maxBodyBytes = MAX_BODY_BYTES,
                    connectTimeoutMillis = timeoutMillis,
                    readTimeoutMillis = timeoutMillis,
                    requestHeaders = mapOf("Accept" to "application/json"),
                ) ?: return@withContext false
            isPayRequest(body)
        }

    /**
     * The LNURL-pay well-known URL for [address], or null when the address is
     * malformed or targets a host we refuse to contact (private/loopback,
     * explicit port). Pure — no network.
     */
    internal fun wellKnownUrl(address: String): String? {
        val parsed = Lud16Address.parse(address) ?: return null
        if (HostSafety.isPrivateOrLoopbackHost(parsed.domain)) return null
        return "https://${parsed.domain}/.well-known/lnurlp/${parsed.local}"
    }

    /**
     * True when [body] is a valid LNURL-pay `payRequest` descriptor:
     * `tag == "payRequest"`, `callback` a well-formed HTTPS URL, and numeric
     * `minSendable`/`maxSendable` with 0 < min <= max. Pure — no network.
     */
    internal fun isPayRequest(body: String): Boolean {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return false
        if (json.optString("tag") != "payRequest") return false
        val callback = runCatching { URL(json.optString("callback")) }.getOrNull() ?: return false
        if (!callback.protocol.equals("https", ignoreCase = true) || callback.host.isNullOrBlank()) return false
        // `as? Number` (rather than optLong) so a JSON string like "1000"
        // doesn't get coerced into passing the "numeric" requirement.
        val min = (json.opt("minSendable") as? Number)?.toLong() ?: return false
        val max = (json.opt("maxSendable") as? Number)?.toLong() ?: return false
        return min > 0 && min <= max
    }
}
