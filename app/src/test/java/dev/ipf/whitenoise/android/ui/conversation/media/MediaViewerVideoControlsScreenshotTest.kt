package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import android.graphics.Rect
import android.os.Looper
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import kotlin.math.abs

/**
 * Drives the production viewer PlayerView — real Media3 controller, time bar and touch handling — inside the real
 * viewer frame and pager. A scripted player stands in for decoding, so these tests establish control geometry,
 * seek arbitration and chrome coordination, not decoded-frame pixels.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class MediaViewerVideoControlsScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var playerView: PlayerView
    private var chromeVisible by mutableStateOf(true)
    private var saves = 0

    /** Portrait large type keeps the time bar, times and play/pause above the actions and inside the safe area. */
    @Test
    fun portraitControlsSitBetweenTheChromeInsideTheSafeArea() {
        val insets = WindowInsets(left = 0.dp, top = 42.dp, right = 0.dp, bottom = 28.dp)
        render(ScriptedVideoPlayer(durationMs = CLIP_MS), insets, fontScale = LARGE_FONT_SCALE)

        assertControlsInsideContent(viewport = Rect(0, 42, 360, 780 - 28))
        composeRule.onRoot().captureRoboImage("src/test/snapshots/media_viewer_video_controls_portrait_large.png")
    }

    /** A landscape phone keeps every control clear of the cutout and system bars and above the actions. */
    @Test
    @Config(sdk = [36], qualifiers = "w915dp-h411dp-land-mdpi")
    fun landscapeControlsAvoidCutoutsAndTheActions() {
        val insets = WindowInsets(left = 32.dp, top = 28.dp, right = 0.dp, bottom = 24.dp)
        render(ScriptedVideoPlayer(durationMs = CLIP_MS), insets, darkTheme = true, amoled = true)

        assertControlsInsideContent(viewport = Rect(32, 28, 915, 411 - 24))
        composeRule.onRoot().captureRoboImage("src/test/snapshots/media_viewer_video_controls_landscape_amoled.png")
    }

    /**
     * A short landscape window at large type leaves Media3 too little height for its full layout. Its minimal
     * layout drops the time text but keeps the time bar and play/pause visible, clear of the cutouts and actions.
     */
    @Test
    @Config(sdk = [36], qualifiers = "w780dp-h360dp-land-mdpi")
    fun shortLandscapeKeepsTheTimeBarAndPlayPauseReachable() {
        val insets = WindowInsets(left = 56.dp, top = 18.dp, right = 28.dp, bottom = 22.dp)
        render(ScriptedVideoPlayer(durationMs = CLIP_MS), insets, fontScale = LARGE_FONT_SCALE)

        assertControlsInsideContent(viewport = Rect(56, 18, 780 - 28, 360 - 22), controls = ESSENTIAL_CONTROL_IDS)
    }

    /** RTL dark large type mirrors the viewer chrome while Media3's controls stay above the actions. */
    @Test
    fun rtlControlsStayAboveTheActions() {
        val insets = WindowInsets(top = 24.dp, bottom = 48.dp)
        render(
            ScriptedVideoPlayer(durationMs = CLIP_MS),
            insets,
            darkTheme = true,
            fontScale = LARGE_FONT_SCALE,
            layoutDirection = LayoutDirection.Rtl,
        )

        assertControlsInsideContent(viewport = Rect(0, 24, 360, 780 - 48))
        composeRule.onRoot().captureRoboImage("src/test/snapshots/media_viewer_video_controls_dark_large_rtl.png")
    }

    /** Dragging the time bar seeks to the released position and never pages the gallery. */
    @Test
    fun timeBarDragSeeksWithoutPagingTheGallery() {
        val player = ScriptedVideoPlayer(durationMs = CLIP_MS)
        render(player, WindowInsets(bottom = 48.dp), pageCount = 2)
        val timeBar = timeBarBoundsInPlayer()

        composeRule.onNodeWithTag(PLAYER_TAG).performTouchInput {
            val y = timeBar.centerY()
            down(Offset(timeBar.left + timeBar.width() * 0.1f, y))
            moveTo(Offset(timeBar.left + timeBar.width() * 0.3f, y))
            moveTo(Offset(timeBar.left + timeBar.width() * 0.5f, y))
            up()
        }
        settle()

        composeRule.onNodeWithTag(PLAYER_TAG).assertExists()
        composeRule.onNodeWithTag(NEXT_PAGE_TAG).assertDoesNotExist()
        val target = CLIP_MS / 2
        val sought = player.seeks.last()
        assertTrue("seek $sought is too far from $target", abs(sought - target) <= SEEK_TOLERANCE_MS)
        assertEquals(sought, player.currentPosition)
        assertTrue("seeking must not hide the controls", chromeVisible)
    }

    /** A horizontal drag on the picture itself still pages the gallery. */
    @Test
    fun dragOnThePictureStillPagesTheGallery() {
        val player = ScriptedVideoPlayer(durationMs = CLIP_MS)
        render(player, WindowInsets(bottom = 48.dp), pageCount = 2)

        composeRule.onNodeWithTag(PLAYER_TAG).performTouchInput {
            swipe(start = Offset(width * 0.9f, height * 0.25f), end = Offset(width * 0.1f, height * 0.25f))
        }
        settle()

        composeRule.onNodeWithTag(NEXT_PAGE_TAG).assertExists()
        assertTrue("a page swipe must not seek", player.seeks.isEmpty())
    }

    /** A tap hides Media3's controls and the viewer chrome together, and the next tap restores both. */
    @Test
    fun tapHidesAndRestoresControlsWithTheChrome() {
        render(ScriptedVideoPlayer(durationMs = CLIP_MS), WindowInsets(top = 24.dp, bottom = 48.dp))
        assertControllerAndChrome(visible = true)

        tapPicture()
        assertControllerAndChrome(visible = false)
        val content = composeRule.onNodeWithTag(MEDIA_VIEWER_CONTENT_TAG).fetchSemanticsNode().boundsInWindow
        assertEquals("hidden chrome gives its space back to the video", 24f, content.top, 0.5f)
        assertEquals("hidden chrome gives its space back to the video", 780f - 48f, content.bottom, 0.5f)
        composeRule.onRoot().captureRoboImage("src/test/snapshots/media_viewer_video_chrome_hidden.png")

        tapPicture()
        assertControllerAndChrome(visible = true)
    }

    /** Playing hides both after the timeout, buffering shows progress, and pausing or ending restores both. */
    @Test
    fun playbackStatesKeepControlsReachable() {
        val player = ScriptedVideoPlayer(durationMs = CLIP_MS)
        render(player, WindowInsets(bottom = 48.dp))

        composeRule.runOnUiThread { player.update { setPlayWhenReady(true, PLAY_REQUEST) } }
        settle(Duration.ofSeconds(3))
        assertControllerAndChrome(visible = false)

        composeRule.runOnUiThread { player.update { setPlaybackState(Player.STATE_BUFFERING) } }
        settle()
        assertEquals(View.VISIBLE, playerView.findViewById<View>(androidx.media3.ui.R.id.exo_buffering).visibility)

        composeRule.runOnUiThread { player.update { setPlaybackState(Player.STATE_READY) } }
        tapPicture()
        assertControllerAndChrome(visible = true)
        settle(Duration.ofSeconds(3))
        assertControllerAndChrome(visible = false)

        composeRule.runOnUiThread { player.update { setPlayWhenReady(false, PLAY_REQUEST) } }
        settle(Duration.ofSeconds(3))
        assertControllerAndChrome(visible = true)

        composeRule.runOnUiThread { player.update { setPlaybackState(Player.STATE_ENDED) } }
        settle(Duration.ofSeconds(3))
        assertControllerAndChrome(visible = true)
    }

    /** An open More menu keeps its chrome when playback controls time out, and choosing Save then hides it. */
    @Test
    fun openMenuOutlastsTheControlsTimeout() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val player = ScriptedVideoPlayer(durationMs = CLIP_MS)
        render(player, WindowInsets(bottom = 48.dp))
        composeRule.runOnUiThread { player.update { setPlayWhenReady(true, PLAY_REQUEST) } }
        settle()

        composeRule.onNodeWithContentDescription(context.getString(R.string.more_options)).performClick()
        settle(Duration.ofSeconds(3))
        composeRule.runOnIdle { assertFalse("the controls still time out", playerView.isControllerFullyVisible) }
        composeRule.onNodeWithTag(MEDIA_VIEWER_TOP_CHROME_TAG).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.media_save)).assertIsDisplayed().performClick()
        settle()

        composeRule.runOnIdle { assertEquals(1, saves) }
        assertControllerAndChrome(visible = false)
    }

    /** An unknown duration leaves the time bar unseekable instead of seeking to a guessed position. */
    @Test
    fun unknownDurationDisablesSeeking() {
        val player = ScriptedVideoPlayer(durationMs = null)
        render(player, WindowInsets(bottom = 48.dp))
        val timeBar = timeBarBoundsInPlayer()

        composeRule.onNodeWithTag(PLAYER_TAG).performTouchInput {
            val y = timeBar.centerY()
            down(Offset(timeBar.left + timeBar.width() * 0.1f, y))
            moveTo(Offset(timeBar.left + timeBar.width() * 0.6f, y))
            up()
        }
        settle()

        assertTrue("an unknown duration must not seek", player.seeks.isEmpty())
    }

    /** Renders the frame and a pager of [pageCount] pages whose first page hosts the production viewer PlayerView. */
    private fun render(
        player: ScriptedVideoPlayer,
        insets: WindowInsets,
        pageCount: Int = 1,
        darkTheme: Boolean = false,
        amoled: Boolean = false,
        fontScale: Float = 1f,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    ) {
        val pages = List(pageCount) { page(it) }
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled, fontScale = fontScale) {
                CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                    VideoFrame(player, insets, pages)
                }
            }
        }
        settle()
    }

    /** The viewer frame with the same chrome wiring FullScreenMediaViewer gives a current video page. */
    @Composable
    private fun VideoFrame(
        player: ScriptedVideoPlayer,
        insets: WindowInsets,
        pages: List<MediaViewerPage>,
    ) {
        MediaViewerFrame(
            senderLabel = "Alexandria Example",
            recordedAtLabel = "Oct 15, 2026, 9:41 AM",
            onDismiss = {},
            onSave = { saves++ },
            onShare = {},
            snackbarHostState = remember { SnackbarHostState() },
            contentWindowInsets = insets,
            onForwardMessage = {},
            chromeVisible = chromeVisible,
        ) {
            StableMediaViewerPager(
                pages = pages,
                selection = rememberMediaViewerPagerSelection(pages, startIndex = 0),
                modifier = Modifier.fillMaxSize(),
                pagePositionDescription = null,
                userScrollEnabled = true,
            ) { page, isCurrent ->
                if (page.attachmentIndex == 0) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize().background(Color.Black).testTag(PLAYER_TAG),
                        factory = { context ->
                            viewerPlayerView(context, player) { visible -> if (isCurrent) chromeVisible = visible }
                                .also { playerView = it }
                        },
                        onRelease = { it.player = null },
                    )
                } else {
                    Box(Modifier.fillMaxSize()) { Text("next", Modifier.testTag(NEXT_PAGE_TAG)) }
                }
            }
        }
    }

    /** Asserts the shown Media3 [controls] lie inside the content slot, below the top and above the bottom chrome. */
    private fun assertControlsInsideContent(
        viewport: Rect,
        controls: List<Pair<String, Int>> = ESSENTIAL_CONTROL_IDS + TIME_TEXT_IDS,
    ) {
        val content = composeRule.onNodeWithTag(MEDIA_VIEWER_CONTENT_TAG).fetchSemanticsNode().boundsInWindow
        val top = composeRule.onNodeWithTag(MEDIA_VIEWER_TOP_CHROME_TAG).fetchSemanticsNode().boundsInWindow
        val bottom = composeRule.onNodeWithTag(MEDIA_VIEWER_BOTTOM_CHROME_TAG).fetchSemanticsNode().boundsInWindow
        assertTrue("controller must be showing", playerView.isControllerFullyVisible)
        controls.forEach { (label, id) ->
            val control = playerView.findViewById<View>(id)
            assertTrue("$label is not shown", control.isShown)
            val bounds = control.boundsInWindow()
            assertFalse("$label is empty", bounds.isEmpty)
            assertTrue("$label $bounds is under the top chrome ${top.bottom}", bounds.top >= top.bottom)
            assertTrue("$label $bounds is under the actions ${bottom.top}", bounds.bottom <= bottom.top)
            assertTrue("$label $bounds leaves the content $content", bounds.left >= content.left)
            assertTrue("$label $bounds leaves the content $content", bounds.right <= content.right)
            assertTrue("$label $bounds leaves the safe area $viewport", viewport.contains(bounds))
        }
    }

    /** Asserts the Media3 controller and the viewer chrome agree on [visible]. */
    private fun assertControllerAndChrome(visible: Boolean) {
        composeRule.runOnIdle {
            assertEquals("Media3 controller", visible, playerView.isControllerFullyVisible)
            assertEquals("viewer chrome", visible, chromeVisible)
        }
        val chrome = composeRule.onNodeWithTag(MEDIA_VIEWER_BOTTOM_CHROME_TAG)
        if (visible) chrome.assertExists() else chrome.assertDoesNotExist()
    }

    /** Taps the picture away from Media3's centre and bottom controls. */
    private fun tapPicture() {
        composeRule.onNodeWithTag(PLAYER_TAG).performTouchInput { click(Offset(width * 0.2f, height * 0.25f)) }
        settle()
    }

    /** Returns the real time bar's bounds relative to the hosting AndroidView node. */
    private fun timeBarBoundsInPlayer(): android.graphics.RectF {
        val host = composeRule.onNodeWithTag(PLAYER_TAG).fetchSemanticsNode().boundsInWindow
        val bar = playerView.findViewById<View>(androidx.media3.ui.R.id.exo_progress).boundsInWindow()
        return android.graphics.RectF(
            bar.left - host.left,
            bar.top - host.top,
            bar.right - host.left,
            bar.bottom - host.top,
        )
    }

    /** Runs View timers and Compose frames until both settle. */
    private fun settle(duration: Duration = Duration.ofMillis(SETTLE_MS)) {
        composeRule.waitForIdle()
        shadowOf(Looper.getMainLooper()).idleFor(duration)
        composeRule.waitForIdle()
    }

    /** Window bounds of a laid-out Android view, including any transform. */
    private fun View.boundsInWindow(): Rect {
        val origin = IntArray(2)
        getLocationInWindow(origin)
        return Rect(origin[0], origin[1], origin[0] + width, origin[1] + height)
    }

    /** Creates a logical video page for the pager. */
    private fun page(index: Int) =
        MediaViewerPage(
            messageIdHex = "video",
            attachmentIndex = index,
            reference =
                MediaAttachmentReferenceFfi(
                    locators = emptyList(),
                    ciphertextSha256 = "${index + 1}".repeat(64),
                    plaintextSha256 = "bb".repeat(32),
                    nonceHex = "cc".repeat(12),
                    fileName = "clip-$index.mp4",
                    mediaType = "video/mp4",
                    version = EncryptedMediaVersionFfi.V1,
                    sourceEpoch = 1uL,
                    dim = null,
                    thumbhash = null,
                ),
            mine = false,
            sender = "sender",
            recordedAt = 1uL,
        )

    /** A deterministic Media3 player with a known duration whose seeks and play state tests can script. */
    private class ScriptedVideoPlayer(
        durationMs: Long?,
    ) : SimpleBasePlayer(Looper.getMainLooper()) {
        val seeks = mutableListOf<Long>()
        private var state =
            State
                .Builder()
                .setAvailableCommands(COMMANDS)
                .setPlaylist(
                    listOf(
                        MediaItemData
                            .Builder("clip")
                            .setDurationUs(durationMs?.let { it * US_PER_MS } ?: C.TIME_UNSET)
                            .setIsSeekable(durationMs != null)
                            .build(),
                    ),
                ).setPlaybackState(Player.STATE_READY)
                .setPlayWhenReady(false, PLAY_REQUEST)
                .setContentPositionMs(0)
                .build()

        /** Publishes the scripted state. */
        override fun getState(): State = state

        /** Applies a scripted state change and notifies listeners. */
        fun update(change: State.Builder.() -> State.Builder) {
            state = state.buildUpon().change().build()
            invalidateState()
        }

        /** Play/pause from the controller updates the scripted state. */
        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            state = state.buildUpon().setPlayWhenReady(playWhenReady, PLAY_REQUEST).build()
            return Futures.immediateVoidFuture()
        }

        /** Records each seek and moves the position there. */
        override fun handleSeek(
            mediaItemIndex: Int,
            positionMs: Long,
            seekCommand: Int,
        ): ListenableFuture<*> {
            seeks += positionMs
            state = state.buildUpon().setContentPositionMs(positionMs).build()
            return Futures.immediateVoidFuture()
        }
    }

    private companion object {
        const val PLAYER_TAG = "media-viewer-video-player"
        const val NEXT_PAGE_TAG = "media-viewer-next-page"
        const val CLIP_MS = 60_000L
        const val SEEK_TOLERANCE_MS = 2_000L
        const val SETTLE_MS = 300L
        const val US_PER_MS = 1_000L
        const val LARGE_FONT_SCALE = 1.6f
        const val PLAY_REQUEST = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
        val COMMANDS: Player.Commands =
            Player.Commands
                .Builder()
                .addAll(
                    Player.COMMAND_PLAY_PAUSE,
                    Player.COMMAND_PREPARE,
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                    Player.COMMAND_SEEK_BACK,
                    Player.COMMAND_SEEK_FORWARD,
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_TIMELINE,
                ).build()
        val ESSENTIAL_CONTROL_IDS =
            listOf(
                "time bar" to androidx.media3.ui.R.id.exo_progress,
                "play/pause" to androidx.media3.ui.R.id.exo_play_pause,
            )
        val TIME_TEXT_IDS =
            listOf(
                "elapsed time" to androidx.media3.ui.R.id.exo_position,
                "total time" to androidx.media3.ui.R.id.exo_duration,
            )
    }
}
