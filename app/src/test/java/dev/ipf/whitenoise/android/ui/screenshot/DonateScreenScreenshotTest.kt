package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.settings.DonateScreen
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Donate with Lightning selected: selector, heart and pitch, QR, copy capsule and caption. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h900dp-mdpi")
class DonateScreenScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Light theme. */
    @Test
    fun donateLight() = capture("donate_light", darkTheme = false)

    /** Dark theme. */
    @Test
    fun donateDark() = capture("donate_dark", darkTheme = true)

    /** AMOLED: black canvas with outlined QR surface and capsule. */
    @Test
    fun donateAmoled() = capture("donate_amoled", darkTheme = true, amoled = true)

    /** Renders the screen and records the window. */
    private fun capture(
        name: String,
        darkTheme: Boolean,
        amoled: Boolean = false,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) { DonateScreen(onBack = {}) }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }
}
