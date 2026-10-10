package dev.ipf.whitenoise.android.ui.screenshot

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.conversation.TtsTransportRatePicker
import dev.ipf.whitenoise.android.ui.settings.ttsRateLabel
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual popup pixels complement the device first-open/reopen regression. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class TtsTransportRatePopupScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun speechRatePopupLight() = capture("tts_transport_rate_popup_light", dark = false)

    @Test
    fun speechRatePopupDark() = capture("tts_transport_rate_popup_dark", dark = true)

    @Test
    fun speechRatePopupLargeRtl() = capture("tts_transport_rate_popup_large_rtl", dark = true, largeRtl = true)

    private fun capture(
        name: String,
        dark: Boolean,
        largeRtl: Boolean = false,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, fontScale = if (largeRtl) 2f else 1f) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        TtsTransportRatePicker(rateOverride = 1f, activeRate = 1f, onRateSelected = {})
                    }
                }
            }
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val description = context.getString(R.string.tts_bar_rate_control, ttsRateLabel(1f, Locale.US))
        composeRule.onNodeWithContentDescription(description).performClick()
        composeRule.onNodeWithText(context.getString(R.string.tts_settings_rate_system)).assertIsDisplayed()
        composeRule.onNode(isPopup()).captureRoboImage("src/test/snapshots/$name.png")
    }
}
