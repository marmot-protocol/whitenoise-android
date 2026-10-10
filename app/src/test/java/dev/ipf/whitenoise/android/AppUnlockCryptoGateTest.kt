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

    /** A provider whose authenticated operation rejects finalization must fail closed, not throw. */
    @Test
    fun providerFailureDuringChallengeDoesNotEscapeVerification() {
        val decrypt = Cipher.getInstance(TRANSFORMATION)
        val key = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
        decrypt.init(Cipher.DECRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, ByteArray(12)))

        // The fixed unlock challenge is not an authenticated GCM ciphertext.
        assertFalse(verifyExpectedAppUnlockCipher(decrypt, decrypt))
    }

    private fun initializedCipher(): Cipher {
        val key = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
        return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
