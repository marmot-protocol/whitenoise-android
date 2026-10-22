package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.state.AppFontScale
import dev.ipf.whitenoise.android.state.BubbleTheme
import dev.ipf.whitenoise.android.ui.settings.FontSizeDialog
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h1200dp-mdpi")
class FontSizeDialogScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun smallLight() = capture(AppFontScale.Small, BubbleTheme.Light)

    @Test fun defaultDark() = capture(AppFontScale.Default, BubbleTheme.Dark)

    @Test fun largeAmoled() = capture(AppFontScale.Large, BubbleTheme.Amoled)

    @Test fun extraLargeRtl() = capture(AppFontScale.ExtraLarge, BubbleTheme.Amoled, rtl = true, systemScale = 1.3f)

    private fun capture(
        scale: AppFontScale,
        theme: BubbleTheme,
        rtl: Boolean = false,
        systemScale: Float = 1f,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, systemScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = theme != BubbleTheme.Light, amoled = theme == BubbleTheme.Amoled, fontScale = scale.factor) {
                    FontSizeDialog(selected = scale, onSelect = {}, onDismiss = {}, theme = theme, mineArgb = null, otherArgb = null)
                }
            }
        }
        composeRule.onNodeWithTag("font_size.preview").assertExists()
        composeRule.onNodeWithTag("font_size.dialog").captureRoboImage("src/test/snapshots/font_size_${scale.name}_${theme.name}.png")
    }
}
