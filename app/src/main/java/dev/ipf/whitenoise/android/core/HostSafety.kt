package dev.ipf.whitenoise.android.core

import java.net.IDN
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.text.Normalizer
import java.util.Locale

/**
 * First-layer SSRF guard for URLs that arrive from untrusted protocol data —
 * relay hints, imeta media URLs, and profile avatar URLs. Classifies a literal
 * host as private / loopback / link-local purely from the host string (no DNS),
 * so it is cheap and safe to call on any thread, including composition.
 *
 * The string check ([isPrivateOrLoopbackHost]) deliberately does NOT defend
 * against DNS-rebinding (a public hostname that resolves to a private address).
 * Callers that issue a real request to a user-controlled host should ALSO call
 * the resolve-time check [isPrivateOrLoopbackAddress] on each resolved
 * [InetAddress] from the IO dispatcher — that closes the public-name →
 * private-IP gap. (A pinned-IP connection would be needed to fully defeat an
 * active mid-connection rebind, which `HttpURLConnection` can't express; the
 * resolve-time check is the high-value layer that blocks the common attack.)
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
                ?.let(::canonicalizeHostLiteral)
                // Drop a single rooting dot first: `127.0.0.1.` and `localhost.`
                // still resolve to loopback, but the trailing empty label would
                // otherwise make the IPv4 decode (5 parts) and the localhost
                // check both miss. See #153.
                ?.removeSuffix(".")
                ?.lowercase(Locale.ROOT)
                .orEmpty()
        if (normalized.isEmpty()) return true
        if (normalized == "localhost" || normalized.endsWith(".localhost")) return true
        if (normalized.contains(':')) return isPrivateIpv6(normalized)
        // inet_aton-style decode: catches the non-dotted-quad encodings the OS
        // resolver still accepts and that a naive 4-part decimal parser would
        // wave through — single decimal (2130706433), hex (0x7f000001), octal
        // (0177.0.0.1), and short forms (127.1). All resolve to 127.0.0.1.
        // See #153.
        val ipv4 = decodeNumericIpv4(normalized)
        if (ipv4 != null) return isPrivateIpv4(ipv4)
        // An ordinary hostname (not an IP literal) — let it through; a
        // resolve-time guard is the right place to catch DNS rebinding.
        return false
    }

    /**
     * Resolve-time SSRF guard: true when a DNS-resolved [address] falls in a
     * private/loopback/link-local range, so a public hostname that resolves to
     * (or is rebound to) an internal IP is rejected. Reuses the same range
     * classification as the literal-host check, applied to the actual resolved
     * bytes.
     *
     * Call this from the IO dispatcher on every [InetAddress] returned for the
     * target host (and on every redirect hop's host) BEFORE opening the
     * connection. This closes the public-name → private-IP gap that
     * [isPrivateOrLoopbackHost] documents it does not cover.
     */
    fun isPrivateOrLoopbackAddress(address: InetAddress): Boolean {
        // The JDK helpers cover loopback (127/8, ::1), link-local (169.254/16,
        // fe80::/10), site-local (RFC-1918, fec0::/10), and the wildcard
        // (0.0.0.0, ::). They miss CGNAT (100.64/10) and unique-local
        // fc00::/7, so fall through to the byte classifier for those.
        if (address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isAnyLocalAddress
        ) {
            return true
        }
        return when (address) {
            is Inet4Address -> isPrivateIpv4(toUnsignedOctets(address.address))
            is Inet6Address -> {
                val bytes = address.address
                // IPv4-mapped (::ffff:a.b.c.d) carries the embedded IPv4 in the
                // final four bytes with the preceding two set to 0xffff; follow
                // it so a mapped private literal is rejected too.
                if (bytes.size == 16 &&
                    (0 until 10).all { bytes[it].toInt() == 0 } &&
                    bytes[10].toInt() and 0xFF == 0xFF &&
                    bytes[11].toInt() and 0xFF == 0xFF
                ) {
                    isPrivateIpv4(toUnsignedOctets(bytes.copyOfRange(12, 16)))
                } else {
                    // fc00::/7 unique-local, documentation/discard-only, and
                    // other special-use ranges not covered by the JDK helpers.
                    isPrivateIpv6Groups(hextets(bytes))
                }
            }
            else -> false
        }
    }

    private fun toUnsignedOctets(raw: ByteArray): IntArray = IntArray(raw.size) { raw[it].toInt() and 0xFF }

    /**
     * Strict 4-part decimal IPv4 (the only shape an IPv4-embedded IPv6 literal
     * carries). Returns null for anything that isn't `d.d.d.d` with each octet
     * a decimal 0..255.
     */
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

    /**
     * Decode an IPv4 host the way the platform resolver (`inet_aton`) does:
     * 1–4 parts separated by dots, each part decimal, octal (leading `0`), or
     * hex (`0x` prefix). A part shorter than the full address absorbs the
     * remaining low-order bytes (so `127.1` → 127.0.0.1, `0x7f000001` →
     * 127.0.0.1). Returns the four octets, or null when [host] is not a wholly
     * numeric IPv4 literal (e.g. a real hostname), in which case the caller
     * treats it as a name, not an address.
     */
    private fun decodeNumericIpv4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.isEmpty() || parts.size > 4) return null
        val values = LongArray(parts.size)
        for (i in parts.indices) {
            values[i] = parseRadixPart(parts[i]) ?: return null
        }
        // Each leading part is exactly one octet; the final part absorbs the
        // remaining low-order bytes for short forms.
        val maxFinal =
            when (parts.size) {
                1 -> 0xFFFFFFFFL
                2 -> 0xFFFFFFL
                3 -> 0xFFFFL
                else -> 0xFFL
            }
        for (i in 0 until parts.size - 1) {
            if (values[i] > 0xFFL) return null
        }
        if (values.last() > maxFinal) return null
        var address = 0L
        for (i in 0 until parts.size - 1) {
            address = address or (values[i] shl (8 * (3 - i)))
        }
        address = address or values.last()
        return intArrayOf(
            ((address shr 24) and 0xFF).toInt(),
            ((address shr 16) and 0xFF).toInt(),
            ((address shr 8) and 0xFF).toInt(),
            (address and 0xFF).toInt(),
        )
    }

    /** A single inet_aton part: hex (`0x`), octal (leading `0`), or decimal. */
    private fun parseRadixPart(part: String): Long? {
        if (part.isEmpty()) return null
        return when {
            part.startsWith("0x") || part.startsWith("0X") ->
                part.substring(2).takeIf { it.isNotEmpty() }?.toLongOrNull(16)
            part.length > 1 && part[0] == '0' ->
                part.toLongOrNull(8)
            else -> part.toLongOrNull(10)
        }?.takeIf { it >= 0 }
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
            a == 192 && b == 0 && octets[2] == 0 -> true // IETF protocol assignments
            a == 192 && b == 88 && octets[2] == 99 -> true // deprecated 6to4 relay anycast
            a == 198 && b in 18..19 -> true // benchmarking
            a == 169 && b == 254 -> true // link-local
            a == 100 && b in 64..127 -> true // RFC 6598 carrier-grade NAT
            a in 224..239 -> true // multicast
            a >= 240 -> true // reserved / future use
            else -> false
        }
    }

    private fun isPrivateIpv6(host: String): Boolean {
        val address = host.substringBefore('%') // drop any zone id
        if (address == "::1" || address == "::") return true
        val groups =
            if (address.contains('.')) {
                // Dotted embedded IPv4, e.g. ::ffff:192.168.0.1 — follow the
                // embedded address.
                val embedded = parseIpv4(address.substringAfterLast(':'))
                if (embedded != null && isPrivateIpv4(embedded)) return true
                embedded?.let { expandIpv6WithEmbeddedIpv4(address, it) }
            } else {
                // Hex-grouped IPv4-mapped (::ffff:7f00:1) and IPv4-compatible
                // (::7f00:1) literals carry the embedded IPv4 in the final two
                // hextets. These reach 127.0.0.1 just like the dotted form —
                // the IPv6 sibling of the #153 non-dotted-encoding bypass.
                val embedded = embeddedIpv4FromHextets(address)
                if (embedded != null && isPrivateIpv4(embedded)) return true
                expandIpv6(address)
            } ?: return false
        return isPrivateIpv6Groups(groups)
    }

    private fun isPrivateIpv6Groups(groups: IntArray): Boolean {
        val first = groups[0]
        val isDiscardOnly = first == 0x0100 && groups[1] == 0 && groups[2] == 0 && groups[3] == 0
        val isDocumentation = first == 0x2001 && groups[1] == 0x0DB8
        // fc00::/7 unique-local, fe80::/10 link-local, fec0::/10 site-local,
        // 100::/64 discard-only, and 2001:db8::/32 documentation.
        return (first and 0xFE00) == 0xFC00 ||
            (first and 0xFFC0) == 0xFE80 ||
            (first and 0xFFC0) == 0xFEC0 ||
            isDiscardOnly ||
            isDocumentation
    }

    private fun hextets(bytes: ByteArray): IntArray =
        IntArray(8) { index ->
            ((bytes[index * 2].toInt() and 0xFF) shl 8) or (bytes[index * 2 + 1].toInt() and 0xFF)
        }

    private fun expandIpv6WithEmbeddedIpv4(
        address: String,
        embedded: IntArray,
    ): IntArray? {
        val lastColon = address.lastIndexOf(':')
        if (lastColon < 0) return null
        val prefix = address.substring(0, lastColon)
        val high = (embedded[0] shl 8) or embedded[1]
        val low = (embedded[2] shl 8) or embedded[3]
        return expandIpv6("$prefix:${high.toString(16)}:${low.toString(16)}")
    }

    private fun canonicalizeHostLiteral(host: String): String {
        if (host.contains(':')) return host
        val normalized =
            Normalizer
                .normalize(host, Normalizer.Form.NFKC)
                .replace('\u3002', '.')
                .replace('\uFF0E', '.')
                .replace('\uFF61', '.')
        return runCatching { IDN.toASCII(normalized, IDN.ALLOW_UNASSIGNED) }.getOrDefault(normalized)
    }

    /**
     * The embedded IPv4 (as four octets) from an IPv4-mapped (`::ffff:a:b`) or
     * IPv4-compatible (`::a:b`) IPv6 literal — i.e. the high five hextets are
     * zero and the sixth is `0xffff` (mapped) or `0` (compatible), so the last
     * two hextets are a 32-bit IPv4. Any other shape returns null and is left
     * to the prefix classification.
     */
    private fun embeddedIpv4FromHextets(address: String): IntArray? {
        val groups = expandIpv6(address) ?: return null
        if ((0 until 5).any { groups[it] != 0 }) return null
        if (groups[5] != 0xFFFF && groups[5] != 0) return null
        val high = groups[6]
        val low = groups[7]
        return intArrayOf((high shr 8) and 0xFF, high and 0xFF, (low shr 8) and 0xFF, low and 0xFF)
    }

    /**
     * Expand an IPv6 literal — with at most one `::` run — to exactly eight
     * 16-bit hextets. Returns null for malformed input or an embedded dotted
     * IPv4 (handled separately by the caller).
     */
    private fun expandIpv6(address: String): IntArray? {
        if (address.isEmpty() || address.contains('.')) return null
        val doubleColon = address.indexOf("::")
        val groups =
            if (doubleColon >= 0) {
                // A second "::" is illegal.
                if (address.indexOf("::", doubleColon + 1) >= 0) return null
                val left = address.substring(0, doubleColon).split(':').filter { it.isNotEmpty() }
                val right = address.substring(doubleColon + 2).split(':').filter { it.isNotEmpty() }
                val missing = 8 - left.size - right.size
                if (missing < 1) return null
                left + List(missing) { "0" } + right
            } else {
                address.split(':')
            }
        if (groups.size != 8) return null
        val out = IntArray(8)
        for (i in 0 until 8) {
            val v = groups[i].toIntOrNull(16) ?: return null
            if (v < 0 || v > 0xFFFF) return null
            out[i] = v
        }
        return out
    }
}
