package dev.ipf.whitenoise.android.ui.conversation.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.core.RemoteGiphyMedia
import dev.ipf.whitenoise.android.media.GiphySearchResult
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseSheetHeader
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Captures the picker with mock provider results and proves selection forwards the chosen rendition. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h650dp-mdpi")
class GiphySearchSheetScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Results stay disabled until their own animation is ready, and failed previews cannot send. */
    @Test
    fun sendsOnlyAResultWithAPlayablePreview() {
        var selected: String? = null
        val secondPreview = mutableStateOf<GiphyPreviewState>(GiphyPreviewState.Loading)
        val playable = GiphyPreviewState.Playable(fixturePreview())
        render(
            GiphySearchState.Results(results),
            onSend = { selected = it.media.wireText() },
            preview = { if (it == results.first()) playable else secondPreview.value },
        )

        composeRule.onNodeWithContentDescription("Powered by GIPHY").assertIsDisplayed()
        composeRule
            .onNodeWithText("GIPHY receives your search and IP address. Recipients’ phones may automatically load GIFs from GIPHY, sharing their IP address.")
            .assertIsDisplayed()
        val second = composeRule.onNodeWithText("Sleepy cat")
        second.assertIsNotEnabled().performTouchInput { click() }
        composeRule.runOnIdle { assertNull(selected) }
        composeRule.runOnIdle { secondPreview.value = GiphyPreviewState.Unavailable }
        second.assertIsNotEnabled().performTouchInput { click() }
        composeRule.runOnIdle { assertNull(selected) }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/giphy_search_results_light.png")
        composeRule.onNodeWithContentDescription("Dancing cat").performClick()
        composeRule.runOnIdle { assertEquals(results.first().media.wireText(), selected) }
    }

    /** A failed search remains retryable at large font scale in RTL. */
    @Test
    @Config(qualifiers = "en-w320dp-h650dp-mdpi")
    fun showsRetryInDarkLargeRtl() {
        var retried = false
        render(GiphySearchState.Failed, darkRtl = true, onRetry = { retried = true })

        composeRule.onNodeWithText("Retry").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/giphy_search_retry_dark_rtl.png")
        composeRule.onNodeWithText("Retry").performClick()
        composeRule.runOnIdle { assertEquals(true, retried) }
    }

    private fun render(
        state: GiphySearchState,
        darkRtl: Boolean = false,
        onRetry: () -> Unit = {},
        onSend: (GiphySearchResult) -> Unit = {},
        preview: (GiphySearchResult) -> GiphyPreviewState = { GiphyPreviewState.Loading },
    ) {
        val field = TextFieldState("cats")
        val direction = if (darkRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                WhiteNoiseTheme(darkTheme = darkRtl, fontScale = if (darkRtl) 1.6f else 1f) {
                    Surface {
                        Column {
                            WhiteNoiseSheetHeader("Search GIFs", onClose = {})
                            GiphySearchContent(field, state, onRetry, onSend, preview = { item, _ -> preview(item) })
                        }
                    }
                }
            }
        }
    }

    /** Deterministic raster frames let goldens exercise the same playable content as the real picker. */
    private fun fixturePreview(): GiphyPreview =
        GiphyPreview(
            listOf(42f, 56f).map { pupilX ->
                val bitmap = Bitmap.createBitmap(160, 128, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                canvas.drawColor(Color.rgb(226, 211, 249))
                paint.color = Color.rgb(247, 173, 103)
                canvas.drawOval(28f, 43f, 132f, 119f, paint)
                canvas.drawCircle(50f, 49f, 20f, paint)
                canvas.drawCircle(110f, 49f, 20f, paint)
                paint.color = Color.rgb(67, 44, 50)
                canvas.drawCircle(pupilX + 12f, 78f, 5f, paint)
                canvas.drawCircle(pupilX + 50f, 78f, 5f, paint)
                canvas.drawOval(75f, 89f, 85f, 96f, paint)
                GiphyPreviewFrame(bitmap.asImageBitmap(), durationMs = 60_000L)
            },
        )

    private val results =
        listOf("Dancing cat", "Sleepy cat").mapIndexed { index, title ->
            val media = RemoteGiphyMedia("https://media.giphy.com/media/cat$index/giphy.gif", "Cats")
            GiphySearchResult(title, media)
        }
}
