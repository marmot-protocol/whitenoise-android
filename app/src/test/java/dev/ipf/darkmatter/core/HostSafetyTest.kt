package dev.ipf.darkmatter.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun blankHostIsTreatedAsUnsafe() {
        assertTrue(HostSafety.isPrivateOrLoopbackHost(""))
        assertTrue(HostSafety.isPrivateOrLoopbackHost("   "))
        assertTrue(HostSafety.isPrivateOrLoopbackHost(null))
    }
}
