@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Pins the too-large-to-preview tile control and viewer state across themes, RTL and large fonts. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w320dp-h640dp-mdpi")
class MediaTooLargeToPreviewScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Light theme with the default font scale. */
    @Test
    fun tooLargeToPreviewLight() = capture("light")

    /** Dark theme keeps the same control, caption and viewer explanation. */
    @Test
    fun tooLargeToPreviewDark() = capture("dark", dark = true)

    /** Large type stays bounded on a narrow right-to-left surface. */
    @Test
    fun tooLargeToPreviewLargeRtl() = capture("large_rtl", rtl = true, fontScale = 1.6f)

    /** The same large right-to-left states remain readable in dark mode. */
    @Test
    fun tooLargeToPreviewDarkLargeRtl() = capture("dark_large_rtl", dark = true, rtl = true, fontScale = 1.6f)

    /** Renders the gallery, checks what a reader hears and that no Retry is offered, then records the baseline. */
    private fun capture(
        name: String,
        dark: Boolean = false,
        rtl: Boolean = false,
        fontScale: Float = 1f,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark, fontScale = fontScale) {
                val direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
                CompositionLocalProvider(LocalLayoutDirection provides direction) { Gallery() }
            }
        }
        composeRule.onAllNodesWithContentDescription("Too large to preview").assertCountEquals(2)
        composeRule.onAllNodesWithText("Tap to retry").assertCountEquals(0)
        composeRule.onRoot().captureRoboImage("src/test/snapshots/media_too_large_to_preview_$name.png")
    }

    /** A photo tile with its caption, a small album tile, and the viewer page's explanation. */
    @Composable
    private fun Gallery() {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(width = 190.dp, height = 110.dp).background(PLACEHOLDER),
                ) {
                    MediaTooLargeToPreviewControl(onOpen = {}, showCaption = true)
                }
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(80.dp).background(PLACEHOLDER)) {
                    MediaTooLargeToPreviewControl(onOpen = {})
                }
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(VIEWER_HEIGHT)
                        .background(MaterialTheme.colorScheme.background),
            ) {
                MediaViewerTooLargeToPreview()
            }
        }
    }

    private companion object {
        val PLACEHOLDER = Color(0xFF8A8F98)
        val VIEWER_HEIGHT = 260.dp
    }
}
