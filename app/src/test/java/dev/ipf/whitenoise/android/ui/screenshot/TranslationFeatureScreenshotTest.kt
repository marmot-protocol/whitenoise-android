package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.translation.TranslationGlobalSettingsContent
import dev.ipf.whitenoise.android.translation.TranslationPreferences
import dev.ipf.whitenoise.android.ui.settings.SettingsList
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w400dp-h1000dp-mdpi")
class TranslationFeatureScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun offLight() = capture("translation_settings_off_light.png")

    @Test fun enabledDark() = capture("translation_settings_on_dark.png", enabled = true, dark = true)

    @Test fun largeRtl() = capture("translation_settings_large_rtl.png", enabled = true, scale = 2f, rtl = true)

    private fun capture(
        name: String,
        enabled: Boolean = false,
        dark: Boolean = false,
        scale: Float = 1f,
        rtl: Boolean = false,
    ) {
        val context: android.content.Context = ApplicationProvider.getApplicationContext()
        context
            .getSharedPreferences("translation_choices", 0)
            .edit()
            .clear()
            .commit()
        val preferences = TranslationPreferences(context)
        preferences.setGlobal(enabled, "de")
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, scale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark) {
                    Box(Modifier.size(360.dp, 650.dp).testTag("translation-fixture")) {
                        SettingsList { item { TranslationGlobalSettingsContent(preferences) } }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("translation-fixture").captureRoboImage("src/test/snapshots/$name")
        preferences.close()
    }
}
