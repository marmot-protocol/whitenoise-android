package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.media.AttachmentPlaintext
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.Companion.reference
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.Companion.request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Integration assertions at the production Android/native boundary, not HTTP timing claims. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaDownloadHostRegressionTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var fixture: MediaDownloadIntegrationFixture

    /** Installs a deterministic main dispatcher and a private encrypted test cache. */
    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        fixture = MediaDownloadIntegrationFixture()
    }

    /** Releases only this test's work, files, and dispatcher. */
    @After
    fun tearDown() {
        fixture.close()
        Dispatchers.resetMain()
    }

    /** Sixteen distinct cold references share three slots; a late explicit request gets the next slot. */
    @Test
    fun distinctImageBacklogIsBoundedAndAnExplicitTapJoinsAndPromotesItsOwner() =
        runTest(dispatcher) {
            val owners = List(16) { index -> async { download(index) } }
            val first = List(3) { fixture.entered.receive() }
            fixture.awaitOwners(16)
            assertEquals(3, fixture.active.get())
            assertTrue(fixture.entered.tryReceive().isFailure)
            val tappedIndex = (0 until 16).last { index -> first.none { it.reference == reference(index) } }
            val tapped =
                fixture.state.memoizedDownload(
                    request(tappedIndex).cacheKey(),
                    request(tappedIndex),
                    AttachmentDownloadPriority.Interactive,
                ) {
                    error("The automatic owner must already be registered")
                }
            first.first().succeed(bytes(first.first().reference.fileName))
            val next = fixture.entered.receive()
            assertEquals(reference(tappedIndex), next.reference)
            assertEquals(3, fixture.active.get())
            (first.drop(1) + next).forEach { it.succeed(bytes(it.reference.fileName)) }
            repeat(12) {
                val call = fixture.entered.receive()
                call.succeed(bytes(call.reference.fileName))
            }
            owners.awaitAll().forEachIndexed { index, result ->
                assertArrayEquals(bytes(reference(index).fileName), result)
            }
            assertArrayEquals(bytes(reference(tappedIndex).fileName), tapped.await())
            assertEquals(3, fixture.peak.get())
            assertEquals(0, fixture.active.get())
            assertEquals(16, fixture.calls.size)
            assertEquals(
                16,
                fixture.calls
                    .map { it.reference }
                    .distinct()
                    .size,
            )
        }

    /** A delayed native result keeps other slots useful; Android never restarts the native operation. */
    @Test
    fun delayedNativeResultDoesNotBlockHealthySiblingsOrRenewTheOperation() =
        runTest(dispatcher) {
            val delayed = async { download(0) }
            val stalled = fixture.entered.receive()
            val healthy = List(15) { offset -> async { download(offset + 1) } }
            repeat(15) {
                val call = fixture.entered.receive()
                call.succeed(bytes(call.reference.fileName))
            }
            healthy.awaitAll()
            assertFalse(delayed.isCompleted)
            assertEquals(1, fixture.calls.count { it.reference == reference(0) })
            stalled.succeed(bytes(reference(0).fileName))
            assertArrayEquals(bytes(reference(0).fileName), delayed.await())
            assertTrue(fixture.peak.get() <= 3)
        }

    /** Composition disappearance cancels a waiter, not the shared producer used by a returning consumer. */
    @Test
    fun leavingAndReturningWhileLoadingReusesTheProducerAndCompletedPlaintext() =
        runTest(dispatcher) {
            val leaving = async { download(0) }
            val call = fixture.entered.receive()
            leaving.cancelAndJoin()
            val returning = async { download(0) }
            val sourceConsumer =
                async {
                    fixture.state.downloadAttachmentPlaintextSource(
                        request(0),
                        reference(0),
                        AttachmentDownloadPriority.Automatic,
                    )
                }
            runCurrent()
            val expected = bytes(reference(0).fileName)
            call.succeed(expected)
            assertArrayEquals(expected, returning.await())
            sourceConsumer.await().use { assertArrayEquals(expected, (it as AttachmentPlaintext.Bytes).bytes) }
            assertArrayEquals(expected, download(0))
            assertEquals(1, fixture.calls.size)
        }

    /** Timeout and integrity failures terminate once, leave both caches empty, and allow a later explicit retry. */
    @Test
    fun failedNativeOperationDoesNotPublishOrLoopBeforeAnExplicitRetry() =
        runTest(dispatcher) {
            listOf(
                MarmotKitException.Runtime("request timed out"),
                MarmotKitException.InvalidMediaReference("synthetic integrity failure"),
            ).forEachIndexed { index, failure ->
                val failed = async { runCatching { download(index) } }
                fixture.entered.receive().fail(failure)
                assertEquals(failure, failed.await().exceptionOrNull())
                runCurrent()
                assertEquals(index + 1, fixture.calls.size)
                assertNull(fixture.state.cachedMediaPlaintext(request(index).cacheKey()))
                assertNull(withContext(Dispatchers.IO) { fixture.disk.get(request(index).cacheKey()) })
            }
            val retry = async { download(0, AttachmentDownloadPriority.Interactive) }
            val expected = bytes(reference(0).fileName)
            fixture.entered.receive().succeed(expected)
            assertArrayEquals(expected, retry.await())
            assertEquals(3, fixture.calls.size)
        }

    /** A cold encrypted index still serves verified bytes without invoking the native boundary. */
    @Test
    fun encryptedDiskHitAndOtherAccountUseTheirOwnDownloadPaths() =
        runTest(dispatcher) {
            val expected = bytes(reference(0).fileName)
            withContext(Dispatchers.IO) { fixture.disk.put(request(0).cacheKey(), expected) }
            fixture.reopenDisk()
            assertArrayEquals(expected, download(0))
            assertEquals(0, fixture.calls.size)
            val other =
                async {
                    fixture.state.downloadAttachmentPlaintext(
                        request(0, "other-synthetic-account"),
                        reference(0),
                        AttachmentDownloadPriority.Automatic,
                    )
                }
            val call = fixture.entered.receive()
            assertEquals("other-synthetic-account", call.account)
            assertEquals(MediaDownloadIntegrationFixture.GROUP, call.group)
            call.succeed(byteArrayOf(9))
            assertArrayEquals(byteArrayOf(9), other.await())
            assertArrayEquals(expected, download(0))
        }

    /** Calls the production path with durable scheduling disabled only for synthetic explicit requests. */
    private suspend fun download(
        index: Int,
        priority: AttachmentDownloadPriority = AttachmentDownloadPriority.Automatic,
    ) = fixture.state.downloadAttachmentPlaintext(
        request(index),
        reference(index),
        priority,
        persistInteractiveIntent = false,
    )

    /** Distinct bounded payloads catch accidental cross-attachment result reuse. */
    private fun bytes(name: String): ByteArray = ByteArray(64 * 1024) { (name.hashCode() + it).toByte() }
}
