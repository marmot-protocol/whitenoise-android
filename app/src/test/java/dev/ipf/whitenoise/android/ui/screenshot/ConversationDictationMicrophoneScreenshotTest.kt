package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.conversation.composer.ConversationDictationMicrophoneDialog
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
class ConversationDictationMicrophoneScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun microphonePrivacyLight() = capture("dictation_microphone_privacy_light.png")

    @Test
    fun microphonePrivacyDark() = capture("dictation_microphone_privacy_dark.png", dark = true)

    @Test
    fun microphonePrivacyLargeFontRtl() = capture("dictation_microphone_privacy_large_rtl.png", largeRtl = true)

    private fun capture(
        name: String,
        dark: Boolean = false,
        largeRtl: Boolean = false,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, if (largeRtl) 2f else 1f),
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, fontScale = if (largeRtl) 2f else 1f) {
                    ConversationDictationMicrophoneDialog(onDismiss = {}, onOpenSettings = {})
                }
            }
        }
        composeRule.onNodeWithTag("dictation-microphone-dialog").captureRoboImage("src/test/snapshots/$name")
    }
}
