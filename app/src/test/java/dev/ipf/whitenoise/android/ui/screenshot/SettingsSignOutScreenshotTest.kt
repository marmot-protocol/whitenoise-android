package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.settings.SignOutProgressDialog
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The blocking teardown surface is visible in both ordinary and monochrome themes. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class SettingsSignOutScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** The light-theme progress indicator remains readable while it owns interaction. */
    @Test
    fun signOutProgressLight() = capture(amoled = false, name = "settings_sign_out_progress_light")

    /** AMOLED uses the same monochrome action roles as the rest of Settings. */
    @Test
    fun signOutProgressAmoled() = capture(amoled = true, name = "settings_sign_out_progress_amoled")

    private fun capture(
        amoled: Boolean,
        name: String,
    ) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = amoled, amoled = amoled) { SignOutProgressDialog() }
        }
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.onNodeWithTag("settings.sign_out_progress").captureRoboImage("src/test/snapshots/$name.png")
    }
}
