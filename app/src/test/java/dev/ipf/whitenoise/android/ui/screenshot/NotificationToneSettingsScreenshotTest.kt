package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.settings.NotificationToneControl
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowMediaPlayer

/** App-owned controls only; native sound picker geometry requires physical-device captures. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h1200dp-mdpi")
class NotificationToneSettingsScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun light() = capture("light", dark = false)

    @Test fun dark() = capture("dark", dark = true)

    @Test fun amoled() = capture("amoled", dark = true, amoled = true)

    @Test fun rtlLargeText() = capture("rtl_large", dark = true, amoled = true, rtl = true, large = true)

    @Test
    fun previewFailure() {
        val players = mutableListOf<ShadowMediaPlayer>()
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(60_000, 100) }
        ShadowMediaPlayer.setCreateListener { _, player -> players += player }
        try {
            render(dark = true, amoled = true, rtl = false, large = false)
            composeRule.onNodeWithTag("notification-tone-ding-v1").performClick()
            composeRule.runOnIdle { players.last().invokeErrorListener(1, 0) }
            composeRule
                .onNodeWithTag("tone-controls")
                .captureRoboImage("src/test/snapshots/notification_tones_preview_failure.png")
        } finally {
            ShadowMediaPlayer.resetStaticState()
        }
    }

    private fun capture(
        name: String,
        dark: Boolean,
        amoled: Boolean = false,
        rtl: Boolean = false,
        large: Boolean = false,
    ) {
        render(dark, amoled, rtl, large)
        composeRule.onNodeWithTag("tone-controls").captureRoboImage("src/test/snapshots/notification_tones_$name.png")
    }

    private fun render(
        dark: Boolean,
        amoled: Boolean,
        rtl: Boolean,
        large: Boolean,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark, amoled = amoled) {
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                    LocalDensity provides Density(LocalDensity.current.density, if (large) 2f else 1f),
                ) {
                    Surface(modifier = Modifier.width(360.dp).testTag("tone-controls")) {
                        Column { NotificationToneControl() }
                    }
                }
            }
        }
    }
}
