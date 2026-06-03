package dev.ipf.darkmatter.state

import org.junit.Assert.assertEquals
import org.junit.Test
import dev.ipf.darkmatter.core.MarmotClient

class RelayUrlsTest {
    @Test
    fun normalizeRelayUrlsTrimsDropsInvalidAndDeduplicates() {
        assertEquals(
            listOf("wss://relay.example", "wss://xn--e1afmkfd.xn--p1ai"),
            normalizeRelayUrls(
                listOf(
                    "  wss://relay.example  ",
                    "",
                    "wss://relay.example",
                    "WSS://relay.example",
                    "wss://пример.рф",
                    "wss://xn--e1afmkfd.xn--p1ai",
                    " ws://localhost:7777 ",
                    "https://relay.example",
                    "wss://",
                    "wss://?bad",
                    "wss://user:pass@relay.example",
                ),
            ),
        )
    }

    @Test
    fun relayUrlValidationRequiresSecureWebsocketWithHost() {
        assertEquals(true, isAcceptableRelayUrl("wss://relay.example"))
        assertEquals(true, isAcceptableRelayUrl("WSS://relay.example"))
        assertEquals(true, isAcceptableRelayUrl(" wss://relay.example/path "))
        assertEquals(true, isAcceptableRelayUrl("wss://relay.example:443"))
        assertEquals(true, isAcceptableRelayUrl("wss://[::1]:7777"))
        assertEquals(true, isAcceptableRelayUrl("wss://пример.рф"))
        assertEquals(false, isAcceptableRelayUrl("ws://relay.example"))
        assertEquals(false, isAcceptableRelayUrl("https://relay.example"))
        assertEquals(false, isAcceptableRelayUrl("wss://"))
        assertEquals(false, isAcceptableRelayUrl("wss://?bad"))
        assertEquals(false, isAcceptableRelayUrl("wss://user:pass@relay.example"))
        assertEquals(false, isAcceptableRelayUrl("wss://bad host.example"))
        assertEquals(false, isAcceptableRelayUrl("not a url"))
    }

    @Test
    fun bootstrapRelaysSatisfyRelayUrlValidation() {
        assertEquals(emptyList<String>(), MarmotClient.bootstrapRelays.filterNot(::isAcceptableRelayUrl))
    }
}
