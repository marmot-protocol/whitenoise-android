package dev.ipf.whitenoise.android.share

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.media.DiskByteCache
import dev.ipf.whitenoise.android.media.DiskByteCacheKeyProvider
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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

    /** The maximum supported URI request survives encrypted persistence exactly. */
    @Test
    fun maximumUriRequestRoundTripsWithoutPayloadLoss() {
        val request =
            request("request-max", text = "shared").copy(
                payload =
                    SharePayload(
                        text = "shared",
                        streamUris = (0 until MAX_PENDING_SHARE_URIS).map { Uri.parse("content://example/$it") },
                        intentMimeType = "application/octet-stream",
                    ),
            )

        assertTrue(store().save(request))
        assertEquals(request, store().load(request.requestId))
    }

    /** A request above the supported URI cap cannot evict an already recoverable share. */
    @Test
    fun aboveMaximumUriRequestIsRejectedWithoutDestroyingThePreviousRequest() {
        val previous = request("request-previous", text = "keep")
        val oversized =
            request("request-too-many", text = "drop").copy(
                payload =
                    SharePayload(
                        text = "drop",
                        streamUris =
                            (0..MAX_PENDING_SHARE_URIS).map { Uri.parse("content://example/overflow/$it") },
                        intentMimeType = "application/octet-stream",
                    ),
            )
        val store = store()
        assertTrue(store.save(previous))

        assertFalse(store.save(oversized))

        assertEquals(previous, store.load(previous.requestId))
        assertNull(store.load(oversized.requestId))
    }

    /** Route-level replacement removes obsolete encrypted content when the newest request cannot be retained. */
    @Test
    fun serializedRejectedReplacementClearsTheSupersededRequest() =
        runTest {
            val previous = request("request-previous", text = "private old payload")
            val oversized =
                request("request-too-many", text = "new payload").copy(
                    payload =
                        SharePayload(
                            text = "new payload",
                            streamUris =
                                (0..MAX_PENDING_SHARE_URIS).map { Uri.parse("content://example/overflow/$it") },
                            intentMimeType = "application/octet-stream",
                        ),
                )
            val delegate = store()
            assertTrue(delegate.save(previous))
            val serialized = SerializedPendingShareRequestStore(delegate)

            assertFalse(serialized.save(oversized))

            assertNull(delegate.load(previous.requestId))
            assertNull(delegate.load(oversized.requestId))
        }

    /** A cancelled older disk write finishes before, never after, the newest replacement. */
    @Test
    fun cancelledOlderSaveCannotOverwriteTheNewestRecoverableRequest() =
        runTest {
            val first = request("request-first", text = "first")
            val second = request("request-second", text = "second")
            val firstStarted = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val current = AtomicReference<ShareRequest?>()
            val delegate =
                object : PendingShareRequestStore {
                    override fun save(request: ShareRequest): Boolean {
                        if (request.requestId == first.requestId) {
                            firstStarted.countDown()
                            check(releaseFirst.await(5, TimeUnit.SECONDS)) { "Timed out releasing the first write" }
                        }
                        current.set(request)
                        return true
                    }

                    override fun load(requestId: String): ShareRequest? =
                        current
                            .get()
                            ?.takeIf { it.requestId == requestId }

                    override fun remove(requestId: String) {
                        current.compareAndSet(current.get()?.takeIf { it.requestId == requestId }, null)
                    }

                    override fun clear() {
                        current.set(null)
                    }
                }
            Executors.newFixedThreadPool(2).asCoroutineDispatcher().use { ioDispatcher ->
                val oldActivityStore = SerializedPendingShareRequestStore(delegate, ioDispatcher)
                val recreatedActivityStore = SerializedPendingShareRequestStore(delegate, ioDispatcher)
                val older = launch(start = CoroutineStart.UNDISPATCHED) { oldActivityStore.save(first) }
                assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
                val newer = launch(start = CoroutineStart.UNDISPATCHED) { recreatedActivityStore.save(second) }

                older.cancel()
                releaseFirst.countDown()
                joinAll(older, newer)
            }

            assertEquals(second, current.get())
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
