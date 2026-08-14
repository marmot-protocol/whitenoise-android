package dev.ipf.whitenoise.android.ui.group

import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Visual regression coverage for the descriptive Group Info edit title. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h100dp-mdpi")
class GroupEditTopBarScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun descriptiveTitleLight() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Surface {
                    GroupEditTopBar(onBack = {})
                }
            }
        }

        composeRule.onRoot().captureRoboImage("src/test/snapshots/group_edit_top_bar_light.png")
    }
}
