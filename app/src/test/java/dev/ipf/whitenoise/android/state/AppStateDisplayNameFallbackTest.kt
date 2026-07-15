package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Test

class AppStateDisplayNameFallbackTest {
    @Test
    fun missingAccountFallsBackToShortNpub() {
        var formattedAccountId: String? = null

        val displayName =
            networkDisplayNameFallback(
                accountLabel = null,
                accountIdHex = SAMPLE_ACCOUNT_ID_HEX,
                shortNpub = {
                    formattedAccountId = it
                    SAMPLE_SHORT_NPUB
                },
            )

        assertEquals(SAMPLE_ACCOUNT_ID_HEX, formattedAccountId)
        assertEquals(SAMPLE_SHORT_NPUB, displayName)
    }

    @Test
    fun blankAccountLabelFallsBackToShortNpub() {
        val displayName =
            networkDisplayNameFallback(
                accountLabel = "   ",
                accountIdHex = SAMPLE_ACCOUNT_ID_HEX,
                shortNpub = { SAMPLE_SHORT_NPUB },
            )

        assertEquals(SAMPLE_SHORT_NPUB, displayName)
    }

    @Test
    fun accountLabelTakesPrecedenceOverShortNpub() {
        val displayName =
            networkDisplayNameFallback(
                accountLabel = "Work",
                accountIdHex = SAMPLE_ACCOUNT_ID_HEX,
                shortNpub = { error("shortNpub must not run for a named account") },
            )

        assertEquals("Work", displayName)
    }

    private companion object {
        const val SAMPLE_ACCOUNT_ID_HEX = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val SAMPLE_SHORT_NPUB = "npub1qy352...hstefp92"
    }
}
