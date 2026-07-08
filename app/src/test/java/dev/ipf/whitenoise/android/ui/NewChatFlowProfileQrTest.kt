package dev.ipf.whitenoise.android.ui

import dev.ipf.whitenoise.android.ui.chats.newchat.nostrNpubUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NewChatFlowProfileQrTest {
    @Test
    fun nostrNpubUriUsesNostrNpubUri() {
        val npub = "npub1" + "a".repeat(58)

        assertEquals("nostr:$npub", nostrNpubUri(npub))
    }

    @Test
    fun nostrNpubUriRejectsRawHexFallback() {
        val rawHex = "0".repeat(64)

        assertNull(nostrNpubUri(rawHex))
    }
}
