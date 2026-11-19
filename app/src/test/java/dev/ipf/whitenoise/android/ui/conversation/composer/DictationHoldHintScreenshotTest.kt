package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The held dictation hint: resting lock, armed lock after sliding up, and delete once slide-left arms cancel. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class DictationHoldHintScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun holdHintLight() = capture("composer_dictation_hold_hint_light", dark = false)

    @Test fun holdHintAmoled() = capture("composer_dictation_hold_hint_amoled", dark = true)

    private fun capture(
        name: String,
        dark: Boolean,
    ) {
        val states =
            listOf(false to false, true to false, false to true).map { (lock, cancel) ->
                DictationHoldState().apply {
                    begin(Rect.Zero)
                    track(0f, willLock = lock, willCancel = cancel)
                }
            }
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark, amoled = dark) {
                Surface {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        states.forEach { state -> Box(Modifier.size(36.dp)) { DictationHoldHint(state) } }
                    }
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }
}
