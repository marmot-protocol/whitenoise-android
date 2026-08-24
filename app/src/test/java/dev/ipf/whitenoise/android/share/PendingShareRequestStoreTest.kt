package dev.ipf.whitenoise.android.share

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.media.DiskByteCache
import dev.ipf.whitenoise.android.media.DiskByteCacheKeyProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PendingShareRequestStoreTest {
    private lateinit var dir: File
    private val keyProvider =
        DiskByteCacheKeyProvider {
            SecretKeySpec(ByteArray(32) { index -> (index + 1).toByte() }, "AES")
        }

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("pending-share-store-test").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun requestRoundTripsAcrossStoreRecreationWithoutPlaintextOnDisk() {
        val request = request("request-1", text = "private shared text")
        val first = store()

        assertTrue(first.save(request))

        val encryptedBytes =
            dir
                .listFiles()
                .orEmpty()
                .single { it.extension == "enc" }
                .readBytes()
        assertFalse(String(encryptedBytes, Charsets.ISO_8859_1).contains("private shared text"))
        assertEquals(request, store().load(request.requestId))
    }

    @Test
    fun newRequestReplacesThePreviousPendingShare() {
        val first = request("request-1", text = "first")
        val second = request("request-2", text = "second")
        val store = store()

        assertTrue(store.save(first))
        assertTrue(store.save(second))

        assertNull(store.load(first.requestId))
        assertEquals(second, store.load(second.requestId))
    }

    @Test
    fun decoderRejectsARequestUnderTheWrongSavedStateToken() {
        val encoded = encodePendingShareRequest(request("request-1", text = "shared"))

        assertNull(decodePendingShareRequest(encoded, expectedRequestId = "request-2"))
    }

    @Test
    fun oversizedPayloadIsNotPersisted() {
        val request = request("request-large", text = "x".repeat(MAX_PENDING_SHARE_REQUEST_BYTES + 1))
        val store = store()

        assertFalse(store.save(request))
        assertNull(store.load(request.requestId))
    }

    @Test
    fun contextBackedStoreCreationRunsOffTheCallingThread() =
        runTest {
            val callerThread = Thread.currentThread()
            val expected = store()
            lateinit var factoryThread: Thread

            val actual =
                createPendingShareRequestStore(
                    context = ApplicationProvider.getApplicationContext<Context>(),
                    factory = {
                        factoryThread = Thread.currentThread()
                        expected
                    },
                )

            assertSame(expected, actual)
            assertNotSame(callerThread, factoryThread)
        }

    private fun store(): EncryptedPendingShareRequestStore =
        EncryptedPendingShareRequestStore(
            DiskByteCache(
                cacheDir = dir,
                maxBytes = PENDING_SHARE_REQUEST_CACHE_BYTES.toLong(),
                maxEntryBytes = MAX_PENDING_SHARE_REQUEST_BYTES.toLong(),
                keyProvider = keyProvider,
            ),
        )

    private fun request(
        id: String,
        text: String,
    ): ShareRequest =
        ShareRequest(
            payload =
                SharePayload(
                    text = text,
                    streamUris = listOf(Uri.parse("content://example/one"), Uri.parse("content://example/two")),
                    intentMimeType = "text/plain",
                ),
            shortcutId = "shortcut-1",
            requestId = id,
        )
}
