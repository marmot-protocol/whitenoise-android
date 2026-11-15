package dev.ipf.whitenoise.android.ui.conversation.media

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MediaAutoDownloadNetwork
import dev.ipf.whitenoise.android.state.MediaAutoDownloadType
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.Companion.reference
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.Companion.request
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.clearMediaDiskCaches
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.state.localTimelineMessage
import dev.ipf.whitenoise.android.state.timelineAppMessage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
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

/** Production bubble ownership while the encoder is busy or a cache removal races an original load. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaDisplayThumbnailPersistenceTest {
    @get:Rule val composeRule = createComposeRule()
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
