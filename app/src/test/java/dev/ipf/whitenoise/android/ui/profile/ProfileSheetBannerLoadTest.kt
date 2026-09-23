package dev.ipf.whitenoise.android.ui.profile

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
                ProfileBannerImage(BANNER_URL, peek = { _, _ -> null }, load = { _, _ -> null })
            }
        }

        composeRule.onAllNodesWithTag(PROFILE_BANNER_LOADING_TAG).assertCountEquals(0)
        composeRule.onAllNodesWithTag(PROFILE_BANNER_TAG).assertCountEquals(0)
    }

    @Test
    fun bannerShowsProgressWhileTheLoadIsStillRunning() {
        composeRule.setContent {
            WhiteNoiseTheme {
                ProfileBannerImage(BANNER_URL, peek = { _, _ -> null }, load = { _, _ -> awaitCancellation() })
            }
        }

        composeRule.onNodeWithTag(PROFILE_BANNER_LOADING_TAG).assertExists()
        composeRule.onNodeWithTag(PROFILE_BANNER_TAG).assertExists()
    }

    @Test
    fun theHeaderOverlapSeamReportsAFailedBannerAsAbsent() {
        // The overlap layout reserves space for the banner, so a failed load has
        // to collapse the same way a missing banner does.
        val seen = bannerVisibility { _, _ -> null }

        assertTrue("must start visible while the load is in flight", seen.first())
        assertFalse("must collapse once the load has failed", seen.last())
    }

    @Test
    fun aLoadStillRunningKeepsTheHeaderOverlapReserved() {
        assertTrue(bannerVisibility { _, _ -> awaitCancellation() }.last())
    }

    /** Every visibility the header saw, in recomposition order. */
    private fun bannerVisibility(load: suspend (String, Int) -> ImageBitmap?): List<Boolean> {
        val seen = mutableListOf<Boolean>()
        composeRule.setContent {
            WhiteNoiseTheme {
                seen += rememberProfileBannerLoadState(BANNER_URL, TARGET_WIDTH_PX, { _, _ -> null }, load).visible
            }
        }
        composeRule.waitForIdle()
        return seen
    }

    private companion object {
        const val BANNER_URL = "https://example.com/banner.png"
        const val TARGET_WIDTH_PX = 1080
    }
}
