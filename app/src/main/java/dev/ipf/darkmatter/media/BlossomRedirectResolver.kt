package dev.ipf.darkmatter.media

import android.util.Log
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

// TODO(darkmatter#413): delete once the runtime follows per-hop validated
// redirects itself. Today its HTTP client refuses 30x so a Blossom canonical
// host that redirects to a CDN (e.g. blossom.primal.net → r2a.primal.net)
// breaks every receive-side download.
internal object BlossomRedirectResolver {
    private const val TAG = "BlossomRedirectResolver"
    private const val MAX_HOPS = 5
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 15_000

    fun resolve(initialUrl: String): String? {
        var current = runCatching { URI(initialUrl) }.getOrNull() ?: return null
        if (!isAllowed(current)) return null
        var followed = false
        for (hop in 0 until MAX_HOPS) {
            val (status, location) = runCatching { probeOnce(current) }.getOrElse { return null }
            when (status) {
                in 200..299 -> return if (followed) current.toString() else null
                in 300..399 -> {
                    val raw = location ?: return null
                    val next = runCatching { current.resolve(raw) }.getOrNull() ?: return null
                    if (!isAllowed(next)) {
                        // Log host only — the path / query may carry signed tokens.
                        Log.w(TAG, "rejected redirect to host=${next.host} (failed SSRF check)")
                        return null
                    }
                    current = next
                    followed = true
                }
                else -> return null
            }
        }
        Log.w(TAG, "redirect chain exceeded $MAX_HOPS hops")
        return null
    }

    // GET (not HEAD): primal.net answers HEAD with 200 from the canonical host
    // and only emits the 30x on GET. Disconnect before reading the body.
    private fun probeOnce(uri: URI): Pair<Int, String?> {
        val conn =
            (URL(uri.toString()).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                useCaches = false
            }
        try {
            conn.connect()
            val status = conn.responseCode
            val location = conn.getHeaderField("Location")
            return status to location
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    // Per-hop SSRF check. Delegate to the project-shared `HostSafety` which
    // already covers all IPv4/IPv6 private + loopback + link-local ranges,
    // non-dotted IPv4 encodings, and loopback hostnames — so the same
    // guarantees the avatar / profile pipeline get apply here too.
    private fun isAllowed(uri: URI): Boolean {
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host ?: return false
        if (host.isEmpty()) return false
        return !dev.ipf.darkmatter.core.HostSafety
            .isPrivateOrLoopbackHost(host)
    }
}
