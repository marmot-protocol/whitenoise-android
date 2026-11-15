package dev.ipf.whitenoise.android.ui.conversation.media

import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.unit.IntSize
import dev.ipf.marmotkit.AttachmentTransferStateFfi
import dev.ipf.whitenoise.android.state.AttachmentOpenDestination
import dev.ipf.whitenoise.android.state.AttachmentTransferRequest
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MediaAutoDownloadNetwork
import dev.ipf.whitenoise.android.state.MediaAutoDownloadType
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.Companion.reference
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.Companion.request
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.cacheKey
import dev.ipf.whitenoise.android.state.clearMediaDiskCaches
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.state.localTimelineMessage
import dev.ipf.whitenoise.android.state.timelineAppMessage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Production bubble ownership while the encoder is busy or a cache removal races an original load. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaDisplayThumbnailPersistenceTest {
    private val effectJobs = MainLooperEffectJobs()

    @get:Rule val composeRule = createComposeRule(effectContext = effectJobs)
    private lateinit var fixture: MediaDownloadIntegrationFixture
    private lateinit var controller: ConversationController

    /** Keeps MDK's idle retention feed readable while using the real host cache and bubble. */
    @Before
    fun setUp() {
        fixture = MediaDownloadIntegrationFixture(retainIdleNativeFeeds = true)
        controller =
            ConversationController(
                appState = fixture.state,
                initialGroup = conversationTimelineTestGroup().copy(groupIdHex = MediaDownloadIntegrationFixture.GROUP),
            )
    }

    /** Releases this fixture's transfers and private cached bytes. */
    @After
    fun tearDown() = fixture.close()

    /** A still-visible L1 image waits for an occupied encoder and persists when that encoder becomes free. */
    @Test
    fun busyEncoderEventuallyPersistsAVisibleMemoryHit() {
        runBlocking { fixture.state.displayThumbnailWritePermit.acquire() }
        controller.cacheThumbnail(request(0).messageIdHex, 0, Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888))
        composeRule.setContent { WhiteNoiseTheme { Bubble() } }
        awaitLoaded()
        assertEquals(0L, fixture.thumbnails.residentBytes())
        fixture.state.displayThumbnailWritePermit.release()
        composeRule.waitUntil(10_000) {
            composeRule.waitForIdle()
            fixture.thumbnails.residentBytes() > 0L
        }
        assertEquals(0, fixture.nativeAssetReads.get())
    }

    /** Disposing a visible image cancels its queued persistence before the encoder is released. */
    @Test
    fun disposedMemoryHitCannotWriteAfterWaitingForTheEncoder() {
        runBlocking { fixture.state.displayThumbnailWritePermit.acquire() }
        controller.cacheThumbnail(request(0).messageIdHex, 0, Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888))
        var shown by mutableStateOf(true)
        composeRule.setContent { WhiteNoiseTheme { if (shown) Bubble() } }
        awaitLoaded()
        composeRule.runOnIdle { shown = false }
        composeRule.waitForIdle()
        fixture.state.displayThumbnailWritePermit.release()
        runBlocking {
            fixture.state.displayThumbnailWritePermit.acquire()
            fixture.state.displayThumbnailWritePermit.release()
        }
        assertEquals(0L, fixture.thumbnails.residentBytes())
    }

    /** A cache-only wipe during a native original read must restart the current loader. */
    @Test
    fun clearDuringAnOriginalLoadCannotLeaveTheBubbleBlank() {
        enableAutomaticImages()
        composeRule.setContent { WhiteNoiseTheme { Bubble() } }
        composeRule.waitUntil(10_000) {
            composeRule.waitForIdle()
            fixture.calls.size == 1
        }
        runBlocking(kotlinx.coroutines.Dispatchers.IO) { fixture.state.clearMediaDiskCaches() }
        composeRule.runOnIdle { fixture.calls.single().succeed(imageBytes()) }
        awaitLoaded()
        assertEquals(1, fixture.calls.size)
    }

    /** Cleanup retires the previous oversized-source affordance before offering a fresh download. */
    @Test
    fun nativeCleanupClearsTheOversizedPreviewState() {
        fixture.retainNatively(0, imageBytes())
        fixture.nativeAssetDeclaredBytes = Long.MAX_VALUE.toULong()
        var loadState: ImageLoadState? = null
        composeRule.setContent {
            WhiteNoiseTheme {
                loadState = ObserveLoad()
            }
        }
        composeRule.runOnIdle { checkNotNull(loadState).decodeRequest.onSizeChanged(IntSize(128, 128)) }
        composeRule.waitUntil(10_000) {
            composeRule.waitForIdle()
            loadState?.tooLargeToPreview == true
        }
        composeRule.runOnIdle { fixture.setNativePhase(0, AttachmentTransferStateFfi.CLEANED_BY_SETTING) }
        composeRule.waitUntil(10_000) {
            composeRule.waitForIdle()
            loadState?.transfer?.progress?.phase == AttachmentTransferStateFfi.CLEANED_BY_SETTING &&
                loadState?.tooLargeToPreview == false
        }
        assertEquals(0, fixture.calls.size)
        assertEquals(0, fixture.nativeAssetReads.get())
    }

    /** A user request for a cleaned original reaches durable scheduling while passive loading stays suppressed. */
    @Test
    fun cleanedOriginalDownloadIntentEnqueuesInteractiveWork() {
        fixture.setNativePhase(0, AttachmentTransferStateFfi.CLEANED_BY_SETTING)
        val transfer = armOriginalDownload()
        composeRule.setContent { WhiteNoiseTheme { Bubble() } }
        composeRule.waitUntil(10_000) {
            composeRule.waitForIdle()
            fixture.state.hasInteractive(transfer)
        }
        assertTrue(controller.hasAttachmentDownloadIntent(request(0).messageIdHex, 0))
        assertEquals(0, fixture.calls.size)
        assertEquals(0, fixture.nativeAssetReads.get())
    }

    /** Leaving while original eviction is non-cancellable cannot restore a reader's revoked download. */
    @Test
    fun cancelledCleanupCannotReenqueueTheOriginalDownload() {
        fixture.setNativePhase(0, AttachmentTransferStateFfi.CLEANED_BY_SETTING)
        val transfer = armOriginalDownload()
        var shown by mutableStateOf(true)
        var loadState: ImageLoadState? = null
        composeRule.setContent { WhiteNoiseTheme { if (shown) loadState = ObserveLoad() } }
        // Hold layout admission until the persisted reader intent has reached the mounted loader.
        composeRule.waitUntil(10_000) {
            composeRule.waitForIdle()
            loadState?.startDownload == true
        }
        fixture.disk.put(request(0).cacheKey(), imageBytes())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cleanupOwners = ConcurrentHashMap.newKeySet<Job>()
        fixture.onNativeSnapshot = { cleanupOwners += effectJobs.currentMainOwner() }
        fixture.onBeforeOriginalRemoval = {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "Synthetic original removal was not released" }
        }
        try {
            composeRule.runOnIdle { checkNotNull(loadState).decodeRequest.onSizeChanged(IntSize(128, 128)) }
            composeRule.waitUntil(10_000) {
                composeRule.waitForIdle()
                entered.count == 0L
            }
            assertTrue(cleanupOwners.isNotEmpty())
            composeRule.runOnIdle {
                val openRequest = checkNotNull(fixture.state.attachmentOpens.openRequest(transfer))
                fixture.state.attachmentOpens.cancelOpen(openRequest)
                fixture.state.clearInteractiveAttachmentDownloadIntent(transfer)
                shown = false
            }
            composeRule.waitForIdle()
            release.countDown()
            composeRule.waitUntil(10_000) {
                composeRule.waitForIdle()
                cleanupOwners.all { it.isCompleted }
            }
            composeRule.runOnIdle {
                assertTrue(!controller.hasAttachmentDownloadIntent(request(0).messageIdHex, 0))
                assertTrue(!fixture.state.hasInteractive(transfer))
            }
            assertEquals(0, fixture.calls.size)
        } finally {
            release.countDown()
        }
    }

    /** Preserves foreground demand while modeling scheduler registration that must be restored by the loader. */
    private fun armOriginalDownload(): AttachmentTransferRequest {
        fixture.state.attachmentOpens.setDestination(
            AttachmentOpenDestination(
                accountRef = MediaDownloadIntegrationFixture.ACCOUNT,
                groupIdHex = MediaDownloadIntegrationFixture.GROUP,
                navigationGeneration = 1L,
            ),
        )
        val transfer = MediaDownloadIntegrationFixture.qualifiedRequest(0)
        assertTrue(controller.requestAttachmentDownload(request(0).messageIdHex, 0))
        fixture.state.clearInteractiveAttachmentDownloadIntent(transfer)
        assertTrue(!fixture.state.hasInteractive(transfer))
        assertTrue(controller.hasAttachmentDownloadIntent(request(0).messageIdHex, 0))
        return transfer
    }

    /** Captures the actual Compose owners before native metadata reads dispatch to IO. */
    private class MainLooperEffectJobs :
        AbstractCoroutineContextElement(Key),
        ThreadContextElement<Job?> {
        private val current = ThreadLocal<Job?>()
        private val mainJobs = ConcurrentHashMap.newKeySet<Job>()

        /** Remembers jobs executing on Main before their IO children run the fake binding. */
        override fun updateThreadContext(context: CoroutineContext): Job? {
            val previous = current.get()
            val job = context[Job]
            current.set(job)
            if (job != null && Looper.myLooper() == Looper.getMainLooper()) mainJobs += job
            return previous
        }

        /** Restores the prior coroutine when a dispatcher leaves this thread. */
        override fun restoreThreadContext(
            context: CoroutineContext,
            oldState: Job?,
        ) {
            current.set(oldState)
        }

        /** Finds the nearest captured Main ancestor, the effect that owns this particular native read. */
        @OptIn(ExperimentalCoroutinesApi::class)
        fun currentMainOwner(): Job {
            var job = checkNotNull(current.get()) { "the binding did not inherit its Compose effect context" }
            while (job !in mainJobs) {
                job = checkNotNull(job.parent) { "the read has no captured Main effect owner" }
            }
            return job
        }

        private companion object Key : CoroutineContext.Key<MainLooperEffectJobs>
    }

    /** Mounts the production loader with layout admission controlled separately by the test. */
    @Composable
    private fun ObserveLoad(): ImageLoadState =
        rememberImageLoadState(
            controller = controller,
            appState = fixture.state,
            messageIdHex = request(0).messageIdHex,
            attachmentIndex = 0,
            reference = reference(0),
            mine = false,
            decodeKeys = listOf(controller, request(0).messageIdHex),
            fallbackAspectRatio = 1f,
            intentIdentity = "thumbnail-cleanup-source",
            failureLogTag = "ThumbnailPersistenceTest",
            failureLogMessage = "synthetic failure",
        )

    /** Mounts the shipping image path with a synthetic received row. */
    @Composable
    private fun Bubble() {
        MediaImageBubble(
            item = localTimelineMessage(timelineAppMessage(request(0).messageIdHex)),
            reference = reference(0),
            attachmentIndex = 0,
            controller = controller,
            appState = fixture.state,
            onOpenConversationMedia = {},
            mine = false,
        )
    }

    /** Waits for actual displayed-image semantics before changing encoder ownership. */
    private fun awaitLoaded() {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithContentDescription(reference(0).fileName).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Makes the synthetic acquisition policy eligible without broadcasting a device network change. */
    private fun enableAutomaticImages() {
        WhiteNoiseAppState::class.java
            .getDeclaredField("activeNetworkTypesSnapshot")
            .apply {
                isAccessible = true
            }.set(fixture.state, setOf(MediaAutoDownloadNetwork.WiFi))
        fixture.state.setMediaAutoDownload(MediaAutoDownloadType.Image, MediaAutoDownloadNetwork.WiFi, true)
        assertTrue(fixture.state.shouldAutoDownloadMedia(MediaAutoDownloadType.Image))
    }

    /** Supplies a valid small native body for the clear-during-load regression. */
    private fun imageBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
