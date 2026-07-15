package dev.ipf.whitenoise.android.media

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidKeystoreDiskByteCacheKeyProviderTest {
    private val alias = "whitenoise.test.decrypted_media_cache.${UUID.randomUUID()}"
    private val cacheDir: File by lazy {
        File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "android-keystore-disk-cache-${UUID.randomUUID()}",
        )
    }

    @After
    fun cleanUp() {
        cacheDir.deleteRecursively()
        KeyStore
            .getInstance(ANDROID_KEYSTORE)
            .apply { load(null) }
            .deleteEntry(alias)
    }

    @Test
    fun generatedKeyDecryptsCacheEntryAcrossProviderInstances() {
        val plaintext = "keystore-backed cache bytes".encodeToByteArray()
        val cacheKey = "https://example.com/private-media"
        val firstCache =
            DiskByteCache(
                cacheDir = cacheDir,
                maxBytes = 1_024L * 1_024L,
                keyProvider = AndroidKeystoreDiskByteCacheKeyProvider(alias),
            )

        firstCache.put(
            key = cacheKey,
            bytes = plaintext,
            token = firstCache.capturePublicationToken(),
        )

        val encryptedEntry = cacheDir.listFiles().orEmpty().single { it.extension == "enc" }
        assertFalse(encryptedEntry.readBytes().contentEquals(plaintext))

        val restartedCache =
            DiskByteCache(
                cacheDir = cacheDir,
                maxBytes = 1_024L * 1_024L,
                keyProvider = AndroidKeystoreDiskByteCacheKeyProvider(alias),
            )
        assertArrayEquals(plaintext, restartedCache.get(cacheKey))
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
