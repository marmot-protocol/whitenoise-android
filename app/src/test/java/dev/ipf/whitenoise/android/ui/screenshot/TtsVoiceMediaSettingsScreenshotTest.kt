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
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.audio.tts.TtsVoiceKey
import dev.ipf.whitenoise.android.audio.tts.TtsVoiceOption
import dev.ipf.whitenoise.android.audio.tts.TtsVoiceUnavailableReason
import dev.ipf.whitenoise.android.ui.group.TtsAutoReadGlobalDefaultRow
import dev.ipf.whitenoise.android.ui.settings.SelectableSettingsRowWithSubtitle
import dev.ipf.whitenoise.android.ui.settings.ttsMediaMixToggleRow
import dev.ipf.whitenoise.android.ui.settings.ttsVoicePickerRow
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/** Pixel baseline for the voice and speech-over-media preference rows. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class TtsVoiceMediaSettingsScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Captures enabled mixing plus available and network-only voice rows. */
    @Test
    fun voiceAndMediaMixRowsLight() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Surface {
                    SettingsGallery(360.dp)
                }
            }
        }

        composeRule.onNodeWithTag(GALLERY_TAG).captureRoboImage(
            "src/test/snapshots/tts_voice_media_settings_light.png",
        )
    }

    /** Captures wrapping and trailing controls under RTL and 200% text. */
    @Test
    @Config(sdk = [36], qualifiers = "w320dp-h1400dp-mdpi")
    fun voiceAndMediaMixRowsRtlLargeFont() {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides Density(density = 1f, fontScale = 2f),
            ) {
                WhiteNoiseTheme(darkTheme = false) {
                    Surface {
                        SettingsGallery(320.dp, accessibilityCasesOnly = true)
                    }
                }
            }
        }

        composeRule.onNodeWithTag(GALLERY_TAG).captureRoboImage(
            "src/test/snapshots/tts_voice_media_settings_rtl_large_font.png",
        )
    }

    /** Shared gallery containing each new enabled and disabled row state. */
    @Composable
    private fun SettingsGallery(
        width: Dp,
        accessibilityCasesOnly: Boolean = false,
    ) {
        Column(Modifier.width(width).testTag(GALLERY_TAG)) {
            TtsAutoReadGlobalDefaultRow(checked = true, onCheckedChange = {})
            ttsMediaMixToggleRow(checked = true, onCheckedChange = {})
            if (!accessibilityCasesOnly) {
                SelectableSettingsRowWithSubtitle(
                    title = "Medium",
                    subtitle = "60 percent of the text-to-speech engine volume",
                    selected = true,
                    onClick = {},
                )
                ttsVoicePickerRow(
                    voice =
                        TtsVoiceOption(
                            TtsVoiceKey("engine.a", "English US", "en-US"),
                            "English US",
                            "en-US",
                            null,
                        ),
                    displayLocale = Locale.US,
                    selected = true,
                    onClick = {},
                )
            }
            ttsVoicePickerRow(
                voice =
                    TtsVoiceOption(
                        TtsVoiceKey("engine.a", "Cloud voice", "en-GB"),
                        "Cloud voice",
                        "en-GB",
                        TtsVoiceUnavailableReason.RequiresNetwork,
                    ),
                displayLocale = Locale.US,
                selected = false,
                onClick = {},
            )
        }
    }

    private companion object {
        const val GALLERY_TAG = "tts-voice-media-settings-gallery"
    }
}
