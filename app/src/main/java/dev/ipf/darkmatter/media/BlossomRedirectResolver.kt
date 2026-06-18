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
    // and only emits the 30x on GET, so we must use GET to observe redirects.
    //
    // But a bare GET makes the terminal 2xx host start streaming the (possibly
    // multi-MB, encrypted) blob, which we throw away — the real download then
    // re-fetches it via the Rust FFI, so every resolved blob is fetched twice
    // (darkmatter#226). Send `Range: bytes=0-0` so the terminal host replies
    // 206 with a single byte instead of the whole blob; 30x responses ignore
    // Range and are returned unchanged. This keeps redirect detection intact
    // while making the probe cheap and keeping the connection pool reusable.
    private fun probeOnce(uri: URI): Pair<Int, String?> {
        val conn =
            (URL(uri.toString()).openConnection() as HttpURLConnection).also(::configureProbeConnection)
        try {
            conn.connect()
            val status = conn.responseCode
            val location = conn.getHeaderField("Location")
            return status to location
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    // Visible for testing. Applies the probe request configuration: a
    // non-following GET with a single-byte ranged request so the terminal hop
    // does not stream the full blob (see probeOnce).
    internal fun configureProbeConnection(conn: HttpURLConnection) {
        conn.instanceFollowRedirects = false
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.requestMethod = "GET"
        conn.useCaches = false
        conn.setRequestProperty("Range", "bytes=0-0")
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
