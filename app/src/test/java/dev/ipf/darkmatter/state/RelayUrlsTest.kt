package dev.ipf.darkmatter.state

import dev.ipf.darkmatter.core.MarmotClient
import org.junit.Assert.assertEquals
import org.junit.Test

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
    fun relayUrlValidationRejectsPrivateAndLoopbackHosts() {
        // SSRF guard: relay URLs sourced from protocol messages must not point
        // the client at loopback or the local network. See issue #82.
        assertEquals(false, isAcceptableRelayUrl("wss://[::1]:7777"))
        assertEquals(false, isAcceptableRelayUrl("wss://127.0.0.1"))
        assertEquals(false, isAcceptableRelayUrl("wss://10.0.0.1:7777"))
        assertEquals(false, isAcceptableRelayUrl("wss://192.168.1.1"))
        assertEquals(false, isAcceptableRelayUrl("wss://172.16.5.5"))
        assertEquals(false, isAcceptableRelayUrl("wss://169.254.0.1"))
        assertEquals(false, isAcceptableRelayUrl("wss://localhost:7777"))
        // normalizeRelayUrls drops them too.
        assertEquals(emptyList<String>(), normalizeRelayUrls(listOf("wss://127.0.0.1", "wss://[::1]:7777")))
    }

    @Test
    fun bootstrapRelaysSatisfyRelayUrlValidation() {
        assertEquals(emptyList<String>(), MarmotClient.bootstrapRelays.filterNot(::isAcceptableRelayUrl))
    }

    @Test
    fun bootstrapRelaysUseOnlyWhiteNoiseRegionalRelays() {
        assertEquals(
            listOf(
                "wss://relay.us.whitenoise.chat",
                "wss://relay.eu.whitenoise.chat",
            ),
            MarmotClient.bootstrapRelays,
        )
    }

    @Test
    fun accountRelayListsExposeOnlyNip65AndInboxKinds() {
        assertEquals(
            listOf(RelayListKind.Nip65, RelayListKind.Inbox),
            RelayListKind.entries.toList(),
        )
    }
}
