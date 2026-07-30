package dev.ipf.whitenoise.android.state

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Supplies the AES key a [KeystoreSecureStore] encrypts with. Production uses
 * [AndroidKeystoreSecretKeyProvider]; tests inject a plain in-memory key
 * because Robolectric has no AndroidKeyStore implementation.
 */
internal interface SecureStoreKeyProvider {
    fun secretKey(): SecretKey
}

/**
 * A hardware-backed AES-256-GCM key held in the Android Keystore under
 * [alias], created on first use. Mirrors the alias convention and key
 * parameters the media-cache and notification-reply ciphers already use.
 */
internal class AndroidKeystoreSecretKeyProvider(
    private val alias: String,
) : SecureStoreKeyProvider {
    @Volatile
    private var cached: SecretKey? = null

    override fun secretKey(): SecretKey {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: loadOrCreate().also { cached = it }
        }
    }

    private fun loadOrCreate(): SecretKey {
        val keystore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keystore.containsAlias(alias)) {
            // A non-AES entry under our alias is unusable and unrecoverable;
            // surface it as the same GeneralSecurityException the callers'
            // corruption-recovery paths already handle.
            val existing =
                keystore.getKey(alias, null) as? SecretKey
                    ?: throw GeneralSecurityException("keystore alias $alias is not a secret key")
            return existing
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(AES_KEY_BITS)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val AES_KEY_BITS = 256
    }
}

/**
 * A small encrypted key/value store backed directly by the Android Keystore,
 * replacing the EOL `androidx.security-crypto` stack (Jetpack Security is no
 * longer maintained, so it will not receive fixes for future Keystore/Tink
 * issues).
 *
 * The whole map is sealed into ONE AES-GCM ciphertext rather than encrypting
 * entries individually. That keeps logical key names inside the ciphertext —
 * `EncryptedSharedPreferences` encrypted keys deterministically, so anything
 * per-entry would have to reproduce that or leak names like group ids — and it
 * removes the deterministic-key-encryption problem entirely. These stores hold
 * a handful of small values, so rewriting the blob per write is cheap.
 *
 * Callers are expected to catch [GeneralSecurityException] and recover by
 * clearing the store, exactly as they did with the previous implementation.
 * Every operation honours that contract: the Keystore, Base64, and JSON layers
 * throw types that are NOT [GeneralSecurityException] (`ProviderException` and
 * `IllegalArgumentException` are `RuntimeException`s, and `KeyStore.load`
 * throws `IOException`), so they are normalized here rather than left for each
 * call site to rediscover. Letting one escape would abort
 * `WhiteNoiseAppState` construction, and a failed Kotlin `lazy` re-throws on
 * every later access — a transient Keystore fault would become a crash loop.
 */
internal class KeystoreSecureStore(
    private val context: Context,
    private val fileName: String,
    private val keyProvider: SecureStoreKeyProvider,
) {
    private val lock = Any()
    private val prefs by lazy {
        context.applicationContext.getSharedPreferences(fileName, Context.MODE_PRIVATE)
    }

    fun readAll(): Map<String, String> =
        asSecurityFailure {
            synchronized(lock) {
                val sealed = prefs.getString(PAYLOAD_KEY, null) ?: return@asSecurityFailure emptyMap()
                decrypt(sealed)
            }
        }

    fun write(
        key: String,
        value: String?,
    ) {
        asSecurityFailure {
            synchronized(lock) {
                val current = prefs.getString(PAYLOAD_KEY, null)?.let(::decrypt) ?: emptyMap()
                val updated = if (value == null) current - key else current + (key to value)
                if (updated != current) {
                    prefs.edit().putString(PAYLOAD_KEY, encrypt(updated)).apply()
                }
            }
        }
    }

    /**
     * Merges [values] and commits synchronously, returning whether the write is
     * durable. Used by the legacy import, which must not delete its source
     * until the copy has actually reached disk.
     */
    fun putAllDurably(values: Map<String, String>): Boolean =
        asSecurityFailure {
            synchronized(lock) {
                val current = prefs.getString(PAYLOAD_KEY, null)?.let(::decrypt) ?: emptyMap()
                prefs.edit().putString(PAYLOAD_KEY, encrypt(current + values)).commit()
            }
        }

    fun clear() {
        synchronized(lock) { prefs.edit().clear().apply() }
    }

    // Anything the Keystore/Base64/JSON layers raise becomes the one exception
    // type callers are documented to handle. Errors (OOM and friends) are not
    // storage failures and keep propagating.
    // Catching broadly is the entire purpose: the contract is that a storage
    // failure reaches callers as exactly one type, so nothing may slip past.
    @Suppress("TooGenericExceptionCaught")
    private fun <T> asSecurityFailure(block: () -> T): T =
        try {
            block()
        } catch (error: GeneralSecurityException) {
            throw error
        } catch (error: Exception) {
            throw GeneralSecurityException("secure store operation failed", error)
        }

    private fun encrypt(values: Map<String, String>): String {
        val plaintext = JSONObject(values.toMap()).toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider.secretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(sealed: String): Map<String, String> {
        val raw = Base64.decode(sealed, Base64.NO_WRAP)
        if (raw.size <= IV_BYTES) throw GeneralSecurityException("sealed payload is truncated")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            keyProvider.secretKey(),
            GCMParameterSpec(GCM_TAG_BITS, raw, 0, IV_BYTES),
        )
        val plaintext = cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES)
        val json = JSONObject(String(plaintext, Charsets.UTF_8))
        return json
            .keys()
            .asSequence()
            .mapNotNull { key -> (json.opt(key) as? String)?.let { key to it } }
            .toMap()
    }

    companion object {
        private const val PAYLOAD_KEY = "payload"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }
}
