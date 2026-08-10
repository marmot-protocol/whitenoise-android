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

    @Test
    fun npubAccountLabelFallsBackToShortNpub() {
        var formattedAccountId: String? = null

        val displayName =
            networkDisplayNameFallback(
                accountLabel = SAMPLE_NPUB_LABEL,
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
    fun lowercaseHexAccountLabelFallsBackToShortNpub() {
        var formattedAccountId: String? = null

        val displayName =
            networkDisplayNameFallback(
                accountLabel = SAMPLE_ACCOUNT_ID_HEX,
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
    fun mixedCaseHexAccountLabelFallsBackToShortNpub() {
        val mixedCaseLabel = "0123456789ABCDEF0123456789abcdef0123456789ABCDEF0123456789abcdef"

        val displayName =
            networkDisplayNameFallback(
                accountLabel = mixedCaseLabel,
                accountIdHex = SAMPLE_ACCOUNT_ID_HEX,
                shortNpub = { SAMPLE_SHORT_NPUB },
            )

        assertEquals(SAMPLE_SHORT_NPUB, displayName)
    }

    private companion object {
        const val SAMPLE_ACCOUNT_ID_HEX = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val SAMPLE_SHORT_NPUB = "npub1qy352...hstefp92"
        const val SAMPLE_NPUB_LABEL = "npub1qy352hw5xrsq5k6x5t5vnpqx4lhfv3q8jqk9x0h5q6x5t5vnpq"
    }
}
