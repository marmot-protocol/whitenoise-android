package dev.ipf.whitenoise.android.updates

import dev.ipf.whitenoise.android.core.nostr.sha256
import dev.ipf.whitenoise.android.core.nostr.toHex
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureHashTest {
    @Test
    fun constantTimeEqualsMatchesIdenticalDigests() {
        val digest = ByteArray(32) { index -> index.toByte() }
        assertTrue(constantTimeEquals(digest, digest.copyOf()))
    }

    @Test
    fun constantTimeEqualsRejectsDifferentLengths() {
        assertFalse(constantTimeEquals(ByteArray(32), ByteArray(31)))
    }

    @Test
    fun constantTimeEqualsHexMatchesLowercaseHex() {
        val digest = sha256("darkmatter".toByteArray())
        assertTrue(constantTimeEqualsHex(digest, digest.toHex()))
    }

    @Test
    fun constantTimeEqualsHexRejectsMalformedHex() {
        val digest = sha256("darkmatter".toByteArray())
        assertFalse(constantTimeEqualsHex(digest, "not-a-hash"))
    }
}
