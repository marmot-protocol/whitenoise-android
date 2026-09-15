package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.settings.FullSpectrumColorPicker
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The colour editor's controls with a custom violet selected: presets, three sliders and the hex field. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-mdpi")
class FullSpectrumColorPickerScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Light theme controls. */
    @Test
    fun fullSpectrumPickerLight() = capture("full_spectrum_color_picker_light", darkTheme = false)

    /** Dark theme controls. */
    @Test
    fun fullSpectrumPickerDark() = capture("full_spectrum_color_picker_dark", darkTheme = true)

    /** Renders the picker on a full-size surface and records the window. */
    private fun capture(
        name: String,
        darkTheme: Boolean,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        FullSpectrumColorPicker(
                            selectedArgb = 0xFF7C4DFFL,
                            fallbackArgb = 0xFF000000L,
                            onColorSelected = {},
                        )
                    }
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }
}
