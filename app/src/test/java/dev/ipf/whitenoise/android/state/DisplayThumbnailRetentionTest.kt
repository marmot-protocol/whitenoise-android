package dev.ipf.whitenoise.android.state

import android.graphics.Bitmap
import dev.ipf.marmotkit.AttachmentTransferStateFfi
import dev.ipf.whitenoise.android.media.DiskByteCache
import dev.ipf.whitenoise.android.media.DiskByteCacheKeyProvider
import dev.ipf.whitenoise.android.media.DisplayThumbnailSource
import dev.ipf.whitenoise.android.media.MediaThumbnail
import dev.ipf.whitenoise.android.media.PersistentDisplayThumbnails
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.Companion.qualifiedRequest
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.Companion.reference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.io.IOException
import java.time.Duration
import javax.crypto.spec.SecretKeySpec

/** Unopened-chat cleanup uses real encrypted cache entries and authoritative, batched native metadata. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DisplayThumbnailRetentionTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var fixture: MediaDownloadIntegrationFixture
    private lateinit var bitmap: Bitmap

    /** Installs the shared native fixture with deterministic Main dispatch and private synthetic pixels. */
    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        fixture = MediaDownloadIntegrationFixture()
        bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
    }

    /** Drains cancelled fixture work before restoring the platform dispatcher. */
    @After
    fun tearDown() {
        fixture.closeAndDrain(dispatcher)
        bitmap.recycle()
        Dispatchers.resetMain()
    }

    /** A delete needs neither the former attachment count nor a previously loaded in-memory cache index. */
    @Test
    fun deletedMessageEvictsEveryIndexAfterRestart() =
        runTest(dispatcher) {
            val first = source(0)
            val second = first.copy(attachmentKey = qualifiedRequest(0).copy(attachmentIndex = 1).cacheKey())
            val neighbor = source(1)
            listOf(first, second, neighbor).forEach(::seed)
            val reopened = reopenCache()
            fixture.state.removeMessageDisplayThumbnails(
                qualifiedRequest(0).accountRef,
                qualifiedRequest(0).groupIdHex,
                qualifiedRequest(0).messageIdHex,
            )
            assertEquals(setOf(neighbor), reopened.storedSources())
        }

    /** MDK cleanup and missed deletion reclaim unloaded derivatives in one group batch without downloading. */
    @Test
    fun nativeCleanupRemovesOnlyAuthoritativelyRemovedSources() =
        runTest(dispatcher) {
            (0..5).map(::source).forEach(::seed)
            fixture.setNativePhase(0, AttachmentTransferStateFfi.CLEANED_BY_SETTING)
            fixture.setNativePhase(1, AttachmentTransferStateFfi.UNAVAILABLE)
            fixture.setNativePhase(2, AttachmentTransferStateFfi.PREVIOUSLY_ACQUIRED_UNAVAILABLE)
            fixture.setNativePhase(3, AttachmentTransferStateFfi.COMPLETED_UNRETAINED)
            fixture.setNativePhase(4, AttachmentTransferStateFfi.FAILED)
            var snapshots = 0
            fixture.onNativeSnapshot = { snapshots++ }
            fixture.state.reconcileDisplayThumbnails()
            assertEquals(setOf(source(4), source(5)), fixture.thumbnails.storedSources())
            assertEquals(1, snapshots)
            assertEquals(0, fixture.nativeAssetReads.get())
            assertTrue(fixture.calls.isEmpty())
        }

    /** A foreground return shortly after a complete pass does not authenticate the whole cache again. */
    @Test
    fun foregroundReturnReusesARecentCompletePass() =
        runTest(dispatcher) {
            seed(source(0))
            fixture.state.reconcileDisplayThumbnails()
            assertNull(fixture.state.scheduleForegroundDisplayThumbnailReconciliation())
            ShadowSystemClock.advanceBy(Duration.ofMinutes(16))
            assertNotNull(fixture.state.scheduleForegroundDisplayThumbnailReconciliation())
        }

    /** Deletes fenced together are swept by one pass, after the caller has released its commit lock. */
    @Test
    fun queuedDeletesShareOneSweep() =
        runTest(dispatcher) {
            listOf(source(0), source(1), source(2)).forEach(::seed)
            listOf(qualifiedRequest(0), qualifiedRequest(1)).forEach {
                fixture.state.fenceMessageDisplayThumbnails(it.accountRef, it.groupIdHex, it.messageIdHex)
            }
            assertEquals(setOf(source(0), source(1), source(2)), fixture.thumbnails.storedSources())
            fixture.state.sweepRemovedMessageDisplayThumbnails()
            assertEquals(setOf(source(2)), fixture.thumbnails.storedSources())
        }

    /** A pass whose native read failed does not let the next foreground return skip reconciliation. */
    @Test
    fun failedNativeReadDoesNotCountAsAFreshPass() =
        runTest(dispatcher) {
            seed(source(0))
            fixture.onNativeSnapshot = { throw IOException("synthetic unavailable read") }
            fixture.state.reconcileDisplayThumbnails()
            assertNotNull(fixture.state.scheduleForegroundDisplayThumbnailReconciliation())
        }

    /** Every state in which MDK keeps no retained body rejects the derivative; transfers in progress keep it. */
    @Test
    fun onlyStatesWithoutARetainedBodyRemoveTheDerivative() {
        val removed =
            setOf(
                AttachmentTransferStateFfi.UNAVAILABLE,
                AttachmentTransferStateFfi.REMOVED,
                AttachmentTransferStateFfi.CLEANED_BY_SETTING,
                AttachmentTransferStateFfi.PREVIOUSLY_ACQUIRED_UNAVAILABLE,
                AttachmentTransferStateFfi.COMPLETED_UNRETAINED,
            )
        assertEquals(removed, AttachmentTransferStateFfi.entries.filter { it.removesDisplayThumbnail() }.toSet())
    }

    /** Native read failure leaves cache data for a later authoritative answer. */
    @Test
    fun failedNativeReadPreservesTheDerivative() =
        runTest(dispatcher) {
            seed(source(0))
            fixture.onNativeSnapshot = { throw IOException("synthetic unavailable read") }
            fixture.state.reconcileDisplayThumbnails()
            assertEquals(setOf(source(0)), fixture.thumbnails.storedSources())
        }

    /** A retired native owner cannot remove data based on its late snapshot. */
    @Test
    fun runtimeReplacementRejectsLateCleanup() =
        runTest(dispatcher) {
            seed(source(0))
            fixture.setNativePhase(0, AttachmentTransferStateFfi.CLEANED_BY_SETTING)
            fixture.onNativeSnapshot = { field("marmotRuntime").set(fixture.state, null) }
            fixture.state.reconcileDisplayThumbnails()
            assertEquals(setOf(source(0)), fixture.thumbnails.storedSources())
        }

    /** Valid imported account labels can contain the same delimiter used by the host's cache keys. */
    @Test
    fun accountLabelDelimitersRemainPartOfTheAccountIdentity() {
        val request = qualifiedRequest().copy(accountRef = "alice|phone")
        val source = source(0).copy(attachmentKey = request.cacheKey())
        assertEquals(request, source.transferRequest())
    }

    /** Writes a verified cache fixture with an explicit native source locator. */
    private fun seed(source: DisplayThumbnailSource) {
        fixture.thumbnails.write(source, MediaThumbnail(bitmap, 128), fixture.thumbnails.captureGeneration(source))
    }

    /** Reopens the real encrypted directory with the fixture-owned software key. */
    private fun reopenCache(): PersistentDisplayThumbnails {
        val reopened =
            PersistentDisplayThumbnails(
                DiskByteCache(
                    fixture.thumbnailRoot,
                    maxBytes = 4L * 1024 * 1024,
                    keyProvider = DiskByteCacheKeyProvider { SecretKeySpec(ByteArray(32) { 7 }, "AES") },
                ),
            )
        field("displayThumbnails").set(fixture.state, reopened)
        return reopened
    }

    /** Uses the same authoritative source identity as the native-boundary fixture. */
    private fun source(index: Int): DisplayThumbnailSource =
        DisplayThumbnailSource(
            qualifiedRequest(index).cacheKey(),
            reference(index).sourceEpoch,
            reference(index).ciphertextSha256,
            qualifiedRequest(index).sourceMessageIdHex,
        )

    /** Uses the existing fixture's private field-injection convention. */
    private fun field(name: String): java.lang.reflect.Field {
        val field = WhiteNoiseAppState::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field
    }
}
