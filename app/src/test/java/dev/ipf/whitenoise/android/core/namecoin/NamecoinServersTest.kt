package dev.ipf.whitenoise.android.core.namecoin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NamecoinServersTest {
    @Test
    fun currentClearnetServersMatchOperatorOrderAndPins() {
        val servers = NamecoinResolver.DEFAULT_SERVERS
        assertEquals(
            listOf(
                "electrumx.testls.space:50002",
                "nmc2.bitcoins.sk:57002",
                "46.229.238.187:57002",
                "relay.testls.bit:50012",
                "23.158.233.10:50012",
                "electrumx2.testls.space:50002",
            ),
            servers.map { "${it.host}:${it.port}" },
        )
        assertNull(servers[0].pinBase64)
        assertNull(servers[5].pinBase64)
        assertEquals("tkCCQ6AZPqU4Te1poKHeS5l/CRWYHz5n7J4LH22/LxQ=", servers[1].pinBase64)
        assertEquals(servers[1].pinBase64, servers[2].pinBase64)
        assertEquals("Y9Zu+VV8eoqOBanmpk5QzJtnHwqzxb0ynJGiMRKVJBw=", servers[3].pinBase64)
        assertEquals(servers[3].pinBase64, servers[4].pinBase64)
        assertTrue(servers.none { it.host.contains("ethicnology") })
    }

    @Test
    fun ipEndpointsRetainOperatorHostname() {
        val servers = NamecoinResolver.DEFAULT_SERVERS
        assertEquals("nmc2.bitcoins.sk", servers[2].tlsHostname)
        assertEquals("relay.testls.bit", servers[4].tlsHostname)
        assertEquals(servers[1].tlsHostname, servers[2].tlsHostname)
        assertEquals(servers[3].tlsHostname, servers[4].tlsHostname)
    }
}
