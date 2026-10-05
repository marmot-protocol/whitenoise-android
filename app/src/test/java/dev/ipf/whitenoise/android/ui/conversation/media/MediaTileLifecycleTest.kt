package dev.ipf.whitenoise.android.ui.conversation.media

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.MediaCacheDirs
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.Companion.reference
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.Companion.request
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
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Media that is already local must stay on screen across the ordinary ways a person leaves and returns to a chat:
 * recreating the Activity and moving the app to the background and back. The tiles are production composables over a
 * real controller whose only replaced boundary is the native download, so any request would be counted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaTileLifecycleTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private lateinit var fixture: MediaDownloadIntegrationFixture
    private lateinit var controller: ConversationController
    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private var videoTaps = 0

    /** Retains four images in the encrypted disk cache and one video file, with automatic downloads unavailable. */
    @Before
    fun setUp() {
        fixture = MediaDownloadIntegrationFixture()
        controller =
            ConversationController(
                appState = fixture.state,
                initialGroup = conversationTimelineTestGroup().copy(groupIdHex = MediaDownloadIntegrationFixture.GROUP),
            )
        repeat(IMAGE_COUNT) { fixture.disk.put(request(it).cacheKey(), imageBytes()) }
        fixture.reopenDisk()
        retainVideoFile()
        videoTaps = 0
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
    }

    /** Closes the Activity and removes the fixture's private caches. */
    @After
    fun tearDown() {
        scenario.close()
        fixture.close()
    }

    /** Every tile is shown after the Activity is recreated, and no Download or request appears on the way. */
    @Test
    fun retainedTilesStayShownAcrossActivityRecreation() {
        show()
        awaitTilesShown()
        assertEquals(0, fixture.calls.size)

        scenario.recreate()
        show()
        awaitTilesShown()

        assertEquals("recreating the Activity started a transfer", 0, fixture.calls.size)
    }

    /** Moving the app to the background and back leaves the same tiles shown, again with no request. */
    @Test
    fun retainedTilesStayShownAcrossBackgroundAndForeground() {
        show()
        awaitTilesShown()

        scenario.moveToState(Lifecycle.State.CREATED)
        composeRule.waitForIdle()
        scenario.moveToState(Lifecycle.State.RESUMED)
        awaitTilesShown()

        assertEquals("returning to the foreground started a transfer", 0, fixture.calls.size)
    }

    /** A slow local read must announce opening, never offer to download bytes already in the encrypted cache. */
    @Test
    fun retainedImageDoesNotAnnounceDownloadWhileReadingCachedBytes() {
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
            show()
            composeRule.waitUntil(10_000) { reading.count == 0L }
            assertNoDownloadAction()
            composeRule.onNodeWithContentDescription(text(R.string.media_opening)).assertIsDisplayed()
            assertEquals(0, fixture.calls.size)
        } finally {
            hold.set(false)
            release.countDown()
        }
        awaitTilesShown()
        assertEquals(0, fixture.calls.size)
    }

    /** Pixels decoded before recreation are on the first committed frame of the new Activity, not reloaded later. */
    @Test
    fun decodedPixelsAreOnTheFirstFrameAfterRecreation() {
        show()
        awaitTilesShown()
        composeRule.mainClock.autoAdvance = false

        scenario.recreate()
        show()

        composeRule.onNodeWithContentDescription(reference(0).fileName).assertIsDisplayed()
        assertEquals(0, fixture.calls.size)
    }

    /** One tap on a retained video opens it once, before and after recreation, and never starts a transfer. */
    @Test
    fun aRetainedVideoOpensOnOneTapBeforeAndAfterRecreation() {
        show()
        awaitTilesShown()
        composeRule.onNodeWithTag(videoTag()).performClick()
        assertEquals(1, videoTaps)

        scenario.recreate()
        show()
        awaitTilesShown()
        composeRule.onNodeWithTag(videoTag()).performClick()

        assertEquals("one tap on a retained video must open it exactly once each time", 2, videoTaps)
        assertEquals(0, fixture.calls.size)
    }

    /** Sets the tiles as the Activity's content, as a freshly created Activity does. */
    private fun show() {
        scenario.onActivity { activity -> activity.setContent { WhiteNoiseTheme { Tiles() } } }
    }

    /**
     * Waits until every image and the video are shown. At every idle point on the way it checks that no Download action
     * is offered, so a flash of one after a return is caught rather than only the end state.
     */
    private fun awaitTilesShown() {
        val deadline = System.nanoTime() + WAIT_NANOS
        while (!allShown()) {
            composeRule.waitForIdle()
            assertNoDownloadAction()
            check(System.nanoTime() < deadline) { "the retained tiles were not shown in time" }
            Thread.sleep(POLL_MILLIS)
        }
        assertNoDownloadAction()
    }

    /** True once all four images and the video's play control are on screen. */
    private fun allShown(): Boolean {
        val images = (0 until IMAGE_COUNT).all { nodesDescribed(reference(it).fileName) > 0 }
        return images && nodesDescribed(text(R.string.reply_media_video)) > 0
    }

    /** No tile may offer Download or Retry while its bytes are retained locally. */
    private fun assertNoDownloadAction() {
        val download = nodesDescribed(text(R.string.media_tap_to_download))
        val retry = nodesDescribed(text(R.string.media_tap_to_retry))
        assertEquals("a Download action flashed on a retained tile", 0, download)
        assertEquals("a Retry action appeared on a retained tile", 0, retry)
    }

    /** The number of nodes whose content description is exactly [description]. */
    private fun nodesDescribed(description: String): Int {
        val nodes = composeRule.onAllNodesWithContentDescription(description)
        return nodes.fetchSemanticsNodes().size
    }

    /** One bubble, a three-tile album and a video tile, all over the same retained fixtures. */
    @Composable
    private fun Tiles() {
        Column {
            MediaImageBubble(
                item = localTimelineMessage(timelineAppMessage(request(0).messageIdHex)),
                reference = reference(0),
                attachmentIndex = 0,
                controller = controller,
                appState = fixture.state,
                onOpenConversationMedia = {},
                mine = false,
            )
            Box(Modifier.width(ALBUM_WIDTH)) {
                MasonryImageLayout(visibleCount = ALBUM_TILES) { index, tileModifier ->
                    AlbumTile(index + 1, tileModifier)
                }
            }
            MediaVideoGridTile(
                messageIdHex = request(VIDEO_INDEX).messageIdHex,
                attachmentIndex = 0,
                reference = videoReference(),
                controller = controller,
                appState = fixture.state,
                mine = false,
                onTap = { videoTaps++ },
                overflowCount = 0,
                modifier = Modifier.size(VIDEO_SIZE),
            )
        }
    }

    /** Production album tile for the fixture image at [index]. */
    @Composable
    private fun AlbumTile(
        index: Int,
        modifier: Modifier,
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

    /** The descriptor of the retained video: a video media type over the same synthetic identity scheme. */
    private fun videoReference() = reference(VIDEO_INDEX).copy(fileName = "video.mp4", mediaType = "video/mp4")

    /** Writes the video's validated local copy where the tile looks for it on entry. */
    private fun retainVideoFile() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val directory = File(context.cacheDir, MediaCacheDirs.VIDEO).apply { mkdirs() }
        val reference = videoReference()
        val name = "${request(VIDEO_INDEX).messageIdHex}-0-${reference.sourceEpoch}.mp4"
        File(directory, name).writeBytes(byteArrayOf(1, 2, 3, 4))
        assertNotNull(
            "the retained video file is not where the tile looks for it",
            cachedVideoAttachmentFile(context, request(VIDEO_INDEX).messageIdHex, 0, reference),
        )
    }

    /** The test tag of the video tile's open target. */
    private fun videoTag() = videoAttachmentOpenTestTag(request(VIDEO_INDEX).messageIdHex, 0)

    /** Localized text for a string resource. */
    private fun text(id: Int): String {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return context.getString(id)
    }

    /** Generates valid pixels so the tiles exercise the real off-main decoder. */
    private fun imageBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(IMAGE_EDGE, IMAGE_EDGE, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(IMAGE_COLOR)
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray().also { bytes ->
                    val decoded =
                        runBlocking {
                            decodeMessageAttachmentImage(bytes, "image/png", MediaPipeline.THUMBNAIL_MAX_EDGE_PX)
                        }
                    assertNotNull("Synthetic PNG must decode before exercising the UI", decoded)
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val IMAGE_COUNT = 4
        const val ALBUM_TILES = 3
        const val VIDEO_INDEX = 4
        const val IMAGE_EDGE = 16
        const val IMAGE_COLOR = 0xff286a9a.toInt()
        const val WAIT_NANOS = 15_000_000_000L
        const val POLL_MILLIS = 10L
        val ALBUM_WIDTH = 240.dp
        val VIDEO_SIZE = 160.dp
    }
}
