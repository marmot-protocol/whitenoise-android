package dev.ipf.whitenoise.android.ui.group

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp")
class ImageSearchPreviewPresentationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun bannerPreviewIsWideInsteadOfCircular() {
        composeRule.setContent {
            WhiteNoiseTheme {
                ImageSearchPreview(
                    title = "Alice",
                    header = "Profile banner",
                    seed = "alice",
                    previewUrl = null,
                    subtitle = "Paste a URL or search the web",
                    presentation = ImagePreviewPresentation.Banner,
                    imageLoader = { null },
                )
            }
        }

        val bounds = composeRule.onNodeWithTag(IMAGE_SEARCH_BANNER_PREVIEW_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue("banner preview must be at least twice as wide as it is tall", bounds.width >= bounds.height * 2f)
    }

    @Test
    fun changingPreviewUrlLoadsTheNewBannerInsteadOfRetainingTheOldBitmap() {
        var previewUrl by mutableStateOf<String?>("https://example.com/one.jpg")
        val loadedUrls = mutableListOf<String>()
        composeRule.setContent {
            WhiteNoiseTheme {
                ImageSearchPreview(
                    title = "Alice",
                    header = "Profile banner",
                    seed = "alice",
                    previewUrl = previewUrl,
                    subtitle = "Ready",
                    presentation = ImagePreviewPresentation.Banner,
                    imageLoader = { url ->
                        loadedUrls += url
                        ImageBitmap(2, 1)
                    },
                )
            }
        }
        composeRule.waitForIdle()
        assertEquals(listOf("https://example.com/one.jpg"), loadedUrls)

        composeRule.runOnIdle { previewUrl = "https://example.com/two.jpg" }
        composeRule.waitForIdle()
        assertEquals(
            listOf("https://example.com/one.jpg", "https://example.com/two.jpg"),
            loadedUrls,
        )
    }
}
