package dev.ipf.whitenoise.android.ui.chats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatsScreenIdentifierPresentationTest {
    @Test
    fun encodingFailureDoesNotResolveToBlankOrHexIdentity() {
        assertNull(presentableIdentifierNpub(ACCOUNT_HEX) { "" })
        assertNull(presentableIdentifierNpub(ACCOUNT_HEX) { ACCOUNT_HEX })
    }

    @Test
    fun canonicalNpubResolvesForProfilePresentation() {
        assertEquals(CANONICAL_NPUB, presentableIdentifierNpub(ACCOUNT_HEX) { CANONICAL_NPUB })
    }

    private companion object {
        const val ACCOUNT_HEX = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val CANONICAL_NPUB = "npub1abcdefghijklmnopqrstuvwxyz234567"
    }
}
