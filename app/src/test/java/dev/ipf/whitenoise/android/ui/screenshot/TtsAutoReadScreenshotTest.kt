package dev.ipf.whitenoise.android.ui.screenshot

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
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
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.TtsAutoReadOverride
import dev.ipf.whitenoise.android.ui.group.TTS_AUTO_READ_GLOBAL_DEFAULT_ROW_TAG
import dev.ipf.whitenoise.android.ui.group.TtsAutoReadGlobalDefaultRow
import dev.ipf.whitenoise.android.ui.group.TtsAutoReadGroupActionRow
import dev.ipf.whitenoise.android.ui.group.TtsAutoReadPickerContent
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pixel baselines for TTS auto-read settings: global default switch, per-chat
 * picker selections (inherit / on / off), and group-details row layout under RTL
 * and large font.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class TtsAutoReadScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun string(resId: Int): String = app.getString(resId)

    @Test
    fun globalDefaultOffLight() {
        renderGlobalDefault(checked = false, darkTheme = false)
        captureTag(TTS_AUTO_READ_GLOBAL_DEFAULT_ROW_TAG, "tts_auto_read_global_default_off_light")
    }

    @Test
    fun globalDefaultOnLight() {
        renderGlobalDefault(checked = true, darkTheme = false)
        captureTag(TTS_AUTO_READ_GLOBAL_DEFAULT_ROW_TAG, "tts_auto_read_global_default_on_light")
    }

    @Test
    fun pickerSelectionGalleryLight() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Surface {
                    Column(Modifier.width(360.dp).testTag(PICKER_GALLERY_TAG)) {
                        TtsAutoReadPickerContent(
                            globalDefaultEnabled = false,
                            selectedOverride = null,
                            onSelect = {},
                        )
                        TtsAutoReadPickerContent(
                            globalDefaultEnabled = false,
                            selectedOverride = TtsAutoReadOverride.ON,
                            onSelect = {},
                        )
                        TtsAutoReadPickerContent(
                            globalDefaultEnabled = true,
                            selectedOverride = TtsAutoReadOverride.OFF,
                            onSelect = {},
                        )
                    }
                }
            }
        }
        captureTag(PICKER_GALLERY_TAG, "tts_auto_read_picker_selection_gallery_light")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w320dp-h780dp-mdpi")
    fun groupRowAndPickerRtlLargeFontLight() {
        val title = string(R.string.tts_auto_read_title)
        val provenance = string(R.string.tts_auto_read_override_on)
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides Density(density = 1f, fontScale = 2f),
            ) {
                WhiteNoiseTheme(darkTheme = false) {
                    Surface {
                        Column(Modifier.width(320.dp).testTag(GROUP_ACCESSIBILITY_TAG)) {
                            TtsAutoReadGroupActionRow(
                                title = title,
                                provenanceLabel = provenance,
                                onClick = {},
                            )
                            TtsAutoReadPickerContent(
                                globalDefaultEnabled = false,
                                selectedOverride = TtsAutoReadOverride.ON,
                                onSelect = {},
                            )
                        }
                    }
                }
            }
        }
        captureTag(GROUP_ACCESSIBILITY_TAG, "tts_auto_read_group_row_picker_rtl_large_font_light")
    }

    private fun renderGlobalDefault(
        checked: Boolean,
        darkTheme: Boolean,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme) {
                Surface(Modifier.width(360.dp)) {
                    TtsAutoReadGlobalDefaultRow(
                        checked = checked,
                        onCheckedChange = {},
                    )
                }
            }
        }
    }

    private fun captureTag(
        tag: String,
        name: String,
    ) {
        composeRule.onNodeWithTag(tag).captureRoboImage("src/test/snapshots/$name.png")
    }

    private companion object {
        const val PICKER_GALLERY_TAG = "tts-auto-read-picker-gallery"
        const val GROUP_ACCESSIBILITY_TAG = "tts-auto-read-group-accessibility"
    }
}
