package dev.ipf.whitenoise.android

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricPrompt
import dev.ipf.whitenoise.android.state.AppText
import java.security.InvalidKeyException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Binds app-unlock approval to an authentication-per-use Android Keystore key.
 *
 * A callback alone is not sufficient proof of local authentication: a hooked
 * process can invoke it directly. The sensitive state transition therefore
 * requires the authenticated [Cipher] returned by [BiometricPrompt].
 */
internal class AppUnlockCryptoGate {
    fun createCryptoObject(): BiometricPrompt.CryptoObject = BiometricPrompt.CryptoObject(createAuthenticatedCipher())

    fun verify(
        result: BiometricPrompt.AuthenticationResult,
        expectedCipher: Cipher?,
    ): Boolean = verifyExpectedAppUnlockCipher(result.cryptoObject?.cipher, expectedCipher)

    private fun createAuthenticatedCipher(): Cipher =
        try {
            createAuthenticatedCipherOnce()
        } catch (_: InvalidKeyException) {
            keyStore().deleteEntry(KEY_ALIAS)
            createAuthenticatedCipherOnce()
        }

    private fun createAuthenticatedCipherOnce(): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, secretKey())
        }

    private fun secretKey(): SecretKey {
        val keyStore = keyStore()
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply {
                init(
                    KeyGenParameterSpec
                        .Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setUserAuthenticationRequired(true)
                        .setUserAuthenticationParameters(
                            0,
                            KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                        ).build(),
                )
            }.generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "white_noise_app_unlock_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

internal fun verifyExpectedAppUnlockCipher(
    actualCipher: Cipher?,
    expectedCipher: Cipher?,
): Boolean {
    if (actualCipher == null || actualCipher !== expectedCipher) return false
    return runCatching { actualCipher.doFinal(APP_UNLOCK_CHALLENGE).isNotEmpty() }.getOrDefault(false)
}

private val APP_UNLOCK_CHALLENGE = "white-noise-app-unlock".toByteArray(Charsets.UTF_8)

internal fun foregroundReturnExpiryElapsedRealtime(
    nowElapsedRealtime: Long,
    hostResumed: Boolean,
): Long? {
    if (hostResumed) return null
    return nowElapsedRealtime.coerceAtMost(Long.MAX_VALUE - APP_UNLOCK_FOREGROUND_HANDOFF_MILLIS) +
        APP_UNLOCK_FOREGROUND_HANDOFF_MILLIS
}

internal fun appLockAuthErrorMessage(errorCode: Int): AppText =
    when (errorCode) {
        BiometricPrompt.ERROR_CANCELED,
        BiometricPrompt.ERROR_USER_CANCELED,
        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
        -> AppText.Resource(R.string.app_lock_auth_cancelled)
        BiometricPrompt.ERROR_LOCKOUT,
        BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
        -> AppText.Resource(R.string.app_lock_auth_locked)
        BiometricPrompt.ERROR_TIMEOUT -> AppText.Resource(R.string.app_lock_auth_timed_out)
        BiometricPrompt.ERROR_HW_NOT_PRESENT,
        BiometricPrompt.ERROR_HW_UNAVAILABLE,
        BiometricPrompt.ERROR_NO_BIOMETRICS,
        BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
        -> AppText.Resource(R.string.app_lock_auth_unavailable)
        else -> AppText.Resource(R.string.app_lock_auth_failed)
    }

private const val APP_UNLOCK_FOREGROUND_HANDOFF_MILLIS = 5_000L
