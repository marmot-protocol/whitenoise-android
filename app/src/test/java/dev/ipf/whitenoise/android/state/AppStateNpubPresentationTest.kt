package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AppStateNpubPresentationTest {
    @Test
    fun operationalNpubFallsBackToAccountHexWhenEncodingFails() {
        val resolved =
            operationalNpub(
                accountIdHex = ACCOUNT_HEX,
                cachedNpub = null,
                encode = { error("encoding failed") },
            )

        assertEquals(ACCOUNT_HEX, resolved)
    }

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

    @Test
    fun presentationFromReferenceKeepsCanonicalNpubInput() {
        val presented =
            presentationNpubFromReference(
                reference = CANONICAL_NPUB,
                resolvedAccountIdHex = ACCOUNT_HEX,
                npubForDisplay = { error("must not run when reference is already canonical") },
            )

        assertEquals(CANONICAL_NPUB, presented)
    }

    @Test
    fun presentationFromOperationalHexFallbackDoesNotReturnHex() {
        val presented =
            presentationNpubFromReference(
                reference = ACCOUNT_HEX,
                resolvedAccountIdHex = ACCOUNT_HEX,
                npubForDisplay = { "" },
            )

        assertEquals("", presented)
        assertNotEquals(ACCOUNT_HEX, presented)
    }

    @Test
    fun presentationFromOperationalHexFallbackUsesDisplayBoundary() {
        val presented =
            presentationNpubFromReference(
                reference = ACCOUNT_HEX,
                resolvedAccountIdHex = ACCOUNT_HEX,
                npubForDisplay = { CANONICAL_NPUB },
            )

        assertEquals(CANONICAL_NPUB, presented)
    }

    private companion object {
        const val ACCOUNT_HEX = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val CANONICAL_NPUB = "npub1abcdefghijklmnopqrstuvwxyz234567"
    }
}
