package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** On AMOLED a disabled Send disc dims its outline to the same grey as its arrow, not only the icon. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ComposerActionDiscScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun amoledDisabledSendDimsOutlineWithIcon() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                Surface {
                    Row(Modifier.testTag("send-discs")) {
                        listOf(true, false).forEach { enabled ->
                            ComposerActionDisc(
                                onClick = {},
                                containerColor = Color.Black,
                                contentColor = Color.White,
                                description = "Send",
                                icon = R.drawable.ic_arrow_upward,
                                enabled = enabled,
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule
            .onNodeWithTag("send-discs")
            .captureRoboImage("src/test/snapshots/composer_action_disc_amoled_enabled_disabled.png")
    }
}
