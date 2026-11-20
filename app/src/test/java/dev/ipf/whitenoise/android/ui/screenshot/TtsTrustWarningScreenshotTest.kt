package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.audio.tts.TtsTrustWarningDialog
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Captures the warning shown before sending private text to an unverified engine. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class TtsTrustWarningScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun readAloudTrustWarningAmoled() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true, fontScale = 1f) {
                TtsTrustWarningDialog(onProceed = {}, onDismiss = {})
            }
        }
        composeRule.onNode(isDialog()).captureRoboImage("src/test/snapshots/tts_trust_warning_amoled.png")
    }
}
