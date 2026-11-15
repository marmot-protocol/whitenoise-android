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
 * Invariant gate: the full-screen viewer fits whole unzoomed media in one fixed safe-area rectangle. Every image uses
 * its original-source fit rectangle and videos use fit scaling. The frame gives its body the safe area whether or not
 * the chrome shows, so toggling the chrome or a video's controls never moves or resizes the media (#3801), and a video
 * page shares one visibility state with the chrome.
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

        val controls = source("VideoViewerControls.kt")
        val factory = controls.functionBody("viewerPlayerView")
        assertEquals(listOf("RESIZE_MODE_FIT"), Regex("""RESIZE_MODE_\w+""").findAll(factory).map { it.value }.toList())
        assertTrue("the viewer's own controls replace the native controller", "useController = false" in factory)
        assertTrue(
            "the viewer surface must build its player through the shared factory",
            "viewerPlayerView(ctx, player)" in controls.functionBody("VideoViewerSurface"),
        )
        assertTrue(
            "the viewer page must render the shared surface",
            "VideoViewerSurface(" in source("MediaVideo.kt").functionBody("VideoViewerPage"),
        )
    }

    /** The frame draws its body once, padded by the same safe-area sides whether or not the chrome shows. */
    @Test
    fun frameDrawsItsBodyOnceInAFixedRectangle() {
        val frame = source("MediaViewer.kt").functionBody("MediaViewerFrame")
        assertEquals("the body must have exactly one slot", 1, Regex("""content\s*=\s*body\b""").findAll(frame).count())
        assertFalse("the body must not be drawn twice", Regex("""\bbody\(\)""").containsMatchIn(frame))
        assertTrue(
            "the body must pad every safe-area side regardless of the chrome",
            "contentWindowInsets.only(MEDIA_VIEWER_CONTENT_INSET_SIDES)" in frame,
        )
    }

    /** A current video page shows and hides its controls with the chrome through one visibility state. */
    @Test
    fun videoPagesShareVisibilityWithTheChrome() {
        val viewer = source("MediaViewer.kt").functionBody("FullScreenMediaViewer")
        assertTrue(
            "only the current video page shows controls, and only with the chrome",
            "controlsVisible = isCurrent && chromeVisible" in viewer,
        )
        assertTrue(
            "only the current video page may drive the chrome",
            "onControlsVisibleChange = { visible -> if (isCurrent) chromeVisible = visible }" in viewer,
        )
    }

    /** The body fills the safe area with the chrome shown or hidden, in either orientation, with or without cutouts. */
    @Test
    fun bodyFillsTheSafeAreaWhetherOrNotTheChromeShows() {
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
            val safeArea = frameInsets.safeArea(frameWidth, frameHeight)
            composeRule.runOnIdle {
                width = frameWidth
                height = frameHeight
                insets = frameInsets
                chromeVisible = true
            }
            assertEquals(
                "the body fills the safe area under visible chrome",
                safeArea,
                bounds(MEDIA_VIEWER_CONTENT_TAG),
            )
            assertChromePadsTheSystemBars(safeArea)
            composeRule.runOnIdle { chromeVisible = false }
            assertEquals("hiding the chrome must not move the body", safeArea, bounds(MEDIA_VIEWER_CONTENT_TAG))
            composeRule.onNodeWithTag(MEDIA_VIEWER_TOP_CHROME_TAG).assertDoesNotExist()
            composeRule.onNodeWithTag(MEDIA_VIEWER_BOTTOM_CHROME_TAG).assertDoesNotExist()
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

        val safeArea = Rect(0f, TRANSPORT_HEIGHT.value, 360f, 780f - 48f)
        assertEquals(TRANSPORT_HEIGHT.value, bounds(MEDIA_VIEWER_TOP_CHROME_TAG).top)
        assertEquals(safeArea, bounds(MEDIA_VIEWER_CONTENT_TAG))
        assertChromePadsTheSystemBars(safeArea)
        composeRule.runOnIdle { chromeVisible = false }
        assertEquals(safeArea, bounds(MEDIA_VIEWER_CONTENT_TAG))
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

    /** Asserts the bars reach past the system bars, with their controls inside the [safeArea]. */
    private fun assertChromePadsTheSystemBars(safeArea: Rect) {
        val top = bounds(MEDIA_VIEWER_TOP_CHROME_TAG)
        val bottom = bounds(MEDIA_VIEWER_BOTTOM_CHROME_TAG)
        assertTrue("the top chrome $top must pad the status area of $safeArea", top.bottom > safeArea.top)
        assertTrue("the actions $bottom must pad the navigation area of $safeArea", bottom.top < safeArea.bottom)
        assertEquals(
            "the actions must reserve exactly the clearance video controls avoid",
            safeArea.bottom - MEDIA_VIEWER_BOTTOM_ACTIONS_HEIGHT.value,
            bottom.top,
        )
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
