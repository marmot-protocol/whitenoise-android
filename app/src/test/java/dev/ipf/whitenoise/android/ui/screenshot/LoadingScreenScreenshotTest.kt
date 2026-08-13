package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
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
