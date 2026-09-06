package dev.ipf.whitenoise.android.ui.conversation.media

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MediaAutoDownloadNetwork
import dev.ipf.whitenoise.android.state.MediaAutoDownloadType
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.Companion.reference
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.Companion.request
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.cacheKey
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.state.localTimelineMessage
import dev.ipf.whitenoise.android.state.timelineAppMessage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Mounts the production image bubble and measures behavior, not elapsed JVM rendering time. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaImageBubbleLatencyTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var fixture: MediaDownloadIntegrationFixture
    private lateinit var controller: ConversationController

    /** Uses the real controller with only its native download boundary replaced. */
    @Before
    fun setUp() {
        fixture = MediaDownloadIntegrationFixture()
        controller =
            ConversationController(
                appState = fixture.state,
                initialGroup = conversationTimelineTestGroup().copy(groupIdHex = MediaDownloadIntegrationFixture.GROUP),
            )
        enableAutomaticImages()
        fixture.state.setMediaAutoDownload(MediaAutoDownloadType.Image, MediaAutoDownloadNetwork.WiFi, false)
    }

    /** Cancels fixture transfers and removes its private temporary cache. */
    @After
    fun tearDown() {
        fixture.close()
    }

    /** Freezing the frame clock prevents an asynchronous decode from disguising a first-frame cache regression. */
    @Test
    fun decodedThumbnailIsVisibleBeforeAnyFollowUpFrame() {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        composeRule.mainClock.autoAdvance = false
        controller.cacheThumbnail(request(0).messageIdHex, 0, bitmap)
        composeRule.setContent { WhiteNoiseTheme { Bubble() } }

        assertLoaded()
        assertEquals(0, fixture.calls.size)
    }

    /** A cold encrypted index leads to decoded pixels, never a network call or a stale retry affordance. */
    @Test
    fun encryptedDiskHitRendersAndReopeningUsesItsDecodedThumbnail() {
        verifyColdDiskRendering(false, LayoutDirection.Ltr, "media_image_cold_cache_light.png")
    }

    /** The same retained-image behavior holds with dark colors and right-to-left layout. */
    @Test
    fun encryptedDiskHitRendersInDarkRtlLayout() {
        verifyColdDiskRendering(true, LayoutDirection.Rtl, "media_image_cold_cache_dark_rtl.png")
    }

    /** A genuine miss must still respect a disabled automatic-download policy after index hydration. */
    @Test
    fun coldCacheMissDoesNotGrantNetworkPermission() {
        composeRule.setContent { WhiteNoiseTheme { Bubble() } }
        runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            fixture.state.diskMediaCache.containsAfterHydration(request(0).cacheKey())
        }
        composeRule.waitForIdle()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        composeRule.onNodeWithContentDescription(context.getString(R.string.media_tap_to_download)).assertIsDisplayed()
        assertEquals(0, fixture.calls.size)
    }

    /** An index hit whose ciphertext fails authentication must not silently become an unauthorized fetch. */
    @Test
    fun corruptCachedPayloadDoesNotBypassDisabledAutomaticDownloads() {
        verifyCorruptDiskPolicy(grid = false)
    }

    /** Album tiles must enforce the same cache-only boundary as a standalone image. */
    @Test
    fun corruptAlbumTileDoesNotBypassDisabledAutomaticDownloads() {
        verifyCorruptDiskPolicy(grid = true)
    }

    /** A policy grant arriving during a rejected local read must start the newly authorized download. */
    @Test
    fun enablingAutomaticDownloadsDuringRejectedCacheReadResumesTheImage() {
        verifyPolicyGrantDuringCacheRead(grid = false)
    }

    /** Album tiles cannot lose a policy grant while waiting for local authentication. */
    @Test
    fun enablingAutomaticDownloadsDuringRejectedCacheReadResumesTheAlbumTile() {
        verifyPolicyGrantDuringCacheRead(grid = true)
    }

    /** Holds the real encrypted read after index hydration, then changes policy before its failure returns. */
    private fun verifyPolicyGrantDuringCacheRead(grid: Boolean) {
        fixture.disk.put(request(0).cacheKey(), imageBytes())
        fixture.corruptPayload()
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val hold = AtomicBoolean(false)
        fixture.reopenDisk {
            if (hold.get()) {
                reading.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "Synthetic cache read was not released" }
            }
        }
        runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            assertTrue(fixture.state.diskMediaCache.containsAfterHydration(request(0).cacheKey()))
        }
        hold.set(true)
        try {
            composeRule.setContent { WhiteNoiseTheme { if (grid) GridTile(0) else Bubble() } }
            composeRule.waitUntil(10_000) {
                composeRule.waitForIdle()
                reading.count == 0L
            }
            composeRule.runOnIdle { enableAutomaticImages() }
            composeRule.runOnIdle {
                hold.set(false)
                release.countDown()
            }
            composeRule.waitUntil(10_000) {
                composeRule.waitForIdle()
                fixture.calls.size == 1
            }
            composeRule.runOnIdle { fixture.calls.single().succeed(imageBytes()) }
            awaitLoaded()
            assertEquals(1, fixture.calls.size)
        } finally {
            hold.set(false)
            release.countDown()
        }
    }

    /** Each visible album tile hydrates its own cached bytes with automatic downloads disabled. */
    @Test
    fun coldCachedAlbumRendersDistinctTilesWithoutNativeDownloads() {
        repeat(3) { fixture.disk.put(request(it).cacheKey(), imageBytes()) }
        fixture.reopenDisk()
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Box(Modifier.width(240.dp).testTag("cached-image-album")) {
                    MasonryImageLayout(visibleCount = 3) { index, modifier -> GridTile(index, modifier) }
                }
            }
        }
        composeRule.waitUntil(10_000) {
            (0 until 3).all {
                composeRule.onAllNodesWithContentDescription(reference(it).fileName).fetchSemanticsNodes().isNotEmpty()
            }
        }
        repeat(3) { composeRule.onNodeWithContentDescription(reference(it).fileName).assertIsDisplayed() }
        assertEquals(0, fixture.calls.size)
        composeRule
            .onNodeWithTag("cached-image-album")
            .captureRoboImage("src/test/snapshots/media_album_cold_cache_light.png")
    }

    /** Waits for real authentication rejection before asserting that no network operation began. */
    private fun verifyCorruptDiskPolicy(grid: Boolean) {
        fixture.disk.put(request(0).cacheKey(), imageBytes())
        val corrupt = fixture.corruptPayload()
        fixture.reopenDisk()
        fixture.onDownload = { it.fail(IllegalStateException("Unexpected cache-only network request")) }
        composeRule.setContent { WhiteNoiseTheme { if (grid) GridTile(0) else Bubble() } }
        composeRule.waitUntil(10_000) {
            composeRule.waitForIdle()
            !corrupt.exists()
        }
        composeRule.waitUntil(10_000) {
            fixture.calls.isNotEmpty() ||
                composeRule
                    .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
                    .fetchSemanticsNodes()
                    .isEmpty()
        }
        assertEquals(0, fixture.calls.size)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        composeRule.onNodeWithContentDescription(context.getString(R.string.media_tap_to_download)).assertIsDisplayed()
    }

    /** Exercises the cold production cache path, records its visible result, and remounts the same conversation. */
    private fun verifyColdDiskRendering(
        dark: Boolean,
        direction: LayoutDirection,
        snapshot: String,
    ) {
        val bytes = imageBytes()
        fixture.disk.put(request(0).cacheKey(), bytes)
        fixture.reopenDisk()
        var shown by mutableStateOf(true)
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    if (shown) Box(Modifier.width(240.dp).testTag("image-latency-bubble")) { Bubble() }
                }
            }
        }

        awaitLoaded()
        composeRule.onNodeWithTag("image-latency-bubble").captureRoboImage("src/test/snapshots/$snapshot")
        composeRule.runOnIdle {
            assertEquals(0, fixture.calls.size)
            assertTrue(controller.thumbnailFor(request(0).messageIdHex, 0) != null)
            shown = false
        }
        composeRule.runOnIdle { shown = true }
        assertLoaded()
        assertEquals(0, fixture.calls.size)
    }

    /** Ordinary recomposition and navigation cannot restart a still-active fetch or lose its decoded completion. */
    @Test
    fun coldDownloadSurvivesRecompositionAndConversationReentry() {
        enableAutomaticImages()
        var shown by mutableStateOf(true)
        var revision by mutableIntStateOf(0)
        composeRule.setContent {
            WhiteNoiseTheme {
                Box {
                    if (shown) Bubble(revision)
                }
            }
        }
        composeRule.waitUntil(10_000) {
            // Real IO resumes through Android's main looper as well as Compose's frame scheduler.
            composeRule.waitForIdle()
            fixture.calls.size == 1
        }
        composeRule.runOnIdle { revision++ }
        composeRule.runOnIdle { shown = false }
        composeRule.runOnIdle { shown = true }
        composeRule.runOnIdle {
            assertEquals(1, fixture.calls.size)
            fixture.calls.single().succeed(imageBytes())
        }
        awaitLoaded()
        composeRule.runOnIdle { revision++ }
        assertLoaded()
        assertEquals(1, fixture.calls.size)
    }

    /** Provides a fresh descriptor on every parent recomposition while preserving attachment identity. */
    @Composable
    private fun Bubble(revision: Int = 0) {
        MediaImageBubble(
            item =
                localTimelineMessage(
                    timelineAppMessage(request(0).messageIdHex).copy(plaintext = "Synthetic revision $revision"),
                ),
            reference = reference(0).copy(),
            attachmentIndex = 0,
            controller = controller,
            appState = fixture.state,
            conversationVisualPages = emptyList(),
            mine = false,
        )
    }

    /** Uses production tile rendering while keeping every fixture identity distinct. */
    @Composable
    private fun GridTile(
        index: Int,
        modifier: Modifier = Modifier.width(240.dp),
    ) {
        MediaImageGridTile(
            messageIdHex = request(index).messageIdHex,
            attachmentIndex = 0,
            reference = reference(index),
            controller = controller,
            appState = fixture.state,
            mine = false,
            onTap = {},
            overflowCount = 0,
            modifier = modifier,
        )
    }

    /** Waits for actual image semantics, not a synthetic state flag outside the production bubble. */
    private fun awaitLoaded() {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithContentDescription(reference(0).fileName).fetchSemanticsNodes().isNotEmpty()
        }
        assertLoaded()
    }

    /** Checks both visible pixels' semantics and absence of the loading/retry affordances. */
    private fun assertLoaded() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        composeRule.onNodeWithContentDescription(reference(0).fileName).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.media_tap_to_download)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(context.getString(R.string.media_tap_to_retry)).assertDoesNotExist()
    }

    /** Selects the existing Wi-Fi policy without broadcasting a real connectivity change. */
    private fun enableAutomaticImages() {
        WhiteNoiseAppState::class.java
            .getDeclaredField("activeNetworkTypesSnapshot")
            .apply { isAccessible = true }
            .set(fixture.state, setOf(MediaAutoDownloadNetwork.WiFi))
        fixture.state.setMediaAutoDownload(MediaAutoDownloadType.Image, MediaAutoDownloadNetwork.WiFi, true)
        assertTrue(fixture.state.shouldAutoDownloadMedia(MediaAutoDownloadType.Image))
    }

    /** Generates valid pixels so the test exercises the real off-main decoder. */
    private fun imageBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.setPixels(
                IntArray(16 * 16) { pixel ->
                    if ((pixel / 16 / 4 + pixel % 16 / 4) % 2 == 0) 0xff286a9a.toInt() else 0xffb9daec.toInt()
                },
                0,
                16,
                0,
                0,
                16,
                16,
            )
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray().also { bytes ->
                    assertNotNull(
                        "Synthetic PNG must decode before exercising the UI",
                        runBlocking {
                            decodeMessageAttachmentImage(bytes, "image/png", MediaPipeline.THUMBNAIL_MAX_EDGE_PX)
                        },
                    )
                }
            }
        } finally {
            bitmap.recycle()
        }
    }
}
