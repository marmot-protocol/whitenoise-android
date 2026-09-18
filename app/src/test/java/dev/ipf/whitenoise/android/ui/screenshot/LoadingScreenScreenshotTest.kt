package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.FULL_SCREEN_LOADING_MESSAGE_TEST_TAG
import dev.ipf.whitenoise.android.ui.common.LoadingScreen
import dev.ipf.whitenoise.android.ui.common.StartupLoadingScreen
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class LoadingScreenScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun brandedStartupLight() = capture("loading_screen_light.png", darkTheme = false)

    @Test
    fun brandedStartupDark() = capture("loading_screen_dark.png", darkTheme = true)

    /** The chat list's slow-start state: the neutral indicator with the still-finishing copy under it. */
    @Test
    fun slowStartLoadingLight() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LoadingScreen(message = stringResource(R.string.chat_list_startup_slow))
                }
            }
        }
        composeRule.onNodeWithTag(FULL_SCREEN_LOADING_MESSAGE_TEST_TAG).assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/loading_screen_slow_start_light.png")
    }

    private fun capture(
        fileName: String,
        darkTheme: Boolean,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    StartupLoadingScreen()
                }
            }
        }

        composeRule.onRoot().captureRoboImage("src/test/snapshots/$fileName")
    }
}
