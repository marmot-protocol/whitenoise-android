package dev.ipf.whitenoise.android.ui.profile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
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
@Config(sdk = [36], qualifiers = "en")
class ProfileBannerControlTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun emptyBannerIsATappableWideImagePlaceholder() {
        var opened = false
        composeRule.setContent {
            WhiteNoiseTheme {
                ProfileBannerControl(
                    bannerUrl = null,
                    isValid = true,
                    isUploading = false,
                    onClick = { opened = true },
                )
            }
        }

        composeRule.onNodeWithText(app.getString(R.string.profile_banner_placeholder)).assertIsDisplayed()
        composeRule
            .onNodeWithTag(PROFILE_BANNER_CONTROL_TAG)
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        assertTrue(opened)
    }

    @Test
    fun invalidBannerShowsInlineErrorAndRemainsRepairable() {
        var opened = false
        composeRule.setContent {
            WhiteNoiseTheme {
                ProfileBannerControl(
                    bannerUrl = null,
                    isValid = false,
                    isUploading = false,
                    onClick = { opened = true },
                )
            }
        }

        composeRule.onNodeWithText(app.getString(R.string.profile_banner_invalid)).assertIsDisplayed()
        composeRule.onNodeWithTag(PROFILE_BANNER_CONTROL_TAG).performClick()
        assertTrue(opened)
    }

    @Test
    fun uploadingBannerShowsProgressAndDisablesConflictingTap() {
        var opened = false
        composeRule.setContent {
            WhiteNoiseTheme {
                ProfileBannerControl(
                    bannerUrl = null,
                    isValid = true,
                    isUploading = true,
                    onClick = { opened = true },
                )
            }
        }

        composeRule.onNodeWithText(app.getString(R.string.profile_banner_uploading)).assertIsDisplayed()
        composeRule.onNodeWithTag(PROFILE_BANNER_CONTROL_TAG).assertIsNotEnabled().performClick()
        assertTrue(!opened)
    }

    @Test
    fun replacingExistingBannerKeepsProgressVisibleOverThePreview() {
        var isUploading by mutableStateOf(false)
        composeRule.setContent {
            WhiteNoiseTheme {
                ProfileBannerControl(
                    bannerUrl = "https://example.com/banner.jpg",
                    isValid = true,
                    isUploading = isUploading,
                    imageLoader = { ImageBitmap(3, 1) },
                    onClick = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle { isUploading = true }

        composeRule.onNodeWithText(app.getString(R.string.profile_banner_uploading)).assertIsDisplayed()
        composeRule.onNodeWithTag(PROFILE_BANNER_CONTROL_TAG).assertIsNotEnabled()
    }

    @Test
    fun existingBannerUsesTheWidePreviewInsteadOfTheEmptyPlaceholder() {
        composeRule.setContent {
            WhiteNoiseTheme {
                ProfileBannerControl(
                    bannerUrl = "https://example.com/banner.jpg",
                    isValid = true,
                    isUploading = false,
                    imageLoader = { null },
                    onClick = {},
                )
            }
        }

        composeRule.onNodeWithTag(PROFILE_BANNER_CONTROL_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(app.getString(R.string.profile_banner_placeholder)).assertDoesNotExist()
    }

    @Test
    fun changingOrClearingBannerUrlNeverRetainsThePreviousBitmap() {
        var bannerUrl by mutableStateOf<String?>("https://example.com/one.jpg")
        val loadedUrls = mutableListOf<String>()
        composeRule.setContent {
            WhiteNoiseTheme {
                ProfileBannerControl(
                    bannerUrl = bannerUrl,
                    isValid = true,
                    isUploading = false,
                    imageLoader = { url ->
                        loadedUrls += url
                        ImageBitmap(2, 1)
                    },
                    onClick = {},
                )
            }
        }
        composeRule.waitForIdle()
        assertEquals(listOf("https://example.com/one.jpg"), loadedUrls)

        composeRule.runOnIdle { bannerUrl = "https://example.com/two.jpg" }
        composeRule.waitForIdle()
        assertEquals(
            listOf("https://example.com/one.jpg", "https://example.com/two.jpg"),
            loadedUrls,
        )

        composeRule.runOnIdle { bannerUrl = null }
        composeRule.onNodeWithText(app.getString(R.string.profile_banner_placeholder)).assertIsDisplayed()
    }
}
