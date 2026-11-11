package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Retry offered when a global-search result's window could not load (#1945), across themes and RTL. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class SearchTargetRetryChipScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    /** Light theme. */
    @Test fun light() = capture("light")

    /** Dark theme. */
    @Test fun dark() = capture("dark", dark = true)

    /** AMOLED theme. */
    @Test fun amoled() = capture("amoled", dark = true, amoled = true)

    /** Right-to-left at double font scale on a narrow screen. */
    @Test
    @Config(sdk = [36], qualifiers = "w320dp-h780dp-mdpi")
    fun largeRtl() = capture("large_rtl", largeRtl = true)

    /** Tapping the chip retries, and it meets the minimum touch height. */
    @Test
    fun tapRetriesAndMeetsTheTouchTarget() {
        var retries = 0
        composeRule.setContent { WhiteNoiseTheme { SearchTargetRetryChip(onRetry = { retries++ }) } }

        composeRule.onNodeWithTag(SEARCH_TARGET_RETRY_CHIP_TAG).assertHeightIsAtLeast(48.dp).performClick()
        composeRule.runOnIdle { assertEquals(1, retries) }
    }

    /** Renders the chip where the conversation's navigation controls sit and records the golden. */
    private fun capture(
        name: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        largeRtl: Boolean = false,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, if (largeRtl) 2f else 1f),
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled) {
                    Surface(Modifier.width(if (largeRtl) 320.dp else 360.dp).testTag("retry-host")) {
                        Box(Modifier.padding(16.dp), contentAlignment = Alignment.BottomEnd) {
                            SearchTargetRetryChip(onRetry = {})
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("retry-host").captureRoboImage("src/test/snapshots/search_target_retry_$name.png")
    }
}
