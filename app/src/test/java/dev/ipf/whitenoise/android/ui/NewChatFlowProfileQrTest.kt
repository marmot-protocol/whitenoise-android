package dev.ipf.whitenoise.android.ui

import dev.ipf.whitenoise.android.core.ProfileLink
import dev.ipf.whitenoise.android.ui.profile.profileQrContentForNpub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NewChatFlowProfileQrTest {
    @Test
    fun newMessageSelfQrEncodesProfileLinkQrUri() {
        val npub = "npub1" + "a".repeat(58)

        assertEquals(ProfileLink(npub).qrUri, profileQrContentForNpub(npub))
    }

    @Test
    fun newMessageSelfQrRejectsRawHexFallback() {
        val rawHex = "0".repeat(64)

        assertNull(profileQrContentForNpub(rawHex))
    }
}
