package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.graphics.Insets
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Device proof for #3307 and #3801 with real decoding. The production viewer — FullScreenMediaViewer, PlaybackDialog,
 * MediaViewerFrame and VideoViewerPage's own ExoPlayer, PlayerView and Compose controls — plays deterministic
 * edge-marked clips from local assets. The media body is always the safe area, so the chrome and the controls fade
 * over the video and toggling them, by tap or by the playback timeout, never moves or resizes the frame. Every edge
 * of the portrait, landscape and rotation-tagged frames lands inside the safe area in portrait and landscape windows.
 * The seek bar, times and play/pause sit inside the safe area, below the top chrome and above Share/Forward. A real
 * seek drag seeks without paging while a picture drag still pages, and simulated cutouts and an active shell
 * transport never cover the controls. Edge positions come from UiAutomation screenshots, since SurfaceView video is
 * absent from Compose captures, and touches go through the platform input pipeline.
 */
@PullRequestDeviceSmoke
@RunWith(AndroidJUnit4::class)
class MediaViewerVideoControlsAndroidTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var fixture: EdgeClipViewerFixture

    /** Builds the local viewer fixture and composes its host, with no viewer open yet. */
    @Before
    fun setUp() {
        fixture = EdgeClipViewerFixture(composeRule, context)
        composeRule.setContent { WhiteNoiseTheme(darkTheme = true) { fixture.Content() } }
    }

    /** Releases the fixture's viewer, cache entries, preferences and voice session, then the orientation request. */
    @After
    fun tearDown() {
        try {
            if (::fixture.isInitialized) fixture.release()
        } finally {
            onMain { composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
    }

    /** In a portrait window every clip keeps one frame through a chrome toggle and shows all four edges. */
    @Test
    fun portraitWindowShowsEveryEdgeOfEveryClip() {
        requestOrientation(landscape = false)
        EdgeClip.entries.forEach { assertClipFitsWithChromeShownAndHidden(it, "portrait") }
    }

    /** In a landscape window every clip keeps one frame through a chrome toggle and shows all four edges. */
    @Test
    fun landscapeWindowShowsEveryEdgeOfEveryClip() {
        requestOrientation(landscape = true)
        EdgeClip.entries.forEach { assertClipFitsWithChromeShownAndHidden(it, "landscape") }
    }

    /**
     * #3801: while a clip plays, a tap hides the controls and the chrome, a second tap restores them and the playback
     * timeout hides them again, and the decoded frame keeps the same on-screen rectangle at every step.
     */
    @Test
    fun tapDoesNotResizeVideo() {
        requestOrientation(landscape = false)
        val paused = fixture.open(EdgeClipScenario(listOf(EdgeClip.Portrait)))
        // Looping keeps the clip playing, so the end of playback cannot force the controls back during the wait.
        fixture.readPlayer {
            it.repeatMode = Player.REPEAT_MODE_ONE
            it.play()
        }
        fixture.awaitCondition("playback to start") { fixture.readPlayer { it.isPlaying } }
        val playing = fixture.geometry()
        playing.assertControlsClear("playing")
        assertEquals("starting playback must not move the video", paused.video, playing.video)

        fixture.tapPicture(playing)
        fixture.awaitControllerAndChrome(visible = false)
        assertEquals("a tap that hides the controls must not move the video", playing.video, fixture.geometry().video)

        fixture.tapPicture(playing)
        fixture.awaitControllerAndChrome(visible = true)
        val restored = fixture.geometry()
        restored.assertControlsClear("restored by a tap")
        assertEquals("a tap that shows the controls must not move the video", playing.video, restored.video)

        fixture.awaitControllerAndChrome(visible = false)
        assertTrue("the controls must time out while playing", fixture.readPlayer { it.isPlaying })
        assertEquals("the playback timeout must not move the video", playing.video, fixture.geometry().video)
    }

    /**
     * A real drag along the time bar seeks the decoding player to the released fraction of the clip without paging,
     * hiding the controls or replacing the player, while a horizontal drag on the picture still pages the gallery.
     */
    @Test
    fun timeBarDragSeeksWithoutPagingWhileAPictureDragPages() {
        requestOrientation(landscape = false)
        val opened = fixture.open(EdgeClipScenario(listOf(EdgeClip.Portrait, EdgeClip.Landscape)))
        opened.assertControlsClear("seek, before the drag")
        val player = fixture.currentPlayer()
        val durationMs = fixture.readPlayer { it.duration }
        assertTrue("decoded clip duration $durationMs ms", durationMs in CLIP_DURATION_RANGE_MS)
        val targetMs = (durationMs * SEEK_FRACTION).roundToLong()

        fixture.awaitSeekBarEnabled()
        fixture.dragTimeBar(fixture.geometry(), SEEK_FRACTION)
        fixture.awaitCondition("the seek to reach $targetMs ms") {
            abs(fixture.readPlayer { it.currentPosition } - targetMs) <= SEEK_TOLERANCE_MS
        }
        val positionMs = fixture.readPlayer { it.currentPosition }
        Log.i(TAG, "seek: duration=$durationMs target=$targetMs actual=$positionMs bar=${opened.controls[TIME_BAR]}")
        assertTrue(
            "the drag sought to $positionMs ms, expected $targetMs ± $SEEK_TOLERANCE_MS ms",
            abs(positionMs - targetMs) <= SEEK_TOLERANCE_MS,
        )
        assertEquals("a time-bar drag must not page the gallery", EdgeClip.Portrait.ordinal, fixture.currentAttachment)
        assertSame("a time-bar drag must keep the page's player", player, fixture.currentPlayer())
        fixture.awaitControllerAndChrome(visible = true)

        fixture.swipePicture(opened)
        fixture.awaitAttachment(EdgeClip.Landscape.ordinal)
    }

    /**
     * Simulated status bars, navigation bars and display cutouts — dispatched to the viewer window, so the result does
     * not depend on the device having a cutout — keep the frame and the video controls inside the safe area, in
     * portrait with a deep top notch and side cutouts and in landscape with a side cutout and side navigation.
     */
    @Test
    fun simulatedCutoutsAndBarsKeepTheFrameAndControlsInsideTheSafeArea() {
        requestOrientation(landscape = false)
        fixture.simulatedInsets =
            SimulatedInsets(
                statusBars = Insets.of(0, dp(PORTRAIT_STATUS_DP), 0, 0),
                navigationBars = Insets.of(0, 0, 0, dp(PORTRAIT_NAVIGATION_DP)),
                cutout = Insets.of(dp(PORTRAIT_SIDE_CUTOUT_DP), dp(PORTRAIT_NOTCH_DP), dp(PORTRAIT_SIDE_CUTOUT_DP), 0),
            )
        assertClipFitsWithChromeShownAndHidden(EdgeClip.RotationTagged, "simulated portrait cutout")
        fixture.close()

        requestOrientation(landscape = true)
        fixture.simulatedInsets =
            SimulatedInsets(
                statusBars = Insets.of(0, dp(LANDSCAPE_STATUS_DP), 0, 0),
                navigationBars = Insets.of(0, 0, dp(LANDSCAPE_NAVIGATION_DP), dp(LANDSCAPE_GESTURE_DP)),
                cutout = Insets.of(dp(LANDSCAPE_CUTOUT_DP), 0, 0, 0),
            )
        assertClipFitsWithChromeShownAndHidden(EdgeClip.Landscape, "simulated landscape cutout")
    }

    /**
     * With the shell's playback strip showing above the viewer, as PlaybackDialog hosts it in the app, the strip
     * covers neither the seek bar nor any frame edge, and its consumed status-bar inset is subtracted only once.
     */
    @Test
    fun activeShellTransportCoversNeitherTheTimeBarNorTheFrame() {
        fixture.showShellTransport()
        requestOrientation(landscape = false)
        assertTransportLeavesTheViewerClear(EdgeClip.Portrait, "portrait transport")
        fixture.close()
        requestOrientation(landscape = true)
        assertTransportLeavesTheViewerClear(EdgeClip.Landscape, "landscape transport")
    }

    /**
     * Opens [clip] alone and checks the controls over a safe-area body, hides the chrome and controls with one tap,
     * checks every frame edge, then restores them. The frame keeps one rectangle throughout. Edges are traced with the
     * chrome hidden, since the shown chrome's scrim deliberately fades over the frame.
     */
    private fun assertClipFitsWithChromeShownAndHidden(
        clip: EdgeClip,
        window: String,
    ) {
        val label = "$window ${clip.name}"
        val shown = fixture.open(EdgeClipScenario(listOf(clip)))
        assertRectNear("$label: shown chrome must not shrink the media body", shown.safe, shown.body)
        shown.assertControlsClear("$label, chrome shown")

        fixture.tapPicture(shown)
        fixture.awaitControllerAndChrome(visible = false)
        val hidden = fixture.geometry()
        assertRectNear("$label: hidden chrome must leave the media body at the safe area", hidden.safe, hidden.body)
        assertEquals("$label: hiding the chrome must not move the video", shown.video, hidden.video)
        assertFrameFits(clip, "$label, chrome hidden")

        fixture.tapPicture(hidden)
        fixture.awaitControllerAndChrome(visible = true)
        val restored = fixture.geometry()
        assertEquals("$label: restoring the chrome must not move the video", shown.video, restored.video)
        restored.assertControlsClear("$label, chrome restored")
    }

    /** Compares the viewer with and without the shell transport and asserts the strip covers nothing. */
    private fun assertTransportLeavesTheViewerClear(
        clip: EdgeClip,
        label: String,
    ) {
        val plain = fixture.open(EdgeClipScenario(listOf(clip)))
        val shared = fixture.open(EdgeClipScenario(listOf(clip), shellTransport = true))
        val transport = shared.transport ?: throw AssertionError("$label: the shell transport is not showing")
        val plainChrome = requireNotNull(plain.topChrome) { "$label: no top chrome without the transport" }
        val sharedChrome = requireNotNull(shared.topChrome) { "$label: no top chrome under the transport" }
        Log.i(TAG, "$label: transport=$transport chrome=$sharedChrome plainChrome=$plainChrome")
        assertTrue("$label: transport $transport is under the status bar", transport.top >= shared.safe.top)
        assertTrue("$label: chrome $sharedChrome starts under the transport", sharedChrome.top >= transport.bottom)
        assertNear(
            "$label: the transport's consumed status-bar inset must be subtracted from the chrome exactly once",
            expected = plain.safe.top - plain.window.top,
            actual = plainChrome.height() - sharedChrome.height(),
            tolerance = INSET_TOLERANCE_PX,
        )
        shared.assertControlsClear(label)

        fixture.tapPicture(shared)
        fixture.awaitControllerAndChrome(visible = false)
        assertEquals("$label: hiding the chrome must not move the video", shared.video, fixture.geometry().video)
        assertFrameFits(clip, "$label, chrome hidden")
    }

    /**
     * Screenshots the display until every edge band appears where [ViewerGeometry] says the frame is — the compositor
     * may lag a relayout by a few frames — and fails with the last capture's evidence once the deadline passes.
     */
    private fun assertFrameFits(
        clip: EdgeClip,
        label: String,
    ) {
        val deadline = SystemClock.uptimeMillis() + FRAME_SETTLE_TIMEOUT_MS
        while (true) {
            val geometry = fixture.geometry()
            val shot = takeDisplayScreenshot()
            try {
                assertTrue(
                    "$label: screenshot ${shot.width}x${shot.height} does not cover the window ${geometry.window}",
                    shot.width >= geometry.window.right && shot.height >= geometry.window.bottom,
                )
                val edges = shot.frameEdges(geometry.window, geometry.video)
                Log.i(TAG, "$label: body=${geometry.body} video=${geometry.video} safe=${geometry.safe} edges=$edges")
                geometry.assertBandsTraceTheFrame(clip, edges, label)
                return
            } catch (failure: AssertionError) {
                if (SystemClock.uptimeMillis() > deadline) {
                    shot.saveEvidence(label.replace(Regex("[^A-Za-z0-9]+"), "-"))
                    throw failure
                }
            } finally {
                shot.recycle()
            }
            SystemClock.sleep(FRAME_RETRY_MS)
        }
    }

    /** Requests a window orientation and waits until the activity has been laid out in it. */
    private fun requestOrientation(landscape: Boolean) {
        val requested =
            if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        val expected = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        onMain { composeRule.activity.requestedOrientation = requested }
        fixture.awaitCondition("orientation $expected") {
            onMain {
                val decor = composeRule.activity.window.decorView
                composeRule.activity.resources.configuration.orientation == expected &&
                    (decor.width > decor.height) == landscape
            }
        }
    }

    /** Converts density-independent pixels to whole device pixels. */
    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()
}

/**
 * Asserts every video control is shown inside the media body and the safe area, below the top chrome or shell
 * transport and above Share/Forward, with a usable touch target where it takes touches.
 */
private fun ViewerGeometry.assertControlsClear(label: String) {
    assertTrue("$label: the video controls must be showing", controlsVisible)
    val ceiling = listOfNotNull(topChrome?.bottom, transport?.bottom, body.top).max()
    val floor = listOfNotNull(bottomChrome?.top, body.bottom).min()
    Log.i(TAG, "$label: controls=$controls ceiling=$ceiling floor=$floor body=$body safe=$safe")
    ALL_CONTROLS.forEach { name ->
        val bounds = controls[name] ?: throw AssertionError("$label: the $name control is not shown")
        assertTrue("$label: $name $bounds is under the top chrome or transport at $ceiling", bounds.top >= ceiling)
        assertTrue("$label: $name $bounds is under Share/Forward at $floor", bounds.bottom <= floor)
        assertTrue("$label: $name $bounds leaves the media body $body", body.contains(bounds))
        assertTrue("$label: $name $bounds leaves the safe area $safe", safe.contains(bounds))
    }
}

/** Asserts four on-screen bands trace the whole decoded frame inside the media body and clear of the chrome. */
private fun ViewerGeometry.assertBandsTraceTheFrame(
    clip: EdgeClip,
    edges: FrameEdges,
    label: String,
) {
    val found = { name: String, band: EdgeBand? ->
        band ?: throw AssertionError("$label: the $name edge is not on screen ($edges)")
    }
    val top = found("yellow top", edges.top)
    val bottom = found("cyan bottom", edges.bottom)
    val left = found("red left", edges.left)
    val right = found("magenta right", edges.right)
    val expectedAspect = clip.displayWidth.toFloat() / clip.displayHeight
    assertEquals(
        "$label: frame $video aspect must match the clip after rotation metadata",
        expectedAspect,
        video.width().toFloat() / video.height(),
        expectedAspect * ASPECT_TOLERANCE,
    )
    assertTrue("$label: frame $video leaves the media body $body", body.contains(video))
    assertNear("$label: yellow top edge of $video", video.top, top.outer)
    assertNear("$label: cyan bottom edge of $video", video.bottom - 1, bottom.outer)
    assertNear("$label: red left edge of $video", video.left, left.outer)
    assertNear("$label: magenta right edge of $video", video.right - 1, right.outer)
    val vertical = EDGE_BAND_SOURCE_PX * video.height().toFloat() / clip.displayHeight
    val horizontal = EDGE_BAND_SOURCE_PX * video.width().toFloat() / clip.displayWidth
    listOf(top to vertical, bottom to vertical, left to horizontal, right to horizontal).forEach { (band, expected) ->
        assertTrue("$label: band $band is cropped below $expected px", band.thickness >= expected * MIN_BAND_FRACTION)
    }
    val ceiling = listOfNotNull(topChrome?.bottom, transport?.bottom, body.top, safe.top).max()
    val floor = listOfNotNull(bottomChrome?.top, body.bottom, safe.bottom).min()
    assertTrue("$label: top edge ${top.outer} is under the chrome or outside $body", top.outer >= ceiling)
    assertTrue("$label: bottom edge ${bottom.outer} is under Share/Forward or outside $body", bottom.outer < floor)
    assertTrue("$label: left edge ${left.outer} leaves $body or $safe", left.outer >= maxOf(body.left, safe.left))
    assertTrue("$label: right edge ${right.outer} leaves $body or $safe", right.outer < minOf(body.right, safe.right))
}

/** Asserts [actual] is within [tolerance] pixels of [expected]. */
private fun assertNear(
    message: String,
    expected: Int,
    actual: Int,
    tolerance: Int = EDGE_TOLERANCE_PX,
) {
    assertTrue("$message: expected $expected ± $tolerance px, was $actual", abs(actual - expected) <= tolerance)
}

/** Asserts every side of [actual] is within [INSET_TOLERANCE_PX] of [expected]. */
private fun assertRectNear(
    message: String,
    expected: Rect,
    actual: Rect,
) {
    val sides = listOf(expected.left to actual.left, expected.top to actual.top, expected.right to actual.right)
    (sides + (expected.bottom to actual.bottom)).forEach { (want, was) ->
        assertTrue("$message: expected $expected, was $actual", abs(want - was) <= INSET_TOLERANCE_PX)
    }
}

private const val TAG = "MediaViewerVideoCtl"

/** Every video control the viewer must keep clear of its chrome. */
private val ALL_CONTROLS = listOf(TIME_BAR, PLAY_PAUSE, ELAPSED_TIME, TOTAL_TIME)

/** Released fraction of the time bar's width for the seek. */
private const val SEEK_FRACTION = 0.6f

/**
 * Seek tolerance. The seek bar maps the release point linearly onto the duration and seeks once the drag finishes, so
 * the error budget is one seek-bar pixel (about 6 ms for a 6 s clip on a phone-width bar), the thumb's symmetric end
 * padding (under 1 % of the duration at 60 %) and exact seeking, which ExoPlayer uses by default. 250 ms leaves room
 * for emulator input timing without accepting a missed or cancelled seek, which would leave the paused player at 0.
 */
private const val SEEK_TOLERANCE_MS = 250L

/** The committed clips decode to six seconds. */
private val CLIP_DURATION_RANGE_MS = 5_500L..6_500L

/** On-screen distance allowed between a band's outer line and Media3's content-frame edge, for edge filtering. */
private const val EDGE_TOLERANCE_PX = 4

/** Rounding allowance when comparing inset-derived rectangles. */
private const val INSET_TOLERANCE_PX = 2

/** Relative aspect-ratio allowance for the content frame's whole-pixel size. */
private const val ASPECT_TOLERANCE = 0.02f

/** A band thinner than this share of its scaled source thickness means the frame edge was cropped or covered. */
private const val MIN_BAND_FRACTION = 0.6f

private const val FRAME_SETTLE_TIMEOUT_MS = 5_000L
private const val FRAME_RETRY_MS = 200L
private const val PORTRAIT_STATUS_DP = 40
private const val PORTRAIT_NOTCH_DP = 64
private const val PORTRAIT_SIDE_CUTOUT_DP = 20
private const val PORTRAIT_NAVIGATION_DP = 56
private const val LANDSCAPE_STATUS_DP = 24
private const val LANDSCAPE_CUTOUT_DP = 72
private const val LANDSCAPE_NAVIGATION_DP = 48
private const val LANDSCAPE_GESTURE_DP = 16
