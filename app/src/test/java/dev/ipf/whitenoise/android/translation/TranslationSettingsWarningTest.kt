package dev.ipf.whitenoise.android.translation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.settings.SettingsList
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w400dp-h1000dp-mdpi")
class TranslationSettingsWarningTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun automaticTranslationRequiresANewChoiceBesideTheWarning() {
        val preferences = TranslationPreferences(context)
        preferences.setGlobal(false, "es")
        composeRule.setContent { WhiteNoiseTheme { TranslationGlobalSettingsContent(preferences) } }
        composeRule.onNodeWithText(context.getString(R.string.translation_privacy_warning)).assertIsDisplayed()
        composeRule
            .onNodeWithTag("translation.automatic")
            .assertIsOff()
            .performClick()
            .assertIsOn()
        assertTrue(preferences.global().enabled)
        preferences.close()
    }

    @Test
    fun chatOptInAlsoShowsTheProviderWarning() {
        val preferences = TranslationPreferences(context)
        preferences.setGlobal(false, "en")
        preferences.setOverride("account", "group", TranslationOverride.OFF, null)
        composeRule.setContent {
            WhiteNoiseTheme { TranslationChatSettingsDialog("account", "group", {}) }
        }
        composeRule.onNodeWithText(context.getString(R.string.translation_privacy_warning)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.translation_on)).performClick()
        assertTrue(preferences.effective("account", "group").enabled)
        preferences.close()
    }

    @Test
    fun largeTextSettingsCanScrollToTheEngineAndAutomaticControls() {
        val preferences = TranslationPreferences(context)
        preferences.setGlobal(false, "es")
        composeRule.setContent {
            WhiteNoiseTheme(fontScale = 2f) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.size(360.dp, 650.dp)) {
                        SettingsList { item { TranslationGlobalSettingsContent(preferences) } }
                    }
                }
            }
        }
        composeRule
            .onNodeWithText(context.getString(R.string.translation_open_engine))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNodeWithTag("translation.automatic")
            .performScrollTo()
            .assertIsOff()
            .performClick()
            .assertIsOn()
        assertTrue(preferences.global().enabled)
        preferences.close()
    }
}
