package dev.ipf.darkmatter.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PushServerConfigTest {
    @Test
    fun emptyPubkeyMakesConfigUnconfigured() {
        assertNull(PushServerConfig.fromRaw(rawPubkey = "", rawRelayHint = "wss://relay.example"))
    }

    @Test
    fun whitespaceOnlyPubkeyMakesConfigUnconfigured() {
        assertNull(PushServerConfig.fromRaw(rawPubkey = "   ", rawRelayHint = "wss://relay.example"))
    }

    @Test
    fun nullPubkeyMakesConfigUnconfigured() {
        assertNull(PushServerConfig.fromRaw(rawPubkey = null, rawRelayHint = "wss://relay.example"))
    }

    @Test
    fun trimsPubkeyAndRelayHint() {
        val config = PushServerConfig.fromRaw(rawPubkey = "  abc123  ", rawRelayHint = "  wss://relay.eu.whitenoise.chat  ")
        assertEquals("abc123", config?.serverPubkeyHex)
        assertEquals("wss://relay.eu.whitenoise.chat", config?.relayHint)
    }

    @Test
    fun emptyRelayHintBecomesNull() {
        val config = PushServerConfig.fromRaw(rawPubkey = "abc123", rawRelayHint = "")
        assertEquals("abc123", config?.serverPubkeyHex)
        assertNull(config?.relayHint)
    }

    @Test
    fun whitespaceOnlyRelayHintBecomesNull() {
        val config = PushServerConfig.fromRaw(rawPubkey = "abc123", rawRelayHint = "   ")
        assertNull(config?.relayHint)
    }

    @Test
    fun nullRelayHintIsNull() {
        val config = PushServerConfig.fromRaw(rawPubkey = "abc123", rawRelayHint = null)
        assertEquals("abc123", config?.serverPubkeyHex)
        assertNull(config?.relayHint)
    }
}
