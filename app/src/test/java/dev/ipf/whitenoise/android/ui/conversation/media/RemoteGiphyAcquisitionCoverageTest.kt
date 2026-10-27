package dev.ipf.whitenoise.android.ui.conversation.media

import dev.ipf.whitenoise.android.core.RemoteGiphyMedia
import dev.ipf.whitenoise.android.functionBody
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/** Enforces shared transport, bounded ownership and independent row cancellation through the production owner. */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteGiphyAcquisitionCoverageTest {
    /** Trimming cache references cannot retire a shared transport or duplicate it for a new subscriber. */
    @Test
    fun memoryTrimKeepsInFlightSubscribersAndLaterCacheReuse() =
        runTest {
            val response = CompletableDeferred<ByteArray>()
            val cache = RemoteGiphyByteCache(1024)
            var gets = 0
            val acquisition = RemoteGiphyAcquisition(backgroundScope, cache) {
                gets++
                response.await()
            }
            val owner = Any()
            val first = async { acquisition.useBytes(owner, request(), { true }) { it } }
            runCurrent()
            cache.trimToSize(0)
            val second = async { acquisition.useBytes(owner, request(), { true }) { it } }
            runCurrent()
            assertEquals(1, gets)
            val bytes = byteArrayOf(1, 2, 3)
            response.complete(bytes)
            assertSame(bytes, first.await())
            assertSame(bytes, second.await())
            assertEquals(3L, cache.trimToSize(0))
            assertNull(cache.get(request().url))
            assertEquals(listOf<Byte>(1, 2, 3), bytes.toList())
            cache.put(request().url, bytes)
            assertSame(bytes, acquisition.useBytes(owner, request(), { true }) { it })
            assertEquals(1, gets)
        }

    /** More than the six capacity slots still share one GET and each retain a separate decoded result. */
    @Test
    fun twelveRowsShareOneRequestAndKeepSeparatePresentations() =
        runTest {
            val response = CompletableDeferred<ByteArray>()
            var gets = 0
            val cache = RemoteGiphyByteCache(1024)
            val acquisition =
                RemoteGiphyAcquisition(backgroundScope, cache) {
                    gets++
                    response.await()
                }
            val owner = Any()
            val rows = List(12) { async { acquisition.useBytes(owner, request(), { true }) { Any() } } }
            runCurrent()
            assertEquals(1, gets)
            response.complete(byteArrayOf(1))
            val results = rows.map { it.await() }
            assertEquals(12, results.toSet().size)
            assertEquals(1, gets)
            assertTrue(cache.get(request().url) != null)
        }

    /** A row leaving, including the initiating row, cannot abort a remaining consumer's request. */
    @Test
    fun cancellingFirstSubscriberKeepsTheSecondDownloadAlive() =
        runTest {
            val response = CompletableDeferred<ByteArray>()
            var cancelled = false
            val acquisition =
                RemoteGiphyAcquisition(backgroundScope, RemoteGiphyByteCache(1024)) {
                    try {
                        response.await()
                    } finally {
                        cancelled = !response.isCompleted
                    }
                }
            val owner = Any()
            val first = async { acquisition.useBytes(owner, request(), { true }) { it } }
            val second = async { acquisition.useBytes(owner, request(), { true }) { it } }
            runCurrent()
            first.cancelAndJoin()
            assertFalse(cancelled)
            val bytes = byteArrayOf(2)
            response.complete(bytes)
            assertSame(bytes, second.await())
        }

    /** Last-subscriber cancellation reaches the transport and leaves a subsequent attempt retryable. */
    @Test
    fun lastSubscriberCancelsTransportAndRetryStartsFresh() =
        runTest {
            var gets = 0
            var cancelled = false
            val acquisition =
                RemoteGiphyAcquisition(backgroundScope, RemoteGiphyByteCache(1024)) {
                    gets++
                    if (gets == 1) {
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled = true
                        }
                    }
                    byteArrayOf(3)
                }
            val owner = Any()
            val first = async { acquisition.useBytes(owner, request(), { true }) { it } }
            runCurrent()
            first.cancelAndJoin()
            runCurrent()
            assertTrue(cancelled)
            assertTrue(acquisition.useBytes(owner, request(), { true }) { it } != null)
            assertEquals(2, gets)
        }

    /** Independent URLs retain the six-operation cap rather than multiplying capacity by row count. */
    @Test
    fun differentUrlsStayWithinSixActiveDownloads() =
        runTest {
            var active = 0
            var peak = 0
            val acquisition =
                RemoteGiphyAcquisition(backgroundScope, RemoteGiphyByteCache(1024)) {
                    active++
                    peak = maxOf(peak, active)
                    try {
                        awaitCancellation()
                    } finally {
                        active--
                    }
                }
            val owner = Any()
            val rows = List(12) { async { acquisition.useBytes(owner, request("$it"), { true }) { it } } }
            runCurrent()
            assertEquals(6, active)
            rows.forEach { it.cancelAndJoin() }
            runCurrent()
            assertEquals(6, peak)
            assertEquals(0, active)
        }

    /** Even twelve consumers of one response use at most six concurrent row decoders. */
    @Test
    fun sharedResponseKeepsDecodingBounded() =
        runTest {
            val finish = CompletableDeferred<Unit>()
            var active = 0
            var peak = 0
            val acquisition = RemoteGiphyAcquisition(backgroundScope, RemoteGiphyByteCache(1024)) { byteArrayOf(1) }
            val owner = Any()
            val rows =
                List(12) {
                    async {
                        acquisition.useBytes(owner, request(), { true }) {
                            active++
                            peak = maxOf(peak, active)
                            finish.await()
                            active--
                            Any()
                        }
                    }
                }
            runCurrent()
            assertEquals(6, active)
            finish.complete(Unit)
            rows.forEach { it.await() }
            assertEquals(6, peak)
        }

    /** A queued owner rechecks the shared byte cache after another owner finishes the same URL. */
    @Test
    fun queuedRequestUsesNewCacheEntryInsteadOfAnotherGet() =
        runTest {
            val response = CompletableDeferred<ByteArray>()
            var gets = 0
            val cache = RemoteGiphyByteCache(1024)
            val acquisition =
                RemoteGiphyAcquisition(backgroundScope, cache) {
                    gets++
                    response.await()
                }
            val first = List(6) { async { acquisition.useBytes(Any(), request(), { true }) { it } } }
            runCurrent()
            val queued = async { acquisition.useBytes(Any(), request(), { true }) { it } }
            runCurrent()
            assertEquals(6, gets)
            response.complete(byteArrayOf(4))
            first.forEach { it.await() }
            assertTrue(queued.await() != null)
            assertEquals(6, gets)
        }

    /** A cache hit needs no network consent, but an uncached row cannot join another row's permission. */
    @Test
    fun cacheHitsDoNotFetchAndUnconsentedRowsCannotJoinNetworkWork() =
        runTest {
            val response = CompletableDeferred<ByteArray>()
            var gets = 0
            val cache = RemoteGiphyByteCache(1024)
            val acquisition =
                RemoteGiphyAcquisition(backgroundScope, cache) {
                    gets++
                    response.await()
                }
            val owner = Any()
            val allowed = async { acquisition.useBytes(owner, request(), { true }) { it } }
            runCurrent()
            assertNull(acquisition.useBytes(owner, request(), { false }) { it })
            response.complete(byteArrayOf(5))
            val bytes = allowed.await()
            assertSame(bytes, acquisition.useBytes(owner, request(), { false }) { it })
            assertEquals(1, gets)
        }

    /** Revoked consent is rechecked after queuing, before the transport can be admitted. */
    @Test
    fun revokedQueuedPermissionDoesNotStartARequest() =
        runTest {
            val response = CompletableDeferred<ByteArray>()
            var gets = 0
            var allowed = true
            val acquisition =
                RemoteGiphyAcquisition(backgroundScope, RemoteGiphyByteCache(1024)) {
                    gets++
                    response.await()
                }
            val owner = Any()
            val blockers = List(6) { async { acquisition.useBytes(owner, request("$it"), { true }) { it } } }
            runCurrent()
            val queued = async { acquisition.useBytes(owner, request("queued"), { allowed }) { it } }
            runCurrent()
            allowed = false
            response.complete(byteArrayOf(6))
            blockers.forEach { it.await() }
            assertNull(queued.await())
            assertEquals(6, gets)
        }

    /** A replacement account/controller never joins the previous owner's live request. */
    @Test
    fun replacementOwnerDoesNotJoinOldFlightOrReceiveItsLateResult() =
        runTest {
            val oldResponse = CompletableDeferred<ByteArray>()
            var gets = 0
            val cache = RemoteGiphyByteCache(1024)
            val acquisition =
                RemoteGiphyAcquisition(backgroundScope, cache) {
                    if (++gets == 1) withContext(NonCancellable) { oldResponse.await() } else byteArrayOf(8)
                }
            val old = async { acquisition.useBytes(Any(), request(), { true }) { it } }
            runCurrent()
            old.cancelAndJoin()
            val current = acquisition.useBytes(Any(), request(), { true }) { it }
            oldResponse.complete(byteArrayOf(7))
            runCurrent()
            assertEquals(2, gets)
            assertSame(current, cache.get(request().url))
            assertEquals(8, current?.single()?.toInt())
        }

    /** Missing, oversized, empty and throwing transport results leave no poisoned cache or stuck flight. */
    @Test
    fun failedResponsesRemainRetryable() =
        runTest {
            var attempts = 0
            val cache = RemoteGiphyByteCache(1024)
            val acquisition =
                RemoteGiphyAcquisition(backgroundScope, cache) {
                    when (++attempts) {
                        1 -> null
                        2 -> byteArrayOf()
                        3 -> ByteArray(GIPHY_MAX_BODY_BYTES + 1)
                        4 -> throw IOException("fixture transport failure")
                        else -> byteArrayOf(9)
                    }
                }
            val owner = Any()
            repeat(3) {
                assertNull(acquisition.useBytes(owner, request(), { true }) { it })
                assertNull(cache.get(request().url))
            }
            val failure = runCatching { acquisition.useBytes(owner, request(), { true }) { it } }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertNull(cache.get(request().url))
            assertTrue(acquisition.useBytes(owner, request(), { true }) { it } != null)
            assertEquals(5, attempts)
        }

    /** Decode validation rejects malformed bytes and evicts a cached value that no longer decodes. */
    @Test
    fun invalidDecodeCannotPopulateOrKeepTheCache() =
        runTest {
            val cache = RemoteGiphyByteCache(1024)
            var gets = 0
            val acquisition =
                RemoteGiphyAcquisition(backgroundScope, cache) {
                    gets++
                    byteArrayOf(1)
                }
            val owner = Any()
            assertNull(acquisition.useBytes<Unit>(owner, request(), { true }) { null })
            assertNull(cache.get(request().url))
            assertTrue(acquisition.useBytes(owner, request(), { true }) { it } != null)
            assertNull(acquisition.useBytes<Unit>(owner, request(), { false }) { null })
            assertNull(cache.get(request().url))
            assertEquals(2, gets)
        }

    /** Production rows use the tested owner without weakening HTTPS, consent or local-only animation. */
    @Test
    fun everyGiphyRowUsesSharedAcquisitionAndLivePermission() {
        val source =
            listOf(File("src/main/java"), File("app/src/main/java"))
                .map { File(it, "dev/ipf/whitenoise/android/ui/conversation/media/RemoteGiphyMediaBubble.kt") }
                .first(File::exists)
                .readText()
        val row = source.functionBody("RemoteGiphyMediaBubble")
        assertTrue("giphyAcquisition.useBytes(" in row)
        assertTrue("mayDownload = { remoteGiphyNetworkAllowed(controller, manualRequest) }" in row)
        assertTrue("controller.ownsRetainedForegroundPresentation" in row)
        assertTrue("LaunchedEffect(identity, shouldLoad, networkAllowed, retryToken)" in row)
        assertTrue("!controller.ownsRetainedForegroundPresentation -> decoded?.releaseOwnedPresentation()" in row)
        assertFalse("SafeHttpsGet" in row)
        assertTrue("hostAllowed = { RemoteGiphyMedia.isAllowedMediaUrl" in source)
        assertTrue("maxBodyBytes = GIPHY_MAX_BODY_BYTES" in source)
        assertTrue("continuation.invokeOnCancellation { cancelRequest() }" in source)
    }

    /** Creates a valid rendition identity; transport is controlled by each test, never the public network. */
    private fun request(id: String = "same"): RemoteGiphyMedia.ImageRequest =
        RemoteGiphyMedia.ImageRequest("https://media.giphy.com/media/$id/giphy.gif", "image/gif")
}
