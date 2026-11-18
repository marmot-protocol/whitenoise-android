package dev.ipf.whitenoise.android.state

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
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
 * Every read opens the blob through the Keystore, which is a round trip to
 * secure hardware on a phone. A store read many times between writes passes
 * `cacheReads` to reuse the last payload opened or sealed for its file and key
 * provider, by this instance or another one, since callers build short-lived
 * stores over one file. The cache is keyed by the stored ciphertext, so a
 * payload written by anyone else is never served from it. It holds the
 * decrypted map in process memory for the life of the process: a write
 * replaces it and only a clear drops it. Leave it off for secrets that should
 * not outlive the call that needed them. A cached read also does not prove the
 * Keystore still works, so a caller that must fail closed on an unavailable
 * key asks [readAll] for a fresh open.
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
    private val cacheReads: Boolean = false,
) {
    private val lock = Any()
    private val prefs by lazy {
        context.applicationContext.getSharedPreferences(fileName, Context.MODE_PRIVATE)
    }

    private val cacheKey = fileName to keyProvider

    // The last payload opened or sealed for this file and key, and the map it decodes to.
    private var opened: Pair<String, Map<String, String>>?
        get() = if (cacheReads) OPENED[cacheKey] else null
        set(value) {
            if (!cacheReads) return
            if (value == null) OPENED.remove(cacheKey) else OPENED[cacheKey] = value
        }

    /**
     * Every stored value. [fresh] opens the payload through the Keystore even when it is cached, for
     * callers that must notice a key that stopped working.
     */
    fun readAll(fresh: Boolean = false): Map<String, String> =
        asSecurityFailure {
            synchronized(lock) {
                val sealed = prefs.getString(PAYLOAD_KEY, null)
                if (sealed == null) opened = null
                sealed?.let { if (fresh) decrypt(it) else open(it) } ?: emptyMap()
            }
        }

    /** Sets or removes one value, merging with the stored payload, and writes only when it changed. */
    fun write(
        key: String,
        value: String?,
    ) {
        asSecurityFailure {
            synchronized(lock) {
                val current = prefs.getString(PAYLOAD_KEY, null)?.let(::open) ?: emptyMap()
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
                val current = prefs.getString(PAYLOAD_KEY, null)?.let(::open) ?: emptyMap()
                prefs.edit().putString(PAYLOAD_KEY, encrypt(current + values)).commit()
            }
        }

    /**
     * Replaces the complete logical map and commits synchronously. Callers run
     * this off-main when coalescing high-frequency updates such as drafts.
     */
    fun replaceAllDurably(values: Map<String, String>): Boolean =
        asSecurityFailure {
            synchronized(lock) {
                prefs.edit().putString(PAYLOAD_KEY, encrypt(values)).commit()
            }
        }

    /** Removes the stored payload and the cached copy of it. */
    fun clear() {
        synchronized(lock) {
            opened = null
            prefs.edit().clear().apply()
        }
    }

    /** Clears a workflow receipt before its owner starts a fresh attempt. */
    fun clearDurably(): Boolean =
        synchronized(lock) {
            opened = null
            prefs.edit().clear().commit()
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

    /** Seals [values] and, for a caching store, remembers what opening the result will return. */
    private fun encrypt(values: Map<String, String>): String {
        val plaintext = JSONObject(values.toMap()).toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider.secretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        val sealed = Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
        // Remember what opening these exact bytes yields, never the caller's map — the two differ
        // for text that does not survive UTF-8, and the cache must match a real open.
        if (cacheReads) opened = sealed to decode(plaintext)
        return sealed
    }

    /**
     * The map sealed in [sealed]. A payload already opened or sealed for this file and key is
     * returned without another Keystore round trip, and anything else is decrypted.
     */
    private fun open(sealed: String): Map<String, String> {
        opened?.takeIf { it.first == sealed }?.let { return it.second }
        return decrypt(sealed)
    }

    /** Opens [sealed] through the Keystore and, for a caching store, remembers the result. */
    private fun decrypt(sealed: String): Map<String, String> {
        val raw = Base64.decode(sealed, Base64.NO_WRAP)
        if (raw.size <= IV_BYTES) throw GeneralSecurityException("sealed payload is truncated")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            keyProvider.secretKey(),
            GCMParameterSpec(GCM_TAG_BITS, raw, 0, IV_BYTES),
        )
        val values = decode(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES))
        opened = sealed to values
        return values
    }

    /** Reads a decrypted payload. A caching store hands out a map callers cannot modify. */
    private fun decode(plaintext: ByteArray): Map<String, String> {
        val json = JSONObject(String(plaintext, Charsets.UTF_8))
        val values = json.keys().asSequence().mapNotNull { key -> (json.opt(key) as? String)?.let { key to it } }
        return values.toMap().let { if (cacheReads) Collections.unmodifiableMap(it) else it }
    }

    companion object {
        // Shared by every caching instance of one file and key provider. Each entry is the result of
        // a real open or seal of exactly that ciphertext, so sharing it cannot serve another payload.
        private val OPENED =
            ConcurrentHashMap<Pair<String, SecureStoreKeyProvider>, Pair<String, Map<String, String>>>()
        private const val PAYLOAD_KEY = "payload"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }
}
