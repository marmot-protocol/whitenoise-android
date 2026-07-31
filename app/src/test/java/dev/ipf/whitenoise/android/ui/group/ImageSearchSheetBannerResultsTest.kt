package dev.ipf.whitenoise.android.ui.group

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import dev.ipf.whitenoise.android.media.ImageSearchClient
import dev.ipf.whitenoise.android.media.ImageSearchResult
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h640dp")
class ImageSearchSheetBannerResultsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun bannerSearchKeepsActionsVisibleAndAllowsSelectingAResult() {
        val hits =
            List(12) { index ->
                ImageSearchResult(
                    imageUrl = "https://example.com/banner-$index.jpg",
                    thumbnailUrl = null,
                    sourceHost = "example.com",
                    dimensionsLabel = "1200×600",
                    title = "Mountain $index",
                )
            }
        var appliedUrl: String? = null

        composeRule.setContent {
            WhiteNoiseTheme {
                ImageSearchSheet(
                    initialUrl = "",
                    header = "Profile banner",
                    title = "Clever Pony",
                    seed = "seed",
                    urlLabel = "Banner URL",
                    applyImageLabel = "Use this banner",
                    applyInFlight = false,
                    onApply = { appliedUrl = it },
                    onDismiss = {},
                    previewPresentation = ImagePreviewPresentation.Banner,
                    searchClient =
                        object : ImageSearchClient {
                            override suspend fun search(query: String): List<ImageSearchResult> = hits
                        },
                    resultImageLoader = { null },
                )
            }
        }

        composeRule.onNodeWithText("Image search").performTextInput("mountains")
        composeRule.onNodeWithContentDescription("Search").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(IMAGE_SEARCH_RESULTS_TAG).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag(IMAGE_SEARCH_ACTIONS_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Mountain 0").performScrollTo().performClick()
        composeRule
            .onNodeWithText("Use this banner")
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()

        composeRule.runOnIdle {
            assertEquals("https://example.com/banner-0.jpg", appliedUrl)
        }
    }
}
