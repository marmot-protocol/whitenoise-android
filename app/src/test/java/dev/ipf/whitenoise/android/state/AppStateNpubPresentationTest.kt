package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AppStateNpubPresentationTest {
    @Test
    fun conversionFailureDoesNotReturnInputHex() {
        val presented =
            npubPresentation(
                accountIdHex = ACCOUNT_HEX,
                cachedNpub = null,
                encode = { null },
            )

        assertEquals("", presented)
        assertNotEquals(ACCOUNT_HEX, presented)
    }

    @Test
    fun conversionFailureWhenEncoderReturnsHexDoesNotReturnInputHex() {
        val presented =
            npubPresentation(
                accountIdHex = ACCOUNT_HEX,
                cachedNpub = null,
                encode = { ACCOUNT_HEX },
            )

        assertEquals("", presented)
        assertNotEquals(ACCOUNT_HEX, presented)
    }

    @Test
    fun invalidCachedValueDoesNotReturnInputHex() {
        val presented =
            npubPresentation(
                accountIdHex = ACCOUNT_HEX,
                cachedNpub = ACCOUNT_HEX,
                encode = { error("cached values must not invoke the encoder") },
            )

        assertEquals("", presented)
        assertNotEquals(ACCOUNT_HEX, presented)
    }

    private companion object {
        const val ACCOUNT_HEX = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
