package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.functionBody
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Invariant gate: the full-screen viewer fits whole unzoomed media and never lays opaque chrome over it. Every
 * image uses its original-source fit rectangle and videos use fit scaling. The frame gives its body the area chrome and
 * the safe area leave free, and a video page reveals and hides the chrome with its native controller.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class MediaViewerContentBoundsCoverageTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Every unzoomed image, pending frame and video surface fits the whole frame instead of cropping it. */
    @Test
    fun everyViewerSurfaceFitsTheWholeFrame() {
        val viewer = source("MediaViewer.kt")
        val pageScales = scales(viewer.functionBody("ViewerPage"))
        assertEquals(
            "decoded still and animated images fill only their authoritative fitted source rectangle",
            listOf("FillBounds", "FillBounds"),
            pageScales,
        )
        val viewport = source("ImageViewerViewport.kt").functionBody("ImageViewerViewport")
        assertTrue("original dimensions must determine fit", "viewerFitGeometry(" in viewport)
        assertTrue("both source axes must define fit", "imageWidth," in viewport && "imageHeight," in viewport)
        assertTrue(
            "the fitted rectangle must measure the base",
            "Constraints.fixed(measuredWidth, measuredHeight)" in viewport,
        )
        assertTrue(
            "fractional source width must survive measurement",
            "scaleX = fit.baseWidth / measuredWidth" in viewport,
        )
        assertTrue(
            "fractional source height must survive measurement",
            "scaleY = fit.baseHeight / measuredHeight" in viewport,
        )
        val pendingScales = scales(source("MediaViewerPendingFrame.kt"))
        assertTrue("pending frames must draw", pendingScales.isNotEmpty())
        assertEquals("pending thumbnails must fit", setOf("Fit"), pendingScales.toSet())

        val video = source("MediaVideo.kt")
        val factory = video.functionBody("viewerPlayerView")
        assertEquals(listOf("RESIZE_MODE_FIT"), Regex("""RESIZE_MODE_\w+""").findAll(factory).map { it.value }.toList())
        assertTrue("the native controller owns the time bar", "useController = true" in factory)
        assertTrue(
            "the viewer page must build its surface through the shared factory",
            "viewerPlayerView(ctx, exo)" in video.functionBody("VideoViewerPage"),
        )
    }

    /** The frame draws its body once, in the slot its measure policy places between the chrome. */
    @Test
    fun frameDrawsItsBodyOnlyBetweenTheChrome() {
        val frame = source("MediaViewer.kt").functionBody("MediaViewerFrame")
        assertEquals("the body must have exactly one slot", 1, Regex("""content\s*=\s*body\b""").findAll(frame).count())
        assertFalse("the body must not be drawn under the chrome", Regex("""\bbody\(\)""").containsMatchIn(frame))
        assertTrue(
            "the frame must use the chrome-aware layout",
            "measurePolicy = mediaViewerChromeMeasurePolicy" in frame,
        )
        assertTrue(
            "the body must avoid the safe area the visible chrome does not already pad",
            "contentWindowInsets.only(mediaViewerContentInsetSides(chromeShown))" in frame,
        )
    }

    /** A current video page drives the chrome from its controller, reported before the player attaches. */
    @Test
    fun videoPagesRevealAndHideChromeWithTheirController() {
        val viewer = source("MediaViewer.kt").functionBody("FullScreenMediaViewer")
        assertTrue(
            "only the current video page may drive the chrome",
            "onControllerVisibilityChange = { visible -> if (isCurrent) chromeVisible = visible }" in viewer,
        )
        val factory = source("MediaVideo.kt").functionBody("viewerPlayerView")
        val listener = factory.indexOf("setControllerVisibilityListener(")
        val attach = factory.indexOf("this.player = player")
        assertTrue("the controller listener must be set", listener >= 0)
        assertTrue("the listener must precede the player so the first reveal is reported", listener in 0 until attach)
    }

    /** Visible chrome and a large font never overlap the body, in portrait or landscape, with or without cutouts. */
    @Test
    fun bodyStaysBetweenTheChromeAndInsideTheSafeArea() {
        var width by mutableStateOf(360.dp)
        var height by mutableStateOf(780.dp)
        var insets by mutableStateOf(Insets(0.dp, 42.dp, 0.dp, 28.dp))
        var chromeVisible by mutableStateOf(true)
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, fontScale = 1.6f) {
                // Unbounded and top-start, so a landscape frame keeps its full width and root-relative origin.
                Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).size(width, height)) {
                    Frame(insets.toWindowInsets(), chromeVisible)
                }
            }
        }

        listOf(
            Triple(360.dp, 780.dp, Insets(0.dp, 42.dp, 0.dp, 28.dp)),
            Triple(780.dp, 360.dp, Insets(56.dp, 18.dp, 28.dp, 22.dp)),
            Triple(780.dp, 360.dp, Insets(0.dp, 24.dp, 48.dp, 0.dp)),
        ).forEach { (frameWidth, frameHeight, frameInsets) ->
            composeRule.runOnIdle {
                width = frameWidth
                height = frameHeight
                insets = frameInsets
                chromeVisible = true
            }
            assertBodyBetweenChrome(frameInsets.safeArea(frameWidth, frameHeight))
            composeRule.runOnIdle { chromeVisible = false }
            assertEquals(frameInsets.safeArea(frameWidth, frameHeight), bounds(MEDIA_VIEWER_CONTENT_TAG))
        }
    }

    /** An active shell transport consumes the status-bar inset once, so neither chrome nor media loses it twice. */
    @Test
    fun consumedTransportInsetIsNotSubtractedTwice() {
        var chromeVisible by mutableStateOf(true)
        composeRule.setContent {
            Column(Modifier.size(360.dp, 780.dp)) {
                Box(Modifier.fillMaxWidth().height(TRANSPORT_HEIGHT))
                Box(Modifier.weight(1f).consumeWindowInsets(WindowInsets(top = 24.dp))) {
                    Frame(WindowInsets(top = 24.dp, bottom = 48.dp), chromeVisible)
                }
            }
        }

        assertEquals(TRANSPORT_HEIGHT.value, bounds(MEDIA_VIEWER_TOP_CHROME_TAG).top)
        assertBodyBetweenChrome(Rect(0f, TRANSPORT_HEIGHT.value, 360f, 780f - 48f))
        composeRule.runOnIdle { chromeVisible = false }
        assertEquals(Rect(0f, TRANSPORT_HEIGHT.value, 360f, 780f - 48f), bounds(MEDIA_VIEWER_CONTENT_TAG))
    }

    /** Renders the production frame with a body that fills whatever slot it is given. */
    @Composable
    private fun Frame(
        insets: WindowInsets,
        chromeVisible: Boolean,
    ) {
        MediaViewerFrame(
            senderLabel = "Alexandria Example",
            recordedAtLabel = "Oct 15, 2026, 9:41 AM",
            onDismiss = {},
            onSave = {},
            onShare = {},
            snackbarHostState = remember { SnackbarHostState() },
            contentWindowInsets = insets,
            onForwardMessage = {},
            chromeVisible = chromeVisible,
        ) {}
    }

    /** Asserts the body touches neither bar and stays inside [safeArea]. */
    private fun assertBodyBetweenChrome(safeArea: Rect) {
        val body = bounds(MEDIA_VIEWER_CONTENT_TAG)
        val top = bounds(MEDIA_VIEWER_TOP_CHROME_TAG)
        val bottom = bounds(MEDIA_VIEWER_BOTTOM_CHROME_TAG)
        assertEquals("the body must start where the top chrome ends", top.bottom, body.top)
        assertEquals("the body must end where the actions begin", bottom.top, body.bottom)
        assertTrue("the body $body must leave the side cutouts of $safeArea", body.left >= safeArea.left)
        assertTrue("the body $body must leave the side cutouts of $safeArea", body.right <= safeArea.right)
        assertTrue("the top chrome must pad the status area", top.bottom > safeArea.top)
        assertTrue("the actions must pad the navigation area", bottom.top < safeArea.bottom)
    }

    /** Root-relative bounds of the tagged node, in dp at this mdpi fixture. */
    private fun bounds(tag: String): Rect = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    /** Every `ContentScale.*` choice in [text]. */
    private fun scales(text: String): List<String> =
        Regex("""ContentScale\.(\w+)""")
            .findAll(text)
            .map { it.groupValues[1] }
            .toList()

    /** Reads a production media-viewer source from either the repository or app-module working directory. */
    private fun source(name: String): String =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/conversation/media/$name"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/media/$name"),
        ).firstOrNull(File::exists)?.readText() ?: error("Missing $name")

    /** Synthetic safe-area insets for one display shape. */
    private data class Insets(
        val left: Dp,
        val top: Dp,
        val right: Dp,
        val bottom: Dp,
    ) {
        /** The same insets as Compose window insets. */
        fun toWindowInsets() = WindowInsets(left, top, right, bottom)

        /** The rectangle these insets leave free in a frame of the given size. */
        fun safeArea(
            width: Dp,
            height: Dp,
        ) = Rect(left.value, top.value, (width - right).value, (height - bottom).value)
    }

    private companion object {
        val TRANSPORT_HEIGHT = 56.dp
    }
}
