package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.BubbleSide
import dev.ipf.whitenoise.android.state.BubbleTheme
import dev.ipf.whitenoise.android.ui.settings.TonalSwatchPicker
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-mdpi")
class FullSpectrumColorPickerScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun string(resId: Int): String = context.getString(resId)

    @Test
    fun fullSpectrumPickerLight() = capture("full_spectrum_color_picker_light", dark = false, amoled = false)

    @Test
    fun fullSpectrumPickerDark() = capture("full_spectrum_color_picker_dark", dark = true, amoled = false)

    @Test
    fun fullSpectrumPickerAmoled() = capture("full_spectrum_color_picker_amoled", dark = true, amoled = true)

    private fun capture(
        name: String,
        dark: Boolean,
        amoled: Boolean,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark, amoled = amoled) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        TonalSwatchPicker(
                            selectedArgb = 0xFF7C4DFFL,
                            onColorSelected = {},
                            scopeKey = name,
                            theme =
                                if (amoled) {
                                    BubbleTheme.Amoled
                                } else if (dark) {
                                    BubbleTheme.Dark
                                } else {
                                    BubbleTheme.Light
                                },
                            slotKey = BubbleSide.Mine.name,
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithContentDescription(string(R.string.more_colors)).performClick()
        composeRule
            .onRoot()
            .captureRoboImage("src/test/snapshots/$name.png")
    }
}
