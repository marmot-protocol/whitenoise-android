package dev.ipf.whitenoise.android.ui.screenshot

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.conversation.media.MEDIA_VIEWER_CONTENT_TAG
import dev.ipf.whitenoise.android.ui.conversation.media.MediaViewerFrame
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Unzoomed photos fit whole inside the viewer's media slot. Edge-marked bitmaps are drawn the way the viewer
 * draws a decoded image — fill the slot, fit the bitmap — and the rendered pixels must show all four edges with
 * the chrome visible and hidden.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class MediaViewerPhotoFitScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** A tall photo fits whole between visible chrome, then grows to the safe area with every edge kept. */
    @Test
    fun tallPhotoKeepsAllFourEdgesWithChromeVisibleAndHidden() {
        var chromeVisible by mutableStateOf(true)
        renderEdgeMarkedPhoto(
            photo = edgeMarkedPhoto(width = 90, height = 160),
            insets = WindowInsets(top = 24.dp, bottom = 48.dp),
            chromeVisible = { chromeVisible },
        )

        assertPhotoEdgesVisible(photoWidth = 90, photoHeight = 160)
        composeRule.onRoot().captureRoboImage("src/test/snapshots/media_viewer_tall_photo_chrome_visible.png")
        composeRule.runOnIdle { chromeVisible = false }
        assertPhotoEdgesVisible(photoWidth = 90, photoHeight = 160)
        composeRule.onRoot().captureRoboImage("src/test/snapshots/media_viewer_tall_photo_chrome_hidden.png")
    }

    /** A wide photo in landscape keeps clear of cutouts and chrome, and fills the safe width once chrome hides. */
    @Test
    @Config(sdk = [36], qualifiers = "w780dp-h360dp-land-mdpi")
    fun landscapeWidePhotoKeepsAllFourEdgesBesideCutouts() {
        var chromeVisible by mutableStateOf(true)
        renderEdgeMarkedPhoto(
            photo = edgeMarkedPhoto(width = 160, height = 90),
            insets = WindowInsets(left = 56.dp, top = 18.dp, right = 28.dp, bottom = 22.dp),
            chromeVisible = { chromeVisible },
            darkTheme = true,
        )

        assertPhotoEdgesVisible(photoWidth = 160, photoHeight = 90)
        composeRule.onRoot().captureRoboImage("src/test/snapshots/media_viewer_wide_photo_landscape_visible.png")
        composeRule.runOnIdle { chromeVisible = false }
        assertPhotoEdgesVisible(photoWidth = 160, photoHeight = 90)
        composeRule.onRoot().captureRoboImage("src/test/snapshots/media_viewer_wide_photo_landscape_hidden.png")
    }

    /** Renders [photo] with the viewer's unzoomed decoded-image drawing: fill the media slot, fit the bitmap. */
    private fun renderEdgeMarkedPhoto(
        photo: Bitmap,
        insets: WindowInsets,
        chromeVisible: () -> Boolean,
        darkTheme: Boolean = false,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme, fontScale = 1.3f) {
                MediaViewerFrame(
                    senderLabel = "Alex",
                    recordedAtLabel = "Oct 15, 2026, 9:41 AM",
                    onDismiss = {},
                    onSave = {},
                    onShare = {},
                    onForwardMessage = {},
                    snackbarHostState = remember { SnackbarHostState() },
                    contentWindowInsets = insets,
                    chromeVisible = chromeVisible(),
                ) {
                    Image(
                        bitmap = photo.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    /** Samples the drawn pixels inside each fitted edge band and requires its marker colour. */
    private fun assertPhotoEdgesVisible(
        photoWidth: Int,
        photoHeight: Int,
    ) {
        val slot = composeRule.onNodeWithTag(MEDIA_VIEWER_CONTENT_TAG).fetchSemanticsNode().boundsInRoot
        val pixels = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val scale = minOf(slot.width / photoWidth, slot.height / photoHeight)
        val drawnWidth = photoWidth * scale
        val drawnHeight = photoHeight * scale
        val left = slot.left + (slot.width - drawnWidth) / 2
        val top = slot.top + (slot.height - drawnHeight) / 2
        val band = EDGE_PX * scale / 2
        listOf(
            Triple("top", Offset(left + drawnWidth / 2, top + band), TOP),
            Triple("bottom", Offset(left + drawnWidth / 2, top + drawnHeight - band), BOTTOM),
            Triple("left", Offset(left + band, top + drawnHeight / 2), LEFT),
            Triple("right", Offset(left + drawnWidth - band, top + drawnHeight / 2), RIGHT),
        ).forEach { (edge, point, expected) ->
            val actual = pixels.getPixel(point.x.toInt(), point.y.toInt())
            assertEquals("$edge edge at $point is not its marker", expected, actual)
        }
    }

    /** A photo whose four edges carry distinct opaque marker bands around a neutral middle. */
    private fun edgeMarkedPhoto(
        width: Int,
        height: Int,
    ): Bitmap {
        val pixels =
            IntArray(width * height) { index ->
                val x = index % width
                val y = index / width
                when {
                    y < EDGE_PX -> TOP
                    y >= height - EDGE_PX -> BOTTOM
                    x < EDGE_PX -> LEFT
                    x >= width - EDGE_PX -> RIGHT
                    else -> MIDDLE
                }
            }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private companion object {
        const val EDGE_PX = 12
        const val TOP = 0xFFD7263D.toInt()
        const val BOTTOM = 0xFF1B998B.toInt()
        const val LEFT = 0xFF2E86DE.toInt()
        const val RIGHT = 0xFFF4D35E.toInt()
        const val MIDDLE = 0xFF6C757D.toInt()
    }
}
