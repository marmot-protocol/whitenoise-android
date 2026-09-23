package dev.ipf.whitenoise.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.KeyGenerator

class AppUnlockCryptoGateTest {
    @Test
    fun exactExpectedCipherCompletesTheChallenge() {
        val expected = initializedCipher()

        assertTrue(verifyExpectedAppUnlockCipher(expected, expected))
    }

    @Test
    fun usableButDifferentCipherIsRejected() {
        val expected = initializedCipher()
        val forged = initializedCipher()

        assertFalse(verifyExpectedAppUnlockCipher(forged, expected))
    }

    @Test
    fun missingOrUnusableCipherIsRejected() {
        val expected = initializedCipher()

        assertFalse(verifyExpectedAppUnlockCipher(null, expected))
        assertFalse(verifyExpectedAppUnlockCipher(expected, null))
        val uninitialized = Cipher.getInstance(TRANSFORMATION)
        assertFalse(verifyExpectedAppUnlockCipher(uninitialized, uninitialized))
    }

    private fun initializedCipher(): Cipher {
        val key = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
        return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
