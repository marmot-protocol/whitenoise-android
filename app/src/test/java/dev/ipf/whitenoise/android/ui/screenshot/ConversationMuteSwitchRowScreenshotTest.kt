package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.group.MUTE_SWITCH_ROW_TAG
import dev.ipf.whitenoise.android.ui.group.MuteSwitchRow
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
class ConversationMuteSwitchRowScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun pendingMuteCommandDark() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                Surface {
                    Column(Modifier.width(360.dp)) {
                        MuteSwitchRow(
                            muted = true,
                            mutedUntil = "Muted until 8:30 PM",
                            enabled = false,
                            onToggle = {},
                        )
                    }
                }
            }
        }

        composeRule
            .onNodeWithTag(MUTE_SWITCH_ROW_TAG)
            .assertIsNotEnabled()
            .captureRoboImage("src/test/snapshots/conversation_mute_switch_pending_dark.png")
    }
}
