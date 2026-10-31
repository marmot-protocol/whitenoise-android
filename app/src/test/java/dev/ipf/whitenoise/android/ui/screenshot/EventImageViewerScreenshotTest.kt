package dev.ipf.whitenoise.android.ui.screenshot

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.conversation.media.MEDIA_VIEWER_PAGE_GESTURE_TAG
import dev.ipf.whitenoise.android.ui.conversation.media.MEDIA_VIEWER_TOP_CHROME_TAG
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventImagePane
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The public-image entry renders the shared chat chrome; captures require reviewed generated goldens. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class EventImageViewerScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun publicImageLight() = render("event_image_viewer_light", dark = false)

    @Test
    fun publicImageDark() = render("event_image_viewer_dark", dark = true)

    @Test
    fun publicImageAmoledRtlLargeText() =
        render(
            "event_image_viewer_amoled_rtl_large",
            dark = true,
            amoled = true,
            rtl = true,
            fontScale = 2f,
        )

    private fun render(
        name: String,
        dark: Boolean,
        amoled: Boolean = false,
        rtl: Boolean = false,
        fontScale: Float = 1f,
    ) {
        val bitmap = Bitmap.createBitmap(180, 120, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bitmap).apply {
            drawColor(android.graphics.Color.rgb(17, 70, 80))
            val paint = android.graphics.Paint().apply { color = android.graphics.Color.rgb(245, 160, 64) }
            drawCircle(90f, 60f, 36f, paint)
        }
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = fontScale) {
                    NostrEventImagePane(
                        url = "https://images.example/article-cover",
                        initiallyRequested = true,
                        loadImage = { _, _ -> bitmap.asImageBitmap() },
                    )
                }
            }
        }
        composeRule.onNodeWithTag(MEDIA_VIEWER_TOP_CHROME_TAG).assertExists()
        // Size callbacks publish the fitted viewport on the next frame; capture after that layer settles.
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        val viewport = composeRule.onNodeWithTag(MEDIA_VIEWER_PAGE_GESTURE_TAG).fetchSemanticsNode().boundsInRoot
        val chrome = composeRule.onNodeWithTag(MEDIA_VIEWER_TOP_CHROME_TAG).fetchSemanticsNode().boundsInRoot
        val metrics =
            android.content.res.Resources
                .getSystem()
                .displayMetrics
        println(
            "EventImageFixture name=$name viewport=$viewport chrome=$chrome bitmap=180x120 " +
                "density=${metrics.density} dpi=${metrics.densityDpi} sdk=${android.os.Build.VERSION.SDK_INT}",
        )
        // Capture the actual fullscreen dialog root, including the shared image viewport and chrome.
        composeRule.onNode(isDialog()).captureRoboImage("src/test/snapshots/$name.png")
    }
}
