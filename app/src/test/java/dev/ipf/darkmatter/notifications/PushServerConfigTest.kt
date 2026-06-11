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
        val config = PushServerConfig.fromRaw(rawPubkey = "  $validPubkey  ", rawRelayHint = "  wss://relay.eu.whitenoise.chat  ")
        assertEquals(validPubkey, config?.serverPubkeyHex)
        assertEquals("wss://relay.eu.whitenoise.chat", config?.relayHint)
    }

    @Test
    fun emptyRelayHintBecomesNull() {
        val config = PushServerConfig.fromRaw(rawPubkey = validPubkey, rawRelayHint = "")
        assertEquals(validPubkey, config?.serverPubkeyHex)
        assertNull(config?.relayHint)
    }

    @Test
    fun whitespaceOnlyRelayHintBecomesNull() {
        val config = PushServerConfig.fromRaw(rawPubkey = validPubkey, rawRelayHint = "   ")
        assertNull(config?.relayHint)
    }

    @Test
    fun nullRelayHintIsNull() {
        val config = PushServerConfig.fromRaw(rawPubkey = validPubkey, rawRelayHint = null)
        assertEquals(validPubkey, config?.serverPubkeyHex)
        assertNull(config?.relayHint)
    }

    @Test
    fun pubkeyShorterThanThirtyTwoBytesIsRejected() {
        assertNull(PushServerConfig.fromRaw(rawPubkey = "deadbeef", rawRelayHint = null))
    }

    @Test
    fun pubkeyLongerThanThirtyTwoBytesIsRejected() {
        assertNull(PushServerConfig.fromRaw(rawPubkey = validPubkey + "ff", rawRelayHint = null))
    }

    @Test
    fun pubkeyWithNonHexCharIsRejected() {
        // 64 chars but the 'z' isn't hex — must not register.
        val nonHex = "z3a4996bd18de19f6ac5f6ad42f5f2671eba6e5b739ea9695f07b00b0693fc04"
        assertEquals(64, nonHex.length)
        assertNull(PushServerConfig.fromRaw(rawPubkey = nonHex, rawRelayHint = null))
    }

    @Test
    fun pubkeyWithUppercaseHexIsNormalizedToLowercase() {
        val mixed = "73A4996BD18DE19F6AC5F6AD42F5F2671EBA6E5B739EA9695F07B00B0693FC04"
        val config = PushServerConfig.fromRaw(rawPubkey = mixed, rawRelayHint = null)
        assertEquals(validPubkey, config?.serverPubkeyHex)
    }

    @Test
    fun pubkeyAtExactlySixtyFourHexCharsIsAccepted() {
        val config = PushServerConfig.fromRaw(rawPubkey = validPubkey, rawRelayHint = "wss://relay.example")
        assertEquals(validPubkey, config?.serverPubkeyHex)
        assertEquals("wss://relay.example", config?.relayHint)
    }

    companion object {
        // 64 lowercase hex chars (32 bytes) — the legal shape for a Nostr secp256k1 pubkey.
        private const val validPubkey = "73a4996bd18de19f6ac5f6ad42f5f2671eba6e5b739ea9695f07b00b0693fc04"
    }
}
