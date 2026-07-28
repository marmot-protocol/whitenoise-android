package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.common.AccountActionColors
import dev.ipf.whitenoise.android.ui.settings.ActionColorPreview
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
class ActionColorScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun actionColorLight() = capture(darkTheme = false, amoled = false, name = "action_color_light.png")

    @Test
    fun actionColorDark() = capture(darkTheme = true, amoled = false, name = "action_color_dark.png")

    @Test
    fun actionColorAmoled() = capture(darkTheme = true, amoled = true, name = "action_color_amoled.png")

    private fun capture(
        darkTheme: Boolean,
        amoled: Boolean,
        name: String,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                Surface {
                    ActionColorPreview(
                        colors =
                            AccountActionColors(
                                container = Color(0xFFFFC107),
                                content = Color.Black,
                            ),
                        modifier = Modifier.padding(16.dp).testTag(TAG),
                    )
                }
            }
        }
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/$name")
    }

    private companion object {
        const val TAG = "action-color-preview"
    }
}
