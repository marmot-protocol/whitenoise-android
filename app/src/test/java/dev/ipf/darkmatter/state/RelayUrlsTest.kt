package dev.ipf.darkmatter.state

import org.junit.Assert.assertEquals
import org.junit.Test

class RelayUrlsTest {
    @Test
    fun normalizeRelayUrlsTrimsDropsEmptyAndDeduplicates() {
        assertEquals(
            listOf("wss://relay.example", "ws://localhost:7777"),
            normalizeRelayUrls(
                listOf(
                    "  wss://relay.example  ",
                    "",
                    "wss://relay.example",
                    " ws://localhost:7777 ",
                ),
            ),
        )
    }
}
