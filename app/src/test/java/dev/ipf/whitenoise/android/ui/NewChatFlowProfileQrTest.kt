package dev.ipf.whitenoise.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class NewChatFlowProfileQrTest {
    @Test
    fun profileQrUriUsesNostrNpubUri() {
        val npub = "npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqpz7d7k"

        assertEquals("nostr:$npub", profileQrUri(npub))
    }
}
