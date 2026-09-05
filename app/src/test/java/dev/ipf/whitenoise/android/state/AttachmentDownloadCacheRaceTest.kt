package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises cache publication races against the actual process-owned download gate. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AttachmentDownloadCacheRaceTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var appState: WhiteNoiseAppState

    /** Creates only local test state; the native runtime must never be reached. */
    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        appState =
            WhiteNoiseAppState(
                context = ApplicationProvider.getApplicationContext<Context>(),
                draftStore = DraftStore(EmptyDraftPersistence),
                accountIdHexResolver = { null },
                accounts = emptyList(),
                activeAccountRef = "sample-account",
            )
    }

    /** Restores the shared dispatcher after every bounded scenario. */
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** A late cache-miss caller must reuse bytes published by a completed owner. */
    @Test
    fun staleCacheMissAfterOwnerCompletionDoesNotDownloadAgain() =
        runTest(dispatcher) {
            val request = request("completed")
            val bytes = byteArrayOf(1, 2, 3)
            var downloads = 0
            val first =
                appState.memoizedDownload(request.cacheKey(), request, AttachmentDownloadPriority.Automatic) {
                    downloads++
                    appState.cacheMediaPlaintext(request.cacheKey(), bytes)
                    bytes
                }
            assertSame(bytes, first.await())

            // The second caller already observed a miss before the first owner
            // published; it arrives at admission after the in-flight entry retires.
            val late =
                appState.memoizedDownload(request.cacheKey(), request, AttachmentDownloadPriority.Automatic) {
                    downloads++
                    bytes
                }
            assertSame(bytes, late.await())
            assertEquals(1, downloads)
        }

    /** A queued miss must recheck bytes that became available while all slots were busy. */
    @Test
    fun cachePublicationWhileQueuedSkipsRemoteWork() =
        runTest(dispatcher) {
            val release = CompletableDeferred<Unit>()
            val allHoldersStarted = CompletableDeferred<Unit>()
            var startedHolders = 0
            val holders =
                List(3) { index ->
                    val holder = request("holder-$index")
                    appState.memoizedDownload(holder.cacheKey(), holder, AttachmentDownloadPriority.Automatic) {
                        if (++startedHolders == 3) allHoldersStarted.complete(Unit)
                        release.await()
                        byteArrayOf(7)
                    }
                }
            allHoldersStarted.await()
            val request = request("queued")
            val bytes = byteArrayOf(4, 5, 6)
            var downloads = 0
            val queued =
                appState.memoizedDownload(request.cacheKey(), request, AttachmentDownloadPriority.Automatic) {
                    downloads++
                    bytes
                }
            runCurrent()
            assertFalse(queued.isCompleted)
            appState.cacheMediaPlaintext(request.cacheKey(), bytes)
            release.complete(Unit)
            holders.forEach { it.await() }
            assertSame(bytes, queued.await())
            assertEquals(0, downloads)
        }

    /** Automatic and interactive callers still share the one in-flight owner. */
    @Test
    fun concurrentCallersShareOneDownload() =
        runTest(dispatcher) {
            val request = request("shared")
            val release = CompletableDeferred<Unit>()
            val bytes = byteArrayOf(1, 2, 3)
            var downloads = 0
            val first =
                appState.memoizedDownload(request.cacheKey(), request, AttachmentDownloadPriority.Automatic) {
                    downloads++
                    release.await()
                    bytes
                }
            val second =
                appState.memoizedDownload(request.cacheKey(), request, AttachmentDownloadPriority.Interactive) {
                    error("Must share the active owner")
                }
            assertSame(first, second)
            release.complete(Unit)
            assertSame(bytes, first.await())
            assertSame(bytes, second.await())
            assertEquals(1, downloads)
        }

    /** A cache entry from another account cannot suppress this account's transfer. */
    @Test
    fun anotherAccountsCacheDoesNotBypassDownload() =
        runTest(dispatcher) {
            val request = request("scoped")
            val otherAccount = request.copy(accountRef = "other-account")
            appState.cacheMediaPlaintext(otherAccount.cacheKey(), byteArrayOf(9))
            val bytes = byteArrayOf(1, 2, 3)
            var downloads = 0
            val result =
                appState.memoizedDownload(request.cacheKey(), request, AttachmentDownloadPriority.Automatic) {
                    downloads++
                    bytes
                }
            assertSame(bytes, result.await())
            assertEquals(1, downloads)
        }

    /** A failed owner is retired without hiding a later explicitly requested retry. */
    @Test
    fun failedDownloadCanBeRetried() =
        runTest(dispatcher) {
            val request = request("retry")
            var downloads = 0
            val failed =
                appState.memoizedDownload(request.cacheKey(), request, AttachmentDownloadPriority.Automatic) {
                    downloads++
                    throw java.io.IOException("synthetic transfer failure")
                }
            assertTrue(runCatching { failed.await() }.exceptionOrNull() is java.io.IOException)
            val bytes = byteArrayOf(1, 2, 3)
            val retry =
                appState.memoizedDownload(request.cacheKey(), request, AttachmentDownloadPriority.Interactive) {
                    downloads++
                    bytes
                }
            assertSame(bytes, retry.await())
            assertEquals(2, downloads)
        }

    /** Returns an account/group-scoped synthetic attachment identity. */
    private fun request(message: String): AttachmentTransferRequest =
        AttachmentTransferRequest(
            accountRef = "sample-account",
            groupIdHex = "sample-group",
            messageIdHex = message,
            attachmentIndex = 0,
        )

    /** Keeps the fixture independent of persisted drafts and native initialization. */
    private object EmptyDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }
}
