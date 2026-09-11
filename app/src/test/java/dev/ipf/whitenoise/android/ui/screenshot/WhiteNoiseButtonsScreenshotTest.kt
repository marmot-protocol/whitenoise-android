package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseFilledTonalButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseOutlinedButton
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The three task buttons at rest, disabled and loading, in every theme. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h640dp-mdpi")
class WhiteNoiseButtonsScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Light theme. */
    @Test
    fun buttonsLight() {
        render(darkTheme = false)
        capture("light")
    }

    /** Dark theme. */
    @Test
    fun buttonsDark() {
        render(darkTheme = true)
        capture("dark")
    }

    /** AMOLED: filled buttons become outlined surfaces. */
    @Test
    fun buttonsAmoled() {
        render(darkTheme = true, amoled = true)
        capture("amoled")
    }

    private fun render(
        darkTheme: Boolean,
        amoled: Boolean = false,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                Surface(modifier = Modifier.testTag(TAG)) { ButtonSamples() }
            }
        }
    }

    /** One column of every variant the shared buttons expose. */
    @Composable
    private fun ButtonSamples() {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            WhiteNoiseButton(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
            WhiteNoiseButton(onClick = {}, modifier = Modifier.fillMaxWidth(), enabled = false) { Text("Continue") }
            WhiteNoiseButton(
                onClick = {},
                modifier = Modifier.fillMaxWidth(),
                loading = true,
                loadingLabel = "Signing in",
            ) { Text("Continue") }
            WhiteNoiseOutlinedButton(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Scan another") }
            WhiteNoiseFilledTonalButton(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Restore defaults") }
        }
    }

    private fun capture(variant: String) {
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/white_noise_buttons_$variant.png")
    }

    private companion object {
        const val TAG = "buttons.samples"
    }
}
