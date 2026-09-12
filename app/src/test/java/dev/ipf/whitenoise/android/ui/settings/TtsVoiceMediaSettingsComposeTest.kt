package dev.ipf.whitenoise.android.ui.settings

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.tts.TtsVoiceKey
import dev.ipf.whitenoise.android.audio.tts.TtsVoiceOption
import dev.ipf.whitenoise.android.audio.tts.TtsVoiceUnavailableReason
import dev.ipf.whitenoise.android.ui.common.SpeechChoiceDialog
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/** Read Aloud's speech preferences: the media-mix switch and the voice picker's availability semantics. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en")
class TtsVoiceMediaSettingsComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    /** The switch states the active-media constraint beside its title, and the row toggles. */
    @Test
    fun mediaMixSwitchExplainsItsConstraintAndToggles() {
        var changed: Boolean? = null
        composeRule.setContent {
            WhiteNoiseTheme {
                SettingsGroup {
                    row("media_mix") { context ->
                        SettingsSwitch(
                            context = context,
                            title = app.getString(R.string.tts_media_mix_title),
                            checked = false,
                            onCheckedChange = { changed = it },
                            subtitle = app.getString(R.string.tts_media_mix_subtitle),
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithText(app.getString(R.string.tts_media_mix_subtitle)).assertExists()
        composeRule.onNodeWithText(app.getString(R.string.tts_media_mix_title)).assertIsOff().performClick()
        composeRule.runOnIdle { assertEquals(true, changed) }
    }

    /** The persisted enabled state reaches switch semantics. */
    @Test
    fun mediaMixSwitchReportsTheEnabledState() {
        composeRule.setContent {
            WhiteNoiseTheme {
                SettingsGroup {
                    row("media_mix") { context ->
                        SettingsSwitch(
                            context = context,
                            title = app.getString(R.string.tts_media_mix_title),
                            checked = true,
                            onCheckedChange = {},
                            subtitle = app.getString(R.string.tts_media_mix_subtitle),
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithText(app.getString(R.string.tts_media_mix_title)).assertIsOn()
    }

    /** A network-only voice announces its locale and reason and stays unselectable. */
    @Test
    fun unavailableVoiceNamesLocaleAndReasonAndCannotBeActivated() {
        var clicked = false
        val voice =
            TtsVoiceOption(
                key = TtsVoiceKey("engine.a", "Cloud voice", "en-GB"),
                label = "Cloud voice",
                localeTag = "en-GB",
                unavailableReason = TtsVoiceUnavailableReason.RequiresNetwork,
            )
        composeRule.setContent {
            WhiteNoiseTheme {
                SpeechChoiceDialog(
                    title = app.getString(R.string.tts_voice_title),
                    choices = listOf(speechVoiceChoice(voice, Locale.US, selected = false) { clicked = true }),
                    onDismiss = {},
                )
            }
        }
        val node = composeRule.onNodeWithText("Cloud voice")
        node.assertIsNotEnabled()
        val description =
            node
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.ContentDescription)
                ?.joinToString()
        assertEquals(
            "Cloud voice. English (United Kingdom). " + app.getString(R.string.tts_voice_requires_network),
            description,
        )
        node.performClick()
        composeRule.runOnIdle { assertFalse(clicked) }
    }
}
