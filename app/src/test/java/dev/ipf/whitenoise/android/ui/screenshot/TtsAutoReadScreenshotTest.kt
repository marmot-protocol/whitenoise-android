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
import dev.ipf.whitenoise.android.ui.group.TtsAutoReadGroupActionRow
import dev.ipf.whitenoise.android.ui.group.TtsAutoReadPickerContent
import dev.ipf.whitenoise.android.ui.settings.SettingsGroup
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The per-chat Read Aloud row inside its group and the override picker, including RTL at large type. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class TtsAutoReadScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    /** Resolves a string resource in the test context. */
    private fun string(resId: Int): String = app.getString(resId)

    /** Three picker states side by side: default, explicit on, explicit off. */
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

    /** The group row and the picker at 200 % type in RTL: title and provenance stay visible. */
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
                            SettingsGroup {
                                row("auto_read") { rowContext ->
                                    TtsAutoReadGroupActionRow(
                                        context = rowContext,
                                        title = title,
                                        provenanceLabel = provenance,
                                        onClick = {},
                                    )
                                }
                            }
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
