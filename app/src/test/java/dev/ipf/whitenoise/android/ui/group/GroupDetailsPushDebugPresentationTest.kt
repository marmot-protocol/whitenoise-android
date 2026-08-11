package dev.ipf.whitenoise.android.ui.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class GroupDetailsPushDebugPresentationTest {
    @Test
    fun pushDebugCopyValuesUseNpubsNotRawHex() {
        val copy =
            pushDebugPublicIdentityCopy(
                memberIdHex = MEMBER_HEX,
                serverPubkeyHex = SERVER_HEX,
                npubForDisplay = { hex ->
                    when (hex) {
                        MEMBER_HEX -> MEMBER_NPUB
                        SERVER_HEX -> SERVER_NPUB
                        else -> ""
                    }
                },
            )

        assertEquals(MEMBER_NPUB, copy.memberNpub)
        assertEquals(SERVER_NPUB, copy.serverNpub)
        assertFalse(copy.memberNpub!!.contains(MEMBER_HEX))
        assertFalse(copy.serverNpub!!.contains(SERVER_HEX))
    }

    @Test
    fun pushDebugCopyValuesOmitWhenNpubUnavailable() {
        val copy =
            pushDebugPublicIdentityCopy(
                memberIdHex = MEMBER_HEX,
                serverPubkeyHex = SERVER_HEX,
                npubForDisplay = { "" },
            )

        assertNull(copy.memberNpub)
        assertNull(copy.serverNpub)
    }

    private companion object {
        const val MEMBER_HEX = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val SERVER_HEX = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
        const val MEMBER_NPUB = "npub1aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val SERVER_NPUB = "npub1zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz"
    }
}
