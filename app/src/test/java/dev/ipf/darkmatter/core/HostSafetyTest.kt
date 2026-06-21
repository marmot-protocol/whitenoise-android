package dev.ipf.darkmatter.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class HostSafetyTest {
    @Test
    fun loopbackAndUnspecifiedIpv4AreFlagged() {
        assertTrue(HostSafety.isPrivateOrLoopbackHost("127.0.0.1"))
        assertTrue(HostSafety.isPrivateOrLoopbackHost("127.255.255.254"))
        assertTrue(HostSafety.isPrivateOrLoopbackHost("0.0.0.0"))
    }

    @Test
    fun rfc1918Ipv4RangesAreFlagged() {
        assertTrue(HostSafety.isPrivateOrLoopbackHost("10.0.0.1"))
        assertTrue(HostSafety.isPrivateOrLoopbackHost("10.255.255.255"))
        assertTrue(HostSafety.isPrivateOrLoopbackHost("172.16.0.1"))
        assertTrue(HostSafety.isPrivateOrLoopbackHost("172.31.255.255"))
        assertTrue(HostSafety.isPrivateOrLoopbackHost("192.168.1.1"))
    }

    @Test
    fun linkLocalAndCgnatIpv4AreFlagged() {
        assertTrue(HostSafety.isPrivateOrLoopbackHost("169.254.1.1"))
        assertTrue(HostSafety.isPrivateOrLoopbackHost("100.64.0.1"))
    }

    @Test
    fun publicIpv4IsAllowed() {
        assertFalse(HostSafety.isPrivateOrLoopbackHost("8.8.8.8"))
        assertFalse(HostSafety.isPrivateOrLoopbackHost("1.1.1.1"))
        // Just outside the RFC-1918 172.16/12 block.
        assertFalse(HostSafety.isPrivateOrLoopbackHost("172.32.0.1"))
        assertFalse(HostSafety.isPrivateOrLoopbackHost("172.15.255.255"))
    }

    @Test
    fun loopbackHostnamesAreFlagged() {
        assertTrue(HostSafety.isPrivateOrLoopbackHost("localhost"))
        assertTrue(HostSafety.isPrivateOrLoopbackHost("LOCALHOST"))
        assertTrue(HostSafety.isPrivateOrLoopbackHost("db.localhost"))
    }

    @Test
    fun ordinaryHostnamesAreAllowed() {
        assertFalse(HostSafety.isPrivateOrLoopbackHost("relay.example"))
        assertFalse(HostSafety.isPrivateOrLoopbackHost("blossom.primal.net"))
        // Non-canonical / non-IP strings are treated as ordinary hostnames.
        assertFalse(HostSafety.isPrivateOrLoopbackHost("256.256.256.256"))
    }

    @Test
    fun ipv6LoopbackAndUnspecifiedAreFlagged() {
        assertTrue(HostSafety.isPrivateOrLoopbackHost("::1"))
        assertTrue(HostSafety.isPrivateOrLoopbackHost("[::1]"))
        assertTrue(HostSafety.isPrivateOrLoopbackHost("::"))
    }

    @Test
    fun ipv6UniqueLocalAndLinkLocalAreFlagged() {
        assertTrue(HostSafety.isPrivateOrLoopbackHost("fc00::1"))
        assertTrue(HostSafety.isPrivateOrLoopbackHost("fd12:3456:789a::1"))
        assertTrue(HostSafety.isPrivateOrLoopbackHost("fe80::1"))
        assertTrue(HostSafety.isPrivateOrLoopbackHost("FE80::1")) // case-insensitive
        assertTrue(HostSafety.isPrivateOrLoopbackHost("fe80::1%eth0")) // zone id stripped
    }

    @Test
    fun publicIpv6IsAllowed() {
        assertFalse(HostSafety.isPrivateOrLoopbackHost("2001:4860:4860::8888"))
    }

    @Test
    fun ipv4MappedIpv6FollowsTheEmbeddedAddress() {
        assertTrue(HostSafety.isPrivateOrLoopbackHost("::ffff:192.168.0.1"))
        assertFalse(HostSafety.isPrivateOrLoopbackHost("::ffff:8.8.8.8"))
    }

    @Test
    fun hexGroupedEmbeddedIpv4LoopbackIsFlagged() {
        // #153 sibling: the hex-grouped embedded IPv4 forms reach 127.0.0.1
        // just like the dotted form and must be blocked.
        assertTrue(HostSafety.isPrivateOrLoopbackHost("::ffff:7f00:1")) // IPv4-mapped 127.0.0.1
        assertTrue(HostSafety.isPrivateOrLoopbackHost("::7f00:1")) // IPv4-compatible 127.0.0.1
        assertTrue(HostSafety.isPrivateOrLoopbackHost("::ffff:c0a8:1")) // 192.168.0.1
        assertTrue(HostSafety.isPrivateOrLoopbackHost("[::ffff:7f00:1]")) // bracketed
        // Public IPv4-mapped stays allowed (8.8.8.8 == 0808:0808).
        assertFalse(HostSafety.isPrivateOrLoopbackHost("::ffff:808:808"))
    }

    @Test
    fun nonDottedIpv4LoopbackEncodingsAreFlagged() {
        // SSRF bypass (#153): every form below resolves to 127.0.0.1 in the
        // platform resolver and must be blocked, not waved through as a name.
        assertTrue(HostSafety.isPrivateOrLoopbackHost("2130706433")) // decimal
        assertTrue(HostSafety.isPrivateOrLoopbackHost("0x7f000001")) // hex
        assertTrue(HostSafety.isPrivateOrLoopbackHost("0177.0.0.1")) // octal first octet
        assertTrue(HostSafety.isPrivateOrLoopbackHost("127.1")) // short form
        assertTrue(HostSafety.isPrivateOrLoopbackHost("0x7f.0.0.1")) // hex octet
        // Decimal encoding of an RFC-1918 address (10.0.0.1).
        assertTrue(HostSafety.isPrivateOrLoopbackHost("167772161"))
    }

    @Test
    fun trailingRootDotStillResolvesToLoopbackAndIsFlagged() {
        // #153: a rooting trailing dot would otherwise split into a 5th empty
        // label (IPv4 decode misses) or dodge the exact "localhost" match.
        assertTrue(HostSafety.isPrivateOrLoopbackHost("127.0.0.1."))
        assertTrue(HostSafety.isPrivateOrLoopbackHost("localhost."))
        // A public host with a trailing dot stays allowed.
        assertFalse(HostSafety.isPrivateOrLoopbackHost("relay.example."))
    }

    @Test
    fun nonDottedPublicIpv4IsStillDecodedAndAllowed() {
        // Proves the decoder classifies rather than blanket-blocking numerics:
        // 134744072 == 8.8.8.8 (public) must remain allowed.
        assertFalse(HostSafety.isPrivateOrLoopbackHost("134744072"))
    }

    @Test
    fun blankHostIsTreatedAsUnsafe() {
        assertTrue(HostSafety.isPrivateOrLoopbackHost(""))
        assertTrue(HostSafety.isPrivateOrLoopbackHost("   "))
        assertTrue(HostSafety.isPrivateOrLoopbackHost(null))
    }

    // --- Resolve-time address checks (DNS-rebinding guard, #344) ---
    // InetAddress.getByAddress builds an address from raw bytes with NO DNS
    // lookup, so these stay fully offline.

    private fun ipv4(
        a: Int,
        b: Int,
        c: Int,
        d: Int,
    ): InetAddress = InetAddress.getByAddress(byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte()))

    private fun ipv6(vararg bytes: Int): InetAddress = InetAddress.getByAddress(ByteArray(16) { bytes[it].toByte() })

    @Test
    fun resolvedLoopbackAndRfc1918AddressesAreFlagged() {
        assertTrue(HostSafety.isPrivateOrLoopbackAddress(ipv4(127, 0, 0, 1)))
        assertTrue(HostSafety.isPrivateOrLoopbackAddress(ipv4(10, 0, 0, 1)))
        assertTrue(HostSafety.isPrivateOrLoopbackAddress(ipv4(172, 16, 0, 1)))
        assertTrue(HostSafety.isPrivateOrLoopbackAddress(ipv4(192, 168, 1, 1)))
        assertTrue(HostSafety.isPrivateOrLoopbackAddress(ipv4(169, 254, 1, 1)))
        assertTrue(HostSafety.isPrivateOrLoopbackAddress(ipv4(0, 0, 0, 0)))
    }

    @Test
    fun resolvedCgnatAddressIsFlagged() {
        // 100.64/10 carrier-grade NAT — not caught by the JDK helpers, so this
        // exercises the byte classifier fall-through.
        assertTrue(HostSafety.isPrivateOrLoopbackAddress(ipv4(100, 64, 0, 1)))
        assertTrue(HostSafety.isPrivateOrLoopbackAddress(ipv4(100, 127, 255, 255)))
    }

    @Test
    fun resolvedPublicAddressesAreAllowed() {
        assertFalse(HostSafety.isPrivateOrLoopbackAddress(ipv4(8, 8, 8, 8)))
        assertFalse(HostSafety.isPrivateOrLoopbackAddress(ipv4(1, 1, 1, 1)))
        // Just outside CGNAT and RFC-1918 blocks.
        assertFalse(HostSafety.isPrivateOrLoopbackAddress(ipv4(100, 128, 0, 1)))
        assertFalse(HostSafety.isPrivateOrLoopbackAddress(ipv4(172, 32, 0, 1)))
    }

    @Test
    fun resolvedIpv6LoopbackAndUniqueLocalAreFlagged() {
        // ::1 loopback.
        assertTrue(
            HostSafety.isPrivateOrLoopbackAddress(
                ipv6(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1),
            ),
        )
        // fc00::/7 unique-local (fd00:: variant) — byte classifier fall-through.
        assertTrue(
            HostSafety.isPrivateOrLoopbackAddress(
                ipv6(0xFD, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1),
            ),
        )
        // fe80::/10 link-local.
        assertTrue(
            HostSafety.isPrivateOrLoopbackAddress(
                ipv6(0xFE, 0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1),
            ),
        )
    }

    @Test
    fun resolvedIpv4MappedPrivateAddressIsFlagged() {
        // ::ffff:192.168.0.1 — the embedded private IPv4 must be followed.
        assertTrue(
            HostSafety.isPrivateOrLoopbackAddress(
                ipv6(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF, 0xFF, 192, 168, 0, 1),
            ),
        )
    }

    @Test
    fun resolvedPublicIpv6IsAllowed() {
        // 2001:4860:4860::8888 (Google public DNS).
        assertFalse(
            HostSafety.isPrivateOrLoopbackAddress(
                ipv6(0x20, 0x01, 0x48, 0x60, 0x48, 0x60, 0, 0, 0, 0, 0, 0, 0, 0, 0x88, 0x88),
            ),
        )
    }
}
