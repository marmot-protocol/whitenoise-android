package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.core.RemoteGiphyMedia
import dev.ipf.whitenoise.android.media.GiphySearchResult
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseSheetHeader
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
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

    /** The picker discloses provider contact and does not submit until a GIF is selected. */
    @Test
    fun showsDisclosureAndSendsOnlyTheSelectedResult() {
        var selected: String? = null
        render(GiphySearchState.Results(results), onSend = { selected = it.media.wireText() })

        composeRule.onNodeWithContentDescription("Powered by GIPHY").assertIsDisplayed()
        composeRule.onNodeWithText("Your search and IP address are sent to GIPHY. Opening a received GIF also contacts GIPHY.")
            .assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/giphy_search_results_light.png")
        composeRule.onNodeWithText("Dancing cat").performClick()
        composeRule.runOnIdle { assertEquals(results.first().media.wireText(), selected) }
    }

    /** A failed search remains retryable at large font scale in RTL. */
    @Test
    @Config(qualifiers = "en-w320dp-h650dp-mdpi")
    fun showsRetryInDarkLargeRtl() {
        var retried = false
        render(GiphySearchState.Failed, dark = true, rtl = true, onSearch = { retried = true })

        composeRule.onNodeWithText("Retry").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/giphy_search_retry_dark_rtl.png")
        composeRule.onNodeWithText("Retry").performClick()
        composeRule.runOnIdle { assertEquals(true, retried) }
    }

    private fun render(
        state: GiphySearchState,
        dark: Boolean = false,
        rtl: Boolean = false,
        onSearch: () -> Unit = {},
        onSend: (GiphySearchResult) -> Unit = {},
    ) {
        val field = TextFieldState("cats")
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                WhiteNoiseTheme(darkTheme = dark, fontScale = if (rtl) 1.6f else 1f) {
                    Surface {
                        Column {
                            WhiteNoiseSheetHeader("Search GIFs", onClose = {})
                            GiphySearchContent(field, state, onSearch, onSend, preview = { null })
                        }
                    }
                }
            }
        }
    }

    private val results =
        listOf("Dancing cat", "Sleepy cat").mapIndexed { index, title ->
            val media = RemoteGiphyMedia("https://media.giphy.com/media/cat$index/giphy.gif", "Cats")
            GiphySearchResult(title, media, media)
        }
}
