package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.tts.TtsVoiceKey
import dev.ipf.whitenoise.android.audio.tts.TtsVoiceOption
import dev.ipf.whitenoise.android.audio.tts.TtsVoiceUnavailableReason
import dev.ipf.whitenoise.android.ui.common.SpeechChoiceDialog
import dev.ipf.whitenoise.android.ui.settings.SettingsGroup
import dev.ipf.whitenoise.android.ui.settings.SettingsLink
import dev.ipf.whitenoise.android.ui.settings.SettingsSwitch
import dev.ipf.whitenoise.android.ui.settings.speechVoiceChoice
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/** Pixel baselines for Read Aloud's preference rows and for the voice picker's available and blocked states. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class TtsVoiceMediaSettingsScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** The speech preference group: auto-read, speak-over-media and the mix volume value row. */
    @Test
    fun voiceAndMediaMixRowsLight() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Surface { SettingsGallery(360.dp) }
            }
        }
        composeRule.onNodeWithTag(GALLERY_TAG).captureRoboImage(
            "src/test/snapshots/tts_voice_media_settings_light.png",
        )
    }

    /** The same rows wrapping under RTL and 200 % text. */
    @Test
    @Config(sdk = [36], qualifiers = "w320dp-h1400dp-mdpi")
    fun voiceAndMediaMixRowsRtlLargeFont() {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides Density(density = 1f, fontScale = 2f),
            ) {
                WhiteNoiseTheme(darkTheme = false) {
                    Surface { SettingsGallery(320.dp) }
                }
            }
        }
        composeRule.onNodeWithTag(GALLERY_TAG).captureRoboImage(
            "src/test/snapshots/tts_voice_media_settings_rtl_large_font.png",
        )
    }

    /** The voice picker with the automatic entry, an offline voice and a network-only one that cannot be chosen. */
    @Test
    fun voiceChoicesLight() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                SpeechChoiceDialog(
                    title = stringResource(R.string.tts_voice_title),
                    choices =
                        listOf(
                            speechVoiceChoice(offlineVoice(), Locale.US, selected = true) {},
                            speechVoiceChoice(networkVoice(), Locale.US, selected = false) {},
                        ),
                    onDismiss = {},
                )
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/tts_voice_choices_light.png")
    }

    /** The speech preference rows as Read Aloud groups them. */
    @Composable
    private fun SettingsGallery(width: Dp) {
        Column(Modifier.width(width).testTag(GALLERY_TAG)) {
            SettingsGroup {
                row("auto_read") { context ->
                    SettingsSwitch(
                        context = context,
                        title = stringResource(R.string.tts_auto_read_default_global_title),
                        checked = true,
                        onCheckedChange = {},
                        subtitle = stringResource(R.string.tts_auto_read_default_global_subtitle),
                    )
                }
                row("media_mix") { context ->
                    SettingsSwitch(
                        context = context,
                        title = stringResource(R.string.tts_media_mix_title),
                        checked = true,
                        onCheckedChange = {},
                        subtitle = stringResource(R.string.tts_media_mix_subtitle),
                    )
                }
                row("mix_volume") { context ->
                    SettingsLink(
                        context = context,
                        title = stringResource(R.string.tts_media_mix_volume_title),
                        onClick = {},
                        value = stringResource(R.string.tts_media_mix_volume_medium),
                    )
                }
            }
        }
    }

    private fun offlineVoice(): TtsVoiceOption {
        val key = TtsVoiceKey("engine.a", "English US", "en-US")
        return TtsVoiceOption(key, "English US", "en-US", null)
    }

    private fun networkVoice() =
        TtsVoiceOption(
            TtsVoiceKey("engine.a", "Cloud voice", "en-GB"),
            "Cloud voice",
            "en-GB",
            TtsVoiceUnavailableReason.RequiresNetwork,
        )

    private companion object {
        const val GALLERY_TAG = "tts-voice-media-settings-gallery"
    }
}
