package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.media.AttachmentTooLargeToPresentException
import dev.ipf.whitenoise.android.media.DiskByteCache
import dev.ipf.whitenoise.android.media.DiskByteCacheKeyProvider
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.Companion.request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import javax.crypto.spec.SecretKeySpec

/**
 * The cache-only image read over the encrypted disk cache stays within the presentation budget: an entry too large
 * for the L1 cache is authenticated into a lease and copied within the budget, never read as one whole array.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CachedAttachmentPlaintextBoundedReadTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var fixture: MediaDownloadIntegrationFixture
    private lateinit var controller: ConversationController
    private lateinit var cacheRoot: File

    /** Installs a disk cache whose L1 promotion ceiling is tiny, so a modest entry already takes the lease path. */
    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        fixture = MediaDownloadIntegrationFixture()
        controller =
            ConversationController(
                appState = fixture.state,
                initialGroup = conversationTimelineTestGroup().copy(groupIdHex = MediaDownloadIntegrationFixture.GROUP),
            )
        cacheRoot = Files.createTempDirectory("cached-plaintext-bounded").toFile()
        val cache =
            DiskByteCache(
                cacheDir = File(cacheRoot, "encrypted"),
                maxBytes = 4L * 1024L * 1024L,
                keyProvider = DiskByteCacheKeyProvider { SecretKeySpec(ByteArray(32) { 3 }, "AES") },
                maxInMemoryEntryBytes = SMALL_ENTRY_BYTES.toLong(),
            )
        WhiteNoiseAppState::class.java
            .getDeclaredField("diskMediaCache")
            .apply { isAccessible = true }
            .set(fixture.state, cache)
    }

    /** Releases the fixture, the main dispatcher and the private cache directory. */
    @After
    fun tearDown() {
        fixture.close()
        cacheRoot.deleteRecursively()
        Dispatchers.resetMain()
    }

    /** An entry above the budget is rejected by its size, leaving no plaintext lease behind. */
    @Test
    fun aDiskEntryAboveTheBudgetIsRejectedWithoutALeftoverLease() =
        runTest(dispatcher) {
            fixture.state.diskMediaCache.put(request(0).cacheKey(), ByteArray(LARGE_ENTRY_BYTES) { it.toByte() })

            val failure =
                runCatching {
                    controller.cachedAttachmentPlaintext(request(0).messageIdHex, 0, maxBytes = BUDGET_BYTES)
                }.exceptionOrNull()

            assertTrue(
                "expected a typed too-large failure, got $failure",
                failure is AttachmentTooLargeToPresentException,
            )
            assertEquals(LARGE_ENTRY_BYTES.toLong(), (failure as AttachmentTooLargeToPresentException).declaredBytes)
            assertEquals("a plaintext lease was left on disk", emptyList<String>(), leaseFileNames())
        }

    /** An entry too large for L1 but within the budget is read through a lease that is deleted afterwards. */
    @Test
    fun aDiskEntryWithinTheBudgetIsReadThroughALease() =
        runTest(dispatcher) {
            val bytes = ByteArray(SMALL_ENTRY_BYTES * 2) { (it * 5).toByte() }
            fixture.state.diskMediaCache.put(request(0).cacheKey(), bytes)

            val read = controller.cachedAttachmentPlaintext(request(0).messageIdHex, 0, maxBytes = BUDGET_BYTES)

            assertArrayEquals(bytes, read)
            assertEquals(emptyList<String>(), leaseFileNames())
        }

    /** An entry small enough for L1 is still read directly. */
    @Test
    fun aSmallDiskEntryIsReadDirectly() =
        runTest(dispatcher) {
            val bytes = ByteArray(SMALL_ENTRY_BYTES / 2) { (it * 7).toByte() }
            fixture.state.diskMediaCache.put(request(0).cacheKey(), bytes)

            val read = controller.cachedAttachmentPlaintext(request(0).messageIdHex, 0, maxBytes = BUDGET_BYTES)

            assertArrayEquals(bytes, read)
        }

    /** Names of ephemeral lease files under the encrypted cache directory, which must be empty between reads. */
    private fun leaseFileNames(): List<String> =
        File(cacheRoot, "encrypted")
            .listFiles()
            .orEmpty()
            .filter { "lease" in it.name }
            .map { it.name }

    private companion object {
        const val SMALL_ENTRY_BYTES = 1024
        const val BUDGET_BYTES = 4096L
        const val LARGE_ENTRY_BYTES = 4097
    }
}
