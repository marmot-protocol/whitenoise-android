package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.ui.common.isCanonicalNpub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

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

    /** An all-uppercase key reference is the same bech32 key, so it is shown and copied in lowercase (#3565). */
    @Test
    fun presentationFromAnUppercaseReferenceUsesItsCanonicalLowercase() {
        val upper = UPPER_KEY.uppercase(Locale.ROOT)

        val presented =
            presentationNpubFromReference(
                reference = upper,
                resolvedAccountIdHex = null,
                npubForDisplay = { error("must not run for a key reference") },
            )

        assertEquals(UPPER_KEY, presented)
        assertTrue(isCanonicalNpub(presented))
    }

    /** A mixed-case reference is not a valid bech32 key and is kept as written, so it stays uncopyable. */
    @Test
    fun presentationKeepsAMixedCaseReferenceAsWritten() {
        val mixed = "NPUB1" + UPPER_KEY.drop(5)

        assertEquals(mixed, presentationNpubFromReference(mixed, null) { "" })
        assertFalse(isCanonicalNpub(mixed))
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
        const val UPPER_KEY = "npub1qqqqqqqqqqqqqqqqqqqqqqqqqppppppppqqqqqqqqqqqqqqqqqqqqqqqqq"
    }
}
