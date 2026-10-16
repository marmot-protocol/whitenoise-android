package dev.ipf.whitenoise.android.translation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
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
import org.junit.Assert.assertEquals
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

    /** Automatic translation stays off until switched on beside the provider warning. */
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

    /** The chat opt-in shows the same provider warning before enabling automatic translation. */
    @Test
    fun chatOptInAlsoShowsTheProviderWarning() {
        val preferences = TranslationPreferences(context)
        preferences.setGlobal(false, "en")
        preferences.setOverride("account", "group", TranslationOverride.OFF)
        composeRule.setContent {
            WhiteNoiseTheme { TranslationChatSettingsDialog("account", "group", {}) }
        }
        composeRule.onNodeWithText(context.getString(R.string.translation_privacy_warning)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.translation_on)).performClick()
        assertTrue(preferences.effective("account", "group").enabled)
        preferences.close()
    }

    /** A chat target saved elsewhere while the dialog is open replaces the shown target, and clearing it inherits. */
    @Test
    fun openChatDialogFollowsTargetChangesSavedElsewhere() {
        val preferences = TranslationPreferences(context)
        preferences.setGlobal(false, "en")
        preferences.setTargetOverride("account", "group", "fr")
        composeRule.setContent {
            WhiteNoiseTheme { TranslationChatSettingsDialog("account", "group", {}) }
        }
        composeRule.onNodeWithText("Target language: French").assertIsDisplayed()
        preferences.setTargetOverride("account", "group", "sk")
        composeRule.onNodeWithText("Target language: Slovak").assertIsDisplayed()
        preferences.setTargetOverride("account", "group", null)
        composeRule.onNodeWithText("Target language: Slovak").assertDoesNotExist()
        preferences.close()
    }

    /** With an unsupported global target, a chat's pending target choice survives a mode change until chosen. */
    @Test
    fun pendingChatTargetSurvivesAModeChangeUntilALanguageIsChosen() {
        context
            .getSharedPreferences("translation_choices", 0)
            .edit()
            .putString("target", "xx")
            .commit()
        val preferences = TranslationPreferences(context)
        composeRule.setContent {
            WhiteNoiseTheme { TranslationChatSettingsDialog("account", "group", {}) }
        }
        composeRule.onNode(isToggleable()).assertIsOn().performClick()
        composeRule.onNodeWithText("Target language: Choose a language").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.translation_on)).performClick()
        assertEquals(TranslationOverride.ON, preferences.override("account", "group"))
        composeRule.onNodeWithText("Target language: Choose a language").performClick()
        composeRule.onNodeWithText("Slovak").performScrollTo().performClick()
        composeRule.onNodeWithText("Target language: Slovak").assertIsDisplayed()
        assertEquals("sk", preferences.targetOverride("account", "group"))
        preferences.close()
    }

    /** At double font size the engine route and automatic switch remain reachable by scrolling. */
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
            .onNodeWithTag("translation.engine")
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

    /** The global target picker offers Slovak and saves the engine's own `sk` code. */
    @Test
    fun globalTargetPickerOffersSlovakAndSavesItsEngineCode() {
        val preferences = TranslationPreferences(context)
        preferences.setGlobal(true, "es")
        composeRule.setContent { WhiteNoiseTheme { TranslationGlobalSettingsContent(preferences) } }
        composeRule.onNodeWithText("Target language: Spanish").performClick()
        composeRule.onNodeWithText("Slovak").performScrollTo().performClick()
        composeRule.onNodeWithText("Target language: Slovak").assertIsDisplayed()
        assertEquals(TranslationChoice(true, "sk"), preferences.global())
        preferences.close()
    }

    /** An unsupported saved global target shows recovery, not English, until a catalog language replaces it. */
    @Test
    fun unsupportedGlobalTargetAsksForANewLanguage() {
        context
            .getSharedPreferences("translation_choices", 0)
            .edit()
            .putString("target", "xx")
            .commit()
        val preferences = TranslationPreferences(context)
        composeRule.setContent { WhiteNoiseTheme { TranslationGlobalSettingsContent(preferences) } }
        composeRule.onNodeWithTag("translation.target_recovery").assertIsDisplayed()
        composeRule.onNodeWithText("Target language: English").assertDoesNotExist()
        composeRule.onNodeWithText("Target language: Choose a language").performClick()
        composeRule.onNodeWithText("Chinese (Traditional)").performScrollTo().performClick()
        composeRule.onNodeWithTag("translation.target_recovery").assertDoesNotExist()
        assertEquals("zh_hant", preferences.global().target)
        preferences.close()
    }

    /** A chat's unsupported saved target needs recovery, and choosing Slovak saves `sk` for that chat only. */
    @Test
    fun chatTargetRecoveryReplacesAnUnsupportedOverrideWithSlovak() {
        val preferences = TranslationPreferences(context)
        preferences.setGlobal(true, "de")
        context
            .getSharedPreferences("translation_choices", 0)
            .edit()
            .putString("chat:7:account:group:target", "zh-Hant")
            .commit()
        composeRule.setContent { WhiteNoiseTheme { TranslationChatSettingsDialog("account", "group", {}) } }
        composeRule.onNodeWithTag("translation.target_recovery").assertIsDisplayed()
        composeRule.onNodeWithText("Target language: Choose a language").performClick()
        composeRule.onNodeWithText("Slovak").performScrollTo().performClick()
        composeRule.onNodeWithTag("translation.target_recovery").assertDoesNotExist()
        assertEquals(TranslationChoice(true, "sk"), preferences.effective("account", "group"))
        assertEquals(TranslationChoice(true, "de"), preferences.effective("account", "other"))
        preferences.close()
    }

    /** Target pickers omit into-English-only languages and label Chinese and Norwegian variants distinctly. */
    @Test
    fun targetPickerListsOnlyTargetsWithDistinctVariantLabels() {
        val preferences = TranslationPreferences(context)
        preferences.setGlobal(false, "en")
        composeRule.setContent { WhiteNoiseTheme { TranslationGlobalSettingsContent(preferences) } }
        composeRule.onNodeWithText("Target language: English").performClick()
        for (label in listOf("Chinese (Simplified)", "Chinese (Traditional)", "Norwegian", "Norwegian Bokmål")) {
            composeRule.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        }
        for (sourceOnly in listOf("Belarusian", "Marathi", "Norwegian Nynorsk", "Urdu")) {
            composeRule.onNodeWithText(sourceOnly).assertDoesNotExist()
        }
        preferences.close()
    }
}
