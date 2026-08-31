package dev.ipf.whitenoise.android.media

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
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

    /** Verifies the platform Keystore can stream a maximum-size chunked entry across restart. */
    @Test
    fun androidKeystoreMaterializesReal64MiBChunkedEntry() {
        val entryBytes = 64 * 1024 * 1024
        val cacheKey = "https://example.com/private-64m-apk"
        val cache =
            DiskByteCache(
                cacheDir = cacheDir,
                maxBytes = 70L * 1024L * 1024L,
                maxEntryBytes = 64L * 1024L * 1024L,
                keyProvider = AndroidKeystoreDiskByteCacheKeyProvider(alias),
            )
        val expectedDigest =
            ByteArray(entryBytes) { index -> (index and 0xff).toByte() }.let { plaintext ->
                val digest = MessageDigest.getInstance("SHA-256").digest(plaintext)
                cache.put(cacheKey, plaintext, cache.capturePublicationToken())
                digest
            }

        val restarted =
            DiskByteCache(
                cacheDir = cacheDir,
                maxBytes = 70L * 1024L * 1024L,
                maxEntryBytes = 64L * 1024L * 1024L,
                keyProvider = AndroidKeystoreDiskByteCacheKeyProvider(alias),
            )
        val lease = restarted.materialize(cacheKey)
        assertNotNull(lease)
        lease!!.use {
            assertEquals(entryBytes.toLong(), it.file.length())
            val actualDigest = sha256(it.file)
            assertArrayEquals(expectedDigest, actualDigest)
        }
    }

    /** Digests a materialized lease incrementally so the test does not allocate another 64 MiB. */
    private fun sha256(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        file.inputStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
