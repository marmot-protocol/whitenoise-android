package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.settings.SettingsRow
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pixel baseline for the settings row leaf, pinned so relocation of the
 * composable across packages provably changes nothing on screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class SettingsRowScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun settingsRowLight() {
        render(darkTheme = false)
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/settings_row_light.png")
    }

    @Test
    fun settingsRowDark() {
        render(darkTheme = true)
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/settings_row_dark.png")
    }

    private fun render(darkTheme: Boolean) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme) {
                Surface {
                    Column(modifier = Modifier.width(360.dp).testTag(TAG)) {
                        SettingsRow(
                            title = "Notifications",
                            subtitle = "Sounds, banners, previews",
                            onClick = {},
                        )
                        SettingsRow(
                            title = "Appearance",
                            subtitle = "Theme, font size, language",
                            onClick = {},
                        )
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "settings-rows"
    }
}
