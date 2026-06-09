package dev.ipf.darkmatter.core

/**
 * First-layer SSRF guard for URLs that arrive from untrusted protocol data —
 * relay hints, imeta media URLs, and profile avatar URLs. Classifies a literal
 * host as private / loopback / link-local purely from the host string (no DNS),
 * so it is cheap and safe to call on any thread, including composition.
 *
 * It deliberately does NOT defend against DNS-rebinding (a public hostname that
 * resolves to a private address) — that needs a resolve-time check on the IO
 * dispatcher. Blocking literal private addresses is the high-value, low-cost
 * layer; the resolve-time check can be layered on later without changing this
 * contract.
 */
object HostSafety {
    /**
     * True when [host] is an IP literal in a private/loopback/link-local range,
     * a loopback hostname (`localhost` / `*.localhost`), or unparseable/blank.
     * Ordinary public hostnames and public IP literals return false.
     *
     * [host] must be a hostname or IP only, with NO port — pass `URI.getHost()`
     * (or an already-port-stripped authority), never `host:port`. A trailing
     * `:port` would be misread as part of an IPv6 literal.
     */
    fun isPrivateOrLoopbackHost(host: String?): Boolean {
        val normalized =
            host
                ?.trim()
                ?.removeSurrounding("[", "]")
                ?.lowercase()
                .orEmpty()
        if (normalized.isEmpty()) return true
        if (normalized == "localhost" || normalized.endsWith(".localhost")) return true
        if (normalized.contains(':')) return isPrivateIpv6(normalized)
        val ipv4 = parseIpv4(normalized)
        if (ipv4 != null) return isPrivateIpv4(ipv4)
        // An ordinary hostname (not an IP literal) — let it through; a
        // resolve-time guard is the right place to catch DNS rebinding.
        return false
    }

    private fun parseIpv4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (i in 0 until 4) {
            val n = parts[i].toIntOrNull() ?: return null
            if (n !in 0..255) return null
            octets[i] = n
        }
        return octets
    }

    private fun isPrivateIpv4(octets: IntArray): Boolean {
        val a = octets[0]
        val b = octets[1]
        return when {
            a == 0 -> true // 0.0.0.0/8 "this network"
            a == 127 -> true // loopback
            a == 10 -> true // RFC 1918
            a == 172 && b in 16..31 -> true // RFC 1918
            a == 192 && b == 168 -> true // RFC 1918
            a == 169 && b == 254 -> true // link-local
            a == 100 && b in 64..127 -> true // RFC 6598 carrier-grade NAT
            else -> false
        }
    }

    private fun isPrivateIpv6(host: String): Boolean {
        val address = host.substringBefore('%') // drop any zone id
        if (address == "::1" || address == "::") return true
        // IPv4-mapped / -embedded form, e.g. ::ffff:192.168.0.1 — follow the
        // embedded IPv4 address.
        if (address.contains('.')) {
            val embedded = parseIpv4(address.substringAfterLast(':'))
            if (embedded != null && isPrivateIpv4(embedded)) return true
        }
        return when {
            // fc00::/7 unique-local.
            address.startsWith("fc") || address.startsWith("fd") -> true
            // fe80::/10 link-local.
            address.startsWith("fe8") ||
                address.startsWith("fe9") ||
                address.startsWith("fea") ||
                address.startsWith("feb") -> true
            else -> false
        }
    }
}
