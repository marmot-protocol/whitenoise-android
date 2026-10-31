package dev.ipf.whitenoise.android.ui.screenshot

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.descendants
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.conversation.media.MEDIA_VIEWER_PAGE_GESTURE_TAG
import dev.ipf.whitenoise.android.ui.conversation.media.MEDIA_VIEWER_TOP_CHROME_TAG
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventImagePane
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
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
        settleFullscreenDialog()
        // Publish the viewport size callback from the final Android window measurement.
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        val viewport = composeRule.onNodeWithTag(MEDIA_VIEWER_PAGE_GESTURE_TAG).fetchSemanticsNode().boundsInRoot
        val chrome = composeRule.onNodeWithTag(MEDIA_VIEWER_TOP_CHROME_TAG).fetchSemanticsNode().boundsInRoot
        val dialog = composeRule.onNode(isDialog()).fetchSemanticsNode().boundsInRoot
        assertEquals(SCREEN_HEIGHT_PX.toFloat(), dialog.bottom, 0f)
        assertEquals(SCREEN_HEIGHT_PX.toFloat(), viewport.bottom, 0f)
        val metrics =
            android.content.res.Resources
                .getSystem()
                .displayMetrics
        println(
            "EventImageFixture name=$name viewport=$viewport chrome=$chrome dialog=$dialog bitmap=180x120 " +
                "density=${metrics.density} dpi=${metrics.densityDpi} sdk=${android.os.Build.VERSION.SDK_INT}",
        )
        // Capture the actual fullscreen dialog root, including the shared image viewport and chrome.
        composeRule.onNode(isDialog()).captureRoboImage("src/test/snapshots/$name.png")
    }

    /** Finish Compose DialogLayout's provisional height+1 fullscreen-admission measurement. */
    private fun settleFullscreenDialog() {
        composeRule.runOnIdle {
            // Use the same real-window discovery as MediaViewerDialogTestFrames, scoped to this fixture.
            val decor =
                WindowInspector.getGlobalWindowViews().single {
                    val params = it.layoutParams as? WindowManager.LayoutParams
                    params?.type == WindowManager.LayoutParams.TYPE_APPLICATION
                }
            val provider = (decor as ViewGroup).descendants.filterIsInstance<DialogWindowProvider>().single()
            val window = provider.window
            // DialogLayout must already have admitted fullscreen before we submit its final traversal.
            assertEquals(WindowManager.LayoutParams.MATCH_PARENT, window.attributes.height)
            assertEquals(WindowManager.LayoutParams.MATCH_PARENT, window.attributes.width)
            assertEquals(SCREEN_WIDTH_PX, decor.resources.displayMetrics.widthPixels)
            assertEquals(SCREEN_HEIGHT_PX, decor.resources.displayMetrics.heightPixels)
            // Native Robolectric does not reliably perform the WindowManager remeasure after admission.
            // Supply that exact display-bounded traversal, as the standalone drawable fixtures do.
            (provider as View).requestLayout()
            decor.requestLayout()
            decor.measure(
                View.MeasureSpec.makeMeasureSpec(SCREEN_WIDTH_PX, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(SCREEN_HEIGHT_PX, View.MeasureSpec.EXACTLY),
            )
            decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
            assertEquals(SCREEN_WIDTH_PX, decor.width)
            assertEquals(SCREEN_HEIGHT_PX, decor.height)
        }
    }

    private companion object {
        const val SCREEN_WIDTH_PX = 360
        const val SCREEN_HEIGHT_PX = 780
    }
}
