package dev.ipf.whitenoise.android.ui.screenshot

import android.content.ComponentName
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.audio.ConversationDictationCallerAudioRequirement
import dev.ipf.whitenoise.android.audio.ConversationDictationProvider
import dev.ipf.whitenoise.android.audio.ConversationDictationProviderChoice
import dev.ipf.whitenoise.android.ui.settings.DictationProviderSheet
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class DictationProviderScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun providerChoicesHaveTruthfulLabelsAndSelectionSemantics() {
        var selected: ConversationDictationProviderChoice? = null
        val choice = choice()
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                DictationProviderSheet(
                    listOf(ConversationDictationProvider("example", "Example", listOf(choice))),
                    choice,
                    { selected = it },
                    {},
                )
            }
        }
        composeRule.onNodeWithTag("dictation_provider_choices").performScrollToNode(hasText("Engine"))
        composeRule.onNodeWithText("Engine").assertIsSelected().performClick()
        assertEquals(choice, selected)
        composeRule.onNodeWithText("In-app support not verified").assertIsDisplayed()
        composeRule.onNodeWithText("Works inside White Noise").assertDoesNotExist()
        composeRule
            .onNodeWithTag("dictation_provider_choices")
            .captureRoboImage("src/test/snapshots/dictation_provider_unknown_light.png")
    }

    @Test
    fun providerWindowAt200PercentFontAndRtlWrapsAndScrolls() {
        val choice = choice().copy(activity = ComponentName("example", "example.Window"))
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 2f),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                WhiteNoiseTheme(darkTheme = true) {
                    DictationProviderSheet(
                        listOf(ConversationDictationProvider("example", "Example", listOf(choice))),
                        null,
                        {},
                        {},
                    )
                }
            }
        }
        composeRule.onNodeWithTag("dictation_provider_choices").performScrollToNode(hasText("Opens provider window"))
        composeRule.onNodeWithText("Opens provider window").assertIsDisplayed()
        composeRule
            .onNodeWithTag("dictation_provider_choices")
            .captureRoboImage("src/test/snapshots/dictation_provider_window_large_rtl_dark.png")
    }

    @Test
    fun emptyProviderListShowsSetupWithoutCompatibilityClaim() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) { DictationProviderSheet(emptyList(), null, {}, {}) }
        }
        composeRule
            .onNodeWithTag("dictation_provider_choices")
            .performScrollToNode(hasText("No speech provider found.", substring = true))
        composeRule.onNodeWithText("No speech provider found.", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Works inside White Noise").assertDoesNotExist()
        composeRule
            .onNodeWithTag("dictation_provider_choices")
            .captureRoboImage("src/test/snapshots/dictation_provider_empty_light.png")
    }

    @Test
    fun loadingProviderListDoesNotPrematurelyClaimNoProvider() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) { DictationProviderSheet(null, null, {}, {}) }
        }
        composeRule
            .onNodeWithTag("dictation_provider_choices")
            .performScrollToNode(hasText("Finding speech providers…"))
        composeRule.onNodeWithText("Finding speech providers…").assertIsDisplayed()
        composeRule.onNodeWithText("No speech provider found.", substring = true).assertDoesNotExist()
        composeRule
            .onNodeWithTag("dictation_provider_choices")
            .captureRoboImage("src/test/snapshots/dictation_provider_loading_light.png")
    }

    @Test
    fun verifiedCallerAudioServiceIsDistinct() {
        val supported = choice().copy(callerAudio = ConversationDictationCallerAudioRequirement.Supported)
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                DictationProviderSheet(
                    listOf(ConversationDictationProvider("example", "Example", listOf(supported))),
                    null,
                    {},
                    {},
                )
            }
        }
        composeRule.onNodeWithTag("dictation_provider_choices").performScrollToNode(hasText("Works inside White Noise"))
        composeRule.onNodeWithText("Works inside White Noise").assertIsDisplayed()
        composeRule
            .onNodeWithTag("dictation_provider_choices")
            .captureRoboImage("src/test/snapshots/dictation_provider_verified_light.png")
    }

    @Test
    fun keyboardOnlyProviderIsDistinct() {
        val keyboard =
            ConversationDictationProviderChoice(
                "keyboard",
                1,
                "Keyboard",
                "Voice typing",
                keyboard = ComponentName("keyboard", "keyboard.Ime"),
            )
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                DictationProviderSheet(
                    listOf(ConversationDictationProvider("keyboard", "Keyboard", listOf(keyboard))),
                    null,
                    {},
                    {},
                )
            }
        }
        composeRule.onNodeWithTag("dictation_provider_choices").performScrollToNode(hasText("Keyboard only"))
        composeRule.onNodeWithText("Keyboard only").assertIsDisplayed()
        composeRule
            .onNodeWithTag("dictation_provider_choices")
            .captureRoboImage("src/test/snapshots/dictation_provider_capabilities_light.png")
    }

    private fun choice() =
        ConversationDictationProviderChoice(
            "example",
            1,
            "Example",
            "Engine",
            service = ComponentName("example", "example.Engine"),
        )
}
