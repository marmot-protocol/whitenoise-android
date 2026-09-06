package dev.ipf.whitenoise.android.ui.settings

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.tts.TtsVoiceKey
import dev.ipf.whitenoise.android.audio.tts.TtsVoiceOption
import dev.ipf.whitenoise.android.audio.tts.TtsVoiceUnavailableReason
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en")
class TtsVoiceMediaSettingsComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    /** Ensures TalkBack announces the active-media constraint on the switch. */
    @Test
    fun mediaMixSwitchExplainsItsConstraintAndToggles() {
        var changed: Boolean? = null
        val description =
            "${app.getString(R.string.tts_media_mix_title)}. " +
                app.getString(R.string.tts_media_mix_subtitle)
        composeRule.setContent {
            WhiteNoiseTheme {
                ttsMediaMixToggleRow(checked = false, onCheckedChange = { changed = it })
            }
        }

        composeRule.onNodeWithContentDescription(description).assertIsOff().performClick()
        composeRule.runOnIdle { assertEquals(true, changed) }
    }

    /** Exposes the persisted enabled state through switch semantics. */
    @Test
    fun mediaMixSwitchReportsTheEnabledState() {
        val description =
            "${app.getString(R.string.tts_media_mix_title)}. " +
                app.getString(R.string.tts_media_mix_subtitle)
        composeRule.setContent {
            WhiteNoiseTheme {
                ttsMediaMixToggleRow(checked = true, onCheckedChange = {})
            }
        }

        composeRule.onNodeWithContentDescription(description).assertIsOn()
    }

    /** Announces a voice's locale and reason while keeping it unselectable. */
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
                ttsVoicePickerRow(voice, Locale.US, selected = false, onClick = { clicked = true })
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
