package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.translation.TranslationChatSettingsDialog
import dev.ipf.whitenoise.android.translation.TranslationGlobalSettingsContent
import dev.ipf.whitenoise.android.translation.TranslationOverride
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

    /** A saved target outside the catalog shows its recovery notice and a language prompt, not a substitute. */
    @Test
    fun unsupportedTargetLight() = capture("translation_settings_unsupported_target_light.png", savedTarget = "xx")

    /** A chat with automatic translation on and its own target: radio rows, the cleared inherit box and the picker. */
    @Test
    fun chatDialogOverrideLight() = captureChatDialog("translation_chat_dialog_override_light.png")

    /** The same chat dialog in RTL at 200 %. */
    @Test
    fun chatDialogOverrideLargeRtl() {
        captureChatDialog("translation_chat_dialog_override_large_rtl.png", scale = 2f, rtl = true, dark = true)
    }

    /** Renders one chat's translation dialog with an On override and a German target, and records the dialog. */
    private fun captureChatDialog(
        name: String,
        dark: Boolean = false,
        scale: Float = 1f,
        rtl: Boolean = false,
    ) {
        val context: android.content.Context = ApplicationProvider.getApplicationContext()
        context
            .getSharedPreferences("translation_choices", 0)
            .edit()
            .clear()
            .putString("target", "en")
            .commit()
        TranslationPreferences(context).run {
            setOverride(CHAT_ACCOUNT, CHAT_GROUP, TranslationOverride.ON)
            setTargetOverride(CHAT_ACCOUNT, CHAT_GROUP, "de")
            close()
        }
        composeRule.setContent {
            // The dialog has its own window, so the font scale goes through the theme rather than LocalDensity.
            val direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                WhiteNoiseTheme(darkTheme = dark, fontScale = scale) {
                    TranslationChatSettingsDialog(account = CHAT_ACCOUNT, group = CHAT_GROUP, onDismiss = {})
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNode(isDialog()).captureRoboImage("src/test/snapshots/$name")
    }

    /** Renders global translation settings for [savedTarget], written raw as an older or corrupt value would be. */
    @Suppress("LongParameterList")
    private fun capture(
        name: String,
        enabled: Boolean = false,
        dark: Boolean = false,
        scale: Float = 1f,
        rtl: Boolean = false,
        savedTarget: String = "de",
    ) {
        val context: android.content.Context = ApplicationProvider.getApplicationContext()
        context
            .getSharedPreferences("translation_choices", 0)
            .edit()
            .clear()
            .putString("target", savedTarget)
            .commit()
        val preferences = TranslationPreferences(context)
        preferences.setGlobal(enabled, null)
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, scale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark) {
                    Surface(
                        modifier = Modifier.size(360.dp, 650.dp).testTag("translation-fixture"),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        SettingsList { item { TranslationGlobalSettingsContent(preferences) } }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("translation-fixture").captureRoboImage("src/test/snapshots/$name")
        preferences.close()
    }

    private companion object {
        const val CHAT_ACCOUNT = "account-a"
        const val CHAT_GROUP = "group-a"
    }
}
