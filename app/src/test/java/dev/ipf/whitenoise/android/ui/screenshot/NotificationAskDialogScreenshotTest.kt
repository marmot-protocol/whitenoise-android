package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.NotificationAskDialog
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Pins the explanation shown before Android's notification permission dialog (#4278). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class NotificationAskDialogScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Light explanation with both actions. */
    @Test
    fun notificationAskLight() {
        render(darkTheme = false)
        capture("light")
    }

    /** AMOLED explanation stays legible on the pure-black surface. */
    @Test
    fun notificationAskAmoled() {
        render(darkTheme = true, amoled = true)
        capture("amoled")
    }

    /** At double font scale in RTL the body and both actions wrap without clipping. */
    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w360dp-h780dp-mdpi")
    fun notificationAskAmoledRtlLargeFont() {
        render(darkTheme = true, amoled = true, rtl = true)
        capture("amoled_rtl_large_font")
    }

    private fun render(
        darkTheme: Boolean,
        amoled: Boolean = false,
        rtl: Boolean = false,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, if (rtl) 2f else density.fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                    NotificationAskDialog(onConfirm = {}, onDismiss = {})
                }
            }
        }
    }

    private fun capture(variant: String) {
        composeRule.onNode(isDialog()).captureRoboImage("src/test/snapshots/notification_ask_$variant.png")
    }
}
