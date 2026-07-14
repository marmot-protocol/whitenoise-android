package dev.ipf.whitenoise.android.media

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Supplies the process-stable AES key for encrypted media-cache entries. */
internal class AndroidKeystoreDiskByteCacheKeyProvider(
    private val alias: String = KEY_ALIAS,
) : DiskByteCacheKeyProvider {
    @Volatile
    private var cached: SecretKey? = null

    override fun getOrCreate(): SecretKey {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: loadOrGenerate().also { cached = it }
        }
    }

    private fun loadOrGenerate(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(alias)) {
            return keyStore.getKey(alias, null) as? SecretKey
                ?: throw GeneralSecurityException("Android Keystore alias $alias is not an AES key")
        }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec
                .Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "whitenoise.decrypted_media_cache.aes_gcm.v1"
        const val KEY_BITS = 256
    }
}
