package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import android.os.Looper
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
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
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.sandbox.RawPausedSeekFailure
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
 * Drives the production viewer video surface — Media3 PlayerView with the viewer's Compose controls — inside the real
 * viewer frame and pager. A scripted player stands in for decoding, so these tests establish control geometry, fixed
 * video bounds, seek arbitration and chrome coordination, not decoded-frame pixels.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class MediaViewerVideoControlsScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var chromeVisible by mutableStateOf(true)
    private var saves = 0

    /** Portrait large type keeps the time row and play/pause above the actions and inside the safe area. */
    @Test
    fun portraitControlsSitBetweenTheChromeInsideTheSafeArea() {
        val insets = WindowInsets(left = 0.dp, top = 42.dp, right = 0.dp, bottom = 28.dp)
        render(ScriptedVideoPlayer(durationMs = CLIP_MS), insets, fontScale = LARGE_FONT_SCALE)

        assertControlsInsideContent(viewport = Rect(0f, 42f, 360f, 780f - 28f))
        composeRule.onRoot().captureRoboImage("src/test/snapshots/media_viewer_video_controls_portrait_large.png")
    }

    /** A landscape phone keeps every control clear of the cutout and system bars and above the actions. */
    @Test
    @Config(sdk = [36], qualifiers = "w915dp-h411dp-land-mdpi")
    fun landscapeControlsAvoidCutoutsAndTheActions() {
        val insets = WindowInsets(left = 32.dp, top = 28.dp, right = 0.dp, bottom = 24.dp)
        render(ScriptedVideoPlayer(durationMs = CLIP_MS), insets, darkTheme = true, amoled = true)

        assertControlsInsideContent(viewport = Rect(32f, 28f, 915f, 411f - 24f))
        composeRule.onRoot().captureRoboImage("src/test/snapshots/media_viewer_video_controls_landscape_amoled.png")
    }

    /** A short landscape window at large type with cutouts on both sides still fits every control in the safe area. */
    @Test
    @Config(sdk = [36], qualifiers = "w780dp-h360dp-land-mdpi")
    fun shortLandscapeKeepsEveryControlReachable() {
        val insets = WindowInsets(left = 56.dp, top = 18.dp, right = 28.dp, bottom = 22.dp)
        render(ScriptedVideoPlayer(durationMs = CLIP_MS), insets, fontScale = LARGE_FONT_SCALE)

        assertControlsInsideContent(viewport = Rect(56f, 18f, 780f - 28f, 360f - 22f))
    }

    /** RTL dark large type mirrors the viewer chrome while the controls stay above the actions. */
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

        assertControlsInsideContent(viewport = Rect(0f, 24f, 360f, 780f - 48f))
        composeRule.onRoot().captureRoboImage("src/test/snapshots/media_viewer_video_controls_dark_large_rtl.png")
    }

    /** Play/pause, the scrub bar and the show/hide gesture are labelled, and both controls are large enough to hit. */
    @Test
    fun controlsAreLabelledAndLargeEnoughToTouch() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        render(ScriptedVideoPlayer(durationMs = CLIP_MS), WindowInsets(bottom = 48.dp))

        composeRule.onNodeWithContentDescription(context.getString(R.string.video_play)).assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.video_playback_position))
            .assertIsDisplayed()
        listOf(VIDEO_VIEWER_PLAY_PAUSE_TAG, VIDEO_VIEWER_SEEK_TAG).forEach { tag ->
            val bounds = boundsOf(tag)
            assertTrue("$tag $bounds is under 48dp", bounds.width >= MIN_TOUCH_DP && bounds.height >= MIN_TOUCH_DP)
        }
        val toggle = context.getString(R.string.media_viewer_toggle_controls)
        val actions =
            composeRule
                .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
                .fetchSemanticsNodes()
                .flatMap { it.config[SemanticsActions.CustomActions] }
        assertTrue("the show/hide gesture needs an accessibility action", actions.any { it.label == toggle })
    }

    /** Dragging the scrub bar seeks to the released position and never pages the gallery. */
    @Test
    fun timeBarDragSeeksWithoutPagingTheGallery() {
        val player = ScriptedVideoPlayer(durationMs = CLIP_MS)
        render(player, WindowInsets(bottom = 48.dp), pageCount = 2)

        val released = dragSeekBar(from = 0.1f, to = 0.5f)

        composeRule.onNodeWithTag(PLAYER_TAG).assertExists()
        composeRule.onNodeWithTag(NEXT_PAGE_TAG).assertDoesNotExist()
        val sought = player.seeks.last()
        val shown = (released * CLIP_MS).toLong()
        assertTrue("seek $sought must match the released bar position $shown", abs(sought - shown) <= 1L)
        // Material's slider drops the touch slop from a press-then-drag, so the release lands short of the finger.
        assertTrue("seek $sought must land within the drag", sought in (CLIP_MS / 10)..(CLIP_MS / 2))
        assertEquals(sought, player.currentPosition)
        assertTrue("seeking must not hide the controls", chromeVisible)
    }

    @Test
    fun pausedScrubStaysAtCommittedPositionAndOffersRetryOnFailure() {
        val player = ScriptedVideoPlayer(durationMs = CLIP_MS)
        val pending = SettableFuture.create<Void?>()
        player.pendingSeek = pending
        render(player, WindowInsets(bottom = 48.dp), darkTheme = true, amoled = true, fontScale = LARGE_FONT_SCALE)

        dragSeekBar(from = 0.1f, to = 0.5f, expectedAfterRelease = 0f)
        val target = player.seeks.single()
        composeRule.onNodeWithTag(VIDEO_VIEWER_ELAPSED_TAG).assertTextEquals("00:00")
        composeRule.runOnUiThread {
            val failure = RawPausedSeekFailure(target)
            val errorCode = PlaybackException.ERROR_CODE_DECODING_FAILED
            player.update {
                setPlaybackState(Player.STATE_IDLE)
                setPlayerError(PlaybackException("Paused seek failed", failure, errorCode))
            }
            pending.setException(failure)
            player.pendingSeek = null
        }
        settle()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val error = composeRule.onNodeWithText(context.getString(R.string.video_seek_failed))
        error.assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/media_viewer_video_seek_recovery.png")
        composeRule.onNodeWithText(context.getString(R.string.retry)).performClick()
        settle()

        error.assertDoesNotExist()
        assertEquals(listOf(target, target), player.seeks)
        assertEquals(target, player.currentPosition)
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

    /** A tap hides the controls and chrome together without moving or resizing the video; a tap restores them. */
    @Test
    fun tapHidesAndRestoresControlsWithTheChrome() {
        render(ScriptedVideoPlayer(durationMs = CLIP_MS), WindowInsets(top = 24.dp, bottom = 48.dp))
        assertControlsAndChrome(visible = true)
        val shown = videoBounds()
        assertEquals("the video fills the safe area", Rect(0f, 24f, 360f, 780f - 48f), shown)

        tapPicture()
        assertControlsAndChrome(visible = false)
        assertEquals("hiding the chrome must not move or resize the video", shown, videoBounds())
        composeRule.onRoot().captureRoboImage("src/test/snapshots/media_viewer_video_chrome_hidden.png")

        tapPicture()
        assertControlsAndChrome(visible = true)
        assertEquals("showing the chrome must not move or resize the video", shown, videoBounds())
    }

    /** Playback timing out the controls in a landscape cutout window leaves the video exactly where it was. */
    @Test
    @Config(sdk = [36], qualifiers = "w915dp-h411dp-land-mdpi")
    fun controlsTimeoutKeepsTheVideoBounds() {
        val player = ScriptedVideoPlayer(durationMs = CLIP_MS)
        render(player, WindowInsets(left = 32.dp, top = 28.dp, right = 0.dp, bottom = 24.dp))
        val shown = videoBounds()

        composeRule.runOnUiThread { player.update { setPlayWhenReady(true, PLAY_REQUEST) } }
        settle(Duration.ofSeconds(3))

        assertControlsAndChrome(visible = false)
        assertEquals("the controls timing out must not move or resize the video", shown, videoBounds())
    }

    /** Playing hides both after the timeout, buffering shows progress, and pausing or ending restores both. */
    @Test
    fun playbackStatesKeepControlsReachable() {
        val player = ScriptedVideoPlayer(durationMs = CLIP_MS)
        render(player, WindowInsets(bottom = 48.dp))

        composeRule.runOnUiThread { player.update { setPlayWhenReady(true, PLAY_REQUEST) } }
        settle(Duration.ofSeconds(3))
        assertControlsAndChrome(visible = false)

        composeRule.runOnUiThread { player.update { setPlaybackState(Player.STATE_BUFFERING) } }
        settle()
        composeRule.onNodeWithTag(VIDEO_VIEWER_BUFFERING_TAG).assertExists()

        composeRule.runOnUiThread { player.update { setPlaybackState(Player.STATE_READY) } }
        tapPicture()
        assertControlsAndChrome(visible = true)
        composeRule.onNodeWithTag(VIDEO_VIEWER_BUFFERING_TAG).assertDoesNotExist()
        settle(Duration.ofSeconds(3))
        assertControlsAndChrome(visible = false)

        composeRule.runOnUiThread { player.update { setPlayWhenReady(false, PLAY_REQUEST) } }
        settle(Duration.ofSeconds(3))
        assertControlsAndChrome(visible = true)

        composeRule.runOnUiThread { player.update { setPlaybackState(Player.STATE_ENDED) } }
        settle(Duration.ofSeconds(3))
        assertControlsAndChrome(visible = true)
    }

    /** Play/pause follows the player and drives it. */
    @Test
    fun playPauseTogglesPlayback() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val player = ScriptedVideoPlayer(durationMs = CLIP_MS)
        render(player, WindowInsets(bottom = 48.dp))

        composeRule.onNodeWithContentDescription(context.getString(R.string.video_play)).performClick()
        settle()
        assertTrue("play starts playback", player.playWhenReady)
        composeRule.onNodeWithContentDescription(context.getString(R.string.video_pause)).performClick()
        settle()
        assertFalse("pause stops playback", player.playWhenReady)
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
        composeRule.onNodeWithTag(VIDEO_VIEWER_CONTROLS_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(MEDIA_VIEWER_TOP_CHROME_TAG).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.media_save)).assertIsDisplayed().performClick()
        settle()

        composeRule.runOnIdle { assertEquals(1, saves) }
        assertControlsAndChrome(visible = false)
    }

    /** An unknown duration leaves the scrub bar unseekable instead of seeking to a guessed position. */
    @Test
    fun unknownDurationDisablesSeeking() {
        val player = ScriptedVideoPlayer(durationMs = null)
        render(player, WindowInsets(bottom = 48.dp))

        composeRule.onNodeWithTag(VIDEO_VIEWER_SEEK_TAG).assertIsNotEnabled()
        dragSeekBar(from = 0.1f, to = 0.6f)

        assertTrue("an unknown duration must not seek", player.seeks.isEmpty())
        composeRule.onNodeWithTag(VIDEO_VIEWER_TOTAL_TAG).assertTextEquals("--:--")
    }

    /** Renders the frame and a pager of [pageCount] pages whose first page hosts the production video surface. */
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
                    VideoViewerSurface(
                        player = player,
                        controlsVisible = isCurrent && chromeVisible,
                        onControlsVisibleChange = { visible -> if (isCurrent) chromeVisible = visible },
                        modifier = Modifier.fillMaxSize().background(Color.Black).testTag(PLAYER_TAG),
                    )
                } else {
                    Box(Modifier.fillMaxSize()) { Text("next", Modifier.testTag(NEXT_PAGE_TAG)) }
                }
            }
        }
    }

    /** Asserts every control lies inside the content slot and safe area, below the top and above the bottom chrome. */
    private fun assertControlsInsideContent(viewport: Rect) {
        val content = boundsOf(MEDIA_VIEWER_CONTENT_TAG)
        val top = boundsOf(MEDIA_VIEWER_TOP_CHROME_TAG)
        val bottom = boundsOf(MEDIA_VIEWER_BOTTOM_CHROME_TAG)
        CONTROL_TAGS.forEach { tag ->
            composeRule.onNodeWithTag(tag).assertIsDisplayed()
            val bounds = boundsOf(tag)
            assertFalse("$tag is empty", bounds.isEmpty)
            assertTrue("$tag $bounds is under the top chrome ${top.bottom}", bounds.top >= top.bottom - EPSILON)
            assertTrue("$tag $bounds is under the actions ${bottom.top}", bounds.bottom <= bottom.top + EPSILON)
            assertTrue("$tag $bounds leaves the content $content", content.contains(bounds))
            assertTrue("$tag $bounds leaves the safe area $viewport", viewport.contains(bounds))
        }
    }

    /** Asserts the video controls and the viewer chrome agree on [visible]. */
    private fun assertControlsAndChrome(visible: Boolean) {
        composeRule.runOnIdle { assertEquals("viewer chrome", visible, chromeVisible) }
        listOf(VIDEO_VIEWER_CONTROLS_TAG, MEDIA_VIEWER_BOTTOM_CHROME_TAG).forEach { tag ->
            val node = composeRule.onNodeWithTag(tag)
            if (visible) node.assertExists() else node.assertDoesNotExist()
        }
    }

    /** The video surface's bounds in the root. */
    private fun videoBounds(): Rect = boundsOf(VIDEO_VIEWER_SURFACE_TAG)

    private fun boundsOf(tag: String): Rect = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    /** Drags the seek bar from [from] to [to] of its width and returns the bar fraction shown at release. */
    private fun dragSeekBar(
        from: Float,
        to: Float,
        expectedAfterRelease: Float? = null,
    ): Float {
        val bar = composeRule.onNodeWithTag(VIDEO_VIEWER_SEEK_TAG)
        bar.performTouchInput {
            val y = height / 2f
            down(Offset(width * from, y))
            moveTo(Offset(width * (from + to) / 2f, y))
            moveTo(Offset(width * to, y))
        }
        composeRule.waitForIdle()
        val shown = bar.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
        bar.performTouchInput { up() }
        composeRule.waitForIdle()
        val held = bar.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
        assertEquals("the thumb must follow the player after release", expectedAfterRelease ?: shown, held, 0.001f)
        settle()
        return shown
    }

    /** Taps the picture away from the centre and bottom controls. */
    private fun tapPicture() {
        composeRule.onNodeWithTag(PLAYER_TAG).performTouchInput { click(Offset(width * 0.2f, height * 0.25f)) }
        settle()
    }

    /** Runs View timers and Compose frames and effect delays until both settle. */
    private fun settle(duration: Duration = Duration.ofMillis(SETTLE_MS)) {
        composeRule.waitForIdle()
        shadowOf(Looper.getMainLooper()).idleFor(duration)
        composeRule.mainClock.advanceTimeBy(duration.toMillis())
        composeRule.waitForIdle()
    }

    private fun Rect.contains(other: Rect): Boolean =
        other.left >= left - EPSILON &&
            other.top >= top - EPSILON &&
            other.right <= right + EPSILON &&
            other.bottom <= bottom + EPSILON

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
        var pendingSeek: SettableFuture<Void?>? = null
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

        override fun getPlaceholderState(suggestedPlaceholderState: State): State =
            if (pendingSeek == null) {
                suggestedPlaceholderState
            } else {
                state
            }

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
            pendingSeek?.let { return it }
            state =
                state
                    .buildUpon()
                    .setPlayerError(null)
                    .setPlaybackState(Player.STATE_READY)
                    .setContentPositionMs(positionMs)
                    .build()
            return Futures.immediateVoidFuture()
        }
    }

    private companion object {
        const val PLAYER_TAG = "media-viewer-video-player"
        const val NEXT_PAGE_TAG = "media-viewer-next-page"
        const val CLIP_MS = 60_000L
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
        const val EPSILON = 0.5f
        const val MIN_TOUCH_DP = 48f
        val CONTROL_TAGS =
            listOf(VIDEO_VIEWER_PLAY_PAUSE_TAG, VIDEO_VIEWER_SEEK_TAG, VIDEO_VIEWER_ELAPSED_TAG, VIDEO_VIEWER_TOTAL_TAG)
    }
}
