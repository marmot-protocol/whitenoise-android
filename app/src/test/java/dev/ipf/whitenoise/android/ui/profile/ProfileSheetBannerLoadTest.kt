package dev.ipf.whitenoise.android.ui.profile

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.awaitCancellation
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ProfileSheetBannerLoadTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun failedBannerLoadStopsSpinningAndDropsTheBanner() {
        composeRule.setContent {
            WhiteNoiseTheme {
                ProfileBannerImage(BANNER_URL, peek = { null }, load = { null })
            }
        }

        composeRule.onAllNodesWithTag(PROFILE_BANNER_LOADING_TAG).assertCountEquals(0)
        composeRule.onAllNodesWithTag(PROFILE_BANNER_TAG).assertCountEquals(0)
    }

    @Test
    fun bannerShowsProgressWhileTheLoadIsStillRunning() {
        composeRule.setContent {
            WhiteNoiseTheme {
                ProfileBannerImage(BANNER_URL, peek = { null }, load = { awaitCancellation() })
            }
        }

        composeRule.onNodeWithTag(PROFILE_BANNER_LOADING_TAG).assertExists()
        composeRule.onNodeWithTag(PROFILE_BANNER_TAG).assertExists()
    }

    private companion object {
        const val BANNER_URL = "https://example.com/banner.png"
    }
}
