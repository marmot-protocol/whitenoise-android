package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.ui.conversation.media.MasonryImageLayout
import dev.ipf.whitenoise.android.ui.conversation.media.imageBubbleSizing
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercises real single-image/grid constraints and footer layout without introducing media loader fixtures. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class FocusedMediaCanvasTest {
    @get:Rule val composeRule = createComposeRule()

    private var focused by mutableStateOf(false)
    private var parentWidth by mutableStateOf(320.dp)
    private var ownerStarts = 0
    private var ownerDisposals = 0

    @Test
    fun landscapeCanvasShrinksWhileTimeStatusAndOwnerStayNative() {
        render(ratio = 1.6f)
        assertNativeCanvasTransition(320f, 200f, "focused_media_canvas_landscape")
    }

    @Test
    fun portraitLargeFontRtlKeepsFullSizeFooterAndUnscaledFileSibling() {
        render(ratio = 0.5f, dark = true, fontScale = 2f, rtl = true)
        assertNativeCanvasTransition(280f, 340f, "focused_media_canvas_portrait_large_rtl")
    }

    @Test
    fun focusedWindowResizeUsesTheNewNormalImageCanvasAndTimelineFootprint() {
        render(ratio = 1.6f)
        assertOpenResizeAndClose(expectedWideHeight = 200f, expectedNarrowHeight = 100f)
    }

    @Test
    fun focusedWindowResizeUsesTheActualNativeAlbumLayout() {
        render(album = true)
        assertOpenResizeAndClose(expectedWideHeight = 320f, expectedNarrowHeight = 160f)
    }

    private fun assertOpenResizeAndClose(
        expectedWideHeight: Float,
        expectedNarrowHeight: Float,
    ) {
        val normalFootprint = bounds("timeline-footprint")
        val originalTime = composeRule.onNodeWithText("12:34").fetchSemanticsNode().boundsInRoot
        composeRule.runOnIdle { focused = true }
        composeRule.waitForIdle()
        assertEquals(240f, bounds("canvas").width, 1f)
        assertEquals(expectedWideHeight * 0.75f, bounds("canvas").height, 1f)
        assertEquals(normalFootprint.width, bounds("timeline-footprint").width, 1f)
        assertEquals(normalFootprint.height, bounds("timeline-footprint").height, 1f)

        composeRule.runOnIdle { parentWidth = 160.dp }
        composeRule.waitForIdle()
        assertEquals("75% of the new160dp canvas, not100% of its available width", 120f, bounds("canvas").width, 1f)
        assertEquals(expectedNarrowHeight * 0.75f, bounds("canvas").height, 1f)
        val focusedNarrowFootprint = bounds("timeline-footprint")
        assertEquals(160f, focusedNarrowFootprint.width, 1f)
        val narrowTime = composeRule.onNodeWithText("12:34").fetchSemanticsNode().boundsInRoot
        assertEquals(originalTime.width, narrowTime.width, 1f)
        assertEquals(originalTime.height, narrowTime.height, 1f)

        composeRule.runOnIdle { focused = false }
        composeRule.waitForIdle()
        assertEquals(160f, bounds("canvas").width, 1f)
        assertEquals(expectedNarrowHeight, bounds("canvas").height, 1f)
        assertEquals(focusedNarrowFootprint.height, bounds("timeline-footprint").height, 1f)
        assertEquals(1, ownerStarts)
        assertEquals(0, ownerDisposals)
    }

    private fun assertNativeCanvasTransition(
        width: Float,
        height: Float,
        snapshot: String,
    ) {
        val originalTime = composeRule.onNodeWithText("12:34").fetchSemanticsNode().boundsInRoot
        val originalFile = bounds("file-sibling")
        val originalControl = bounds("media-control")
        assertEquals(width, bounds("canvas").width, 1f)
        assertEquals(height, bounds("canvas").height, 1f)

        composeRule.runOnIdle { focused = true }
        composeRule.waitForIdle()
        val time = composeRule.onNodeWithText("12:34").fetchSemanticsNode().boundsInRoot
        assertEquals(width * 0.75f, bounds("canvas").width, 1f)
        assertEquals(height * 0.75f, bounds("canvas").height, 1f)
        assertEquals(originalTime.width, time.width, 1f)
        assertEquals(originalTime.height, time.height, 1f)
        assertEquals(originalControl.width, bounds("media-control").width, 1f)
        assertEquals(originalControl.height, bounds("media-control").height, 1f)
        assertEquals(originalFile.width, bounds("file-sibling").width, 1f)
        assertEquals(originalFile.height, bounds("file-sibling").height, 1f)
        assertEquals(1, ownerStarts)
        assertEquals(0, ownerDisposals)
        composeRule.onNodeWithTag("media-fixture").captureRoboImage("src/test/snapshots/$snapshot.png")

        composeRule.runOnIdle { focused = false }
        composeRule.waitForIdle()
        assertEquals(width, bounds("canvas").width, 1f)
        assertEquals(height, bounds("canvas").height, 1f)
        assertEquals(1, ownerStarts)
        assertEquals(0, ownerDisposals)
    }

    @Suppress("LongMethod") // One native media tree supplies both sizing passes and its lifetime assertion.
    private fun render(
        ratio: Float = 1f,
        dark: Boolean = false,
        fontScale: Float = 1f,
        rtl: Boolean = false,
        album: Boolean = false,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark) {
                    Box(Modifier.width(parentWidth).testTag("media-fixture")) {
                        LookaheadScope {
                            Column(
                                Modifier
                                    .testTag("timeline-footprint")
                                    .focusedMediaFootprint(focused, maximumPreviewWidth = 528, mine = false),
                            ) {
                                VisualMediaFooterFrame(
                                    showFooter = true,
                                    timeText = "12:34",
                                    showStatus = true,
                                    status = MessageStatus.Sent,
                                    retention = null,
                                    reserveRetentionSpace = false,
                                    focusedPreview = focused,
                                ) {
                                    DisposableEffect(Unit) {
                                        ownerStarts += 1
                                        onDispose { ownerDisposals += 1 }
                                    }
                                    if (album) {
                                        Box(Modifier.testTag("canvas")) {
                                            MasonryImageLayout(visibleCount = 4) { _, tileModifier ->
                                                Box(tileModifier.background(Color(0xFF42687A)))
                                            }
                                        }
                                    } else {
                                        Box(imageBubbleSizing(ratio).background(Color(0xFF42687A)).testTag("canvas"))
                                    }
                                    Box(
                                        Modifier
                                            .align(Alignment.Center)
                                            .size(32.dp)
                                            .background(Color.White)
                                            .testTag("media-control"),
                                    )
                                }
                                Text(
                                    "file.pdf",
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.width(280.dp).testTag("file-sibling"),
                                )
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun bounds(tag: String) = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
}
