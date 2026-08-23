package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.swipe
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.BubbleSide
import dev.ipf.whitenoise.android.state.BubbleTheme
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FullSpectrumColorPickerTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun string(resId: Int): String = context.getString(resId)

    @Test
    fun hueSemanticsUpdatesColorWithoutTouch() {
        var latest = 0xFFFF0000L
        setPickerContent(initialArgb = latest) { latest = it }

        composeRule
            .onNodeWithContentDescription(string(R.string.color_picker_hue))
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                assertTrue(setProgress(120f))
            }

        composeRule.runOnIdle { assertEquals(0xFF00FF00L, latest) }
    }

    @Test
    fun hueKeyboardInputUpdatesColor() {
        var latest = 0xFFFF0000L
        setPickerContent(initialArgb = latest) { latest = it }

        composeRule
            .onNodeWithContentDescription(string(R.string.color_picker_hue))
            .performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule
            .onNodeWithContentDescription(string(R.string.color_picker_hue))
            .performKeyInput { pressKey(Key.DirectionRight) }

        composeRule.runOnIdle { assertEquals(0xFFFF0400L, latest) }
    }

    @Test
    fun hueSemanticsReportsDisplayedValue() {
        setPickerContent(initialArgb = 0xFF00FF00L, onColorChanged = {})

        val stateDescription =
            composeRule
                .onNodeWithContentDescription(string(R.string.color_picker_hue))
                .fetchSemanticsNode()
                .config[SemanticsProperties.StateDescription]

        assertEquals("120°", stateDescription)
    }

    @Test
    fun saturationSemanticsUpdatesColorWithoutTouch() {
        var latest = 0xFFFF0000L
        setPickerContent(initialArgb = latest) { latest = it }

        composeRule
            .onNodeWithContentDescription(string(R.string.color_picker_saturation))
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                assertTrue(setProgress(0f))
            }

        composeRule.runOnIdle { assertEquals(0xFFFFFFFFL, latest) }
    }

    @Test
    fun brightnessSemanticsUpdatesColorWithoutTouch() {
        var latest = 0xFFFF0000L
        setPickerContent(initialArgb = latest) { latest = it }

        composeRule
            .onNodeWithContentDescription(string(R.string.color_picker_brightness))
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                assertTrue(setProgress(0.5f))
            }

        composeRule.runOnIdle { assertEquals(0xFF800000L, latest) }
    }

    @Test
    fun hueMaximumKeepsIndicatorAtMaximum() {
        setPickerContent(initialArgb = 0xFFFF0000L, onColorChanged = {})

        composeRule
            .onNodeWithContentDescription(string(R.string.color_picker_hue))
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                assertTrue(setProgress(360f))
            }

        val hueRange =
            composeRule
                .onNodeWithContentDescription(string(R.string.color_picker_hue))
                .fetchSemanticsNode()
                .config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(360f, hueRange.current)
    }

    @Test
    fun saturationRoundTripPreservesHueAtWhite() {
        var latest = 0xFF00FF00L
        setPickerContent(initialArgb = latest) { latest = it }

        composeRule
            .onNodeWithContentDescription(string(R.string.color_picker_saturation))
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                assertTrue(setProgress(0f))
            }
        composeRule
            .onNodeWithContentDescription(string(R.string.color_picker_saturation))
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                assertTrue(setProgress(1f))
            }

        composeRule.runOnIdle { assertEquals(0xFF00FF00L, latest) }
    }

    @Test
    fun brightnessRoundTripPreservesHueAndSaturationAtBlack() {
        var latest = 0xFF0000FFL
        setPickerContent(initialArgb = latest) { latest = it }

        composeRule
            .onNodeWithContentDescription(string(R.string.color_picker_brightness))
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                assertTrue(setProgress(0f))
            }
        composeRule
            .onNodeWithContentDescription(string(R.string.color_picker_brightness))
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                assertTrue(setProgress(1f))
            }

        composeRule.runOnIdle { assertEquals(0xFF0000FFL, latest) }
    }

    @Test
    fun tappingVisibleMaximumThumbKeepsMaximumValue() {
        var latest = 0xFFFF0000L
        setPickerContent(initialArgb = latest) { latest = it }

        composeRule.onNodeWithContentDescription(string(R.string.color_picker_brightness)).performTouchInput {
            click(Offset(width - 9f, centerY))
        }

        composeRule.runOnIdle { assertEquals(0xFFFF0000L, latest) }
    }

    @Test
    fun draggingHueStreamsLiveColorUpdates() {
        val updates = mutableListOf<Long>()
        setPickerContent(initialArgb = 0xFFFF0000L, onColorChanged = updates::add)

        composeRule.onNodeWithContentDescription(string(R.string.color_picker_hue)).performTouchInput {
            swipe(
                start = Offset(1f, centerY),
                end = Offset(width - 1f, centerY),
                durationMillis = 500,
            )
        }

        composeRule.runOnIdle { assertTrue("drag should stream intermediate colors", updates.size > 2) }
    }

    @Test
    fun exactHexMovesPickerAndVisualAdjustmentUpdatesHex() {
        var latest = 0xFFFF0000L
        composeRule.setContent {
            WhiteNoiseTheme {
                var selectedArgb by remember { mutableLongStateOf(latest) }
                TonalSwatchPicker(
                    selectedArgb = selectedArgb,
                    onColorSelected = {
                        latest = it
                        selectedArgb = it
                    },
                    scopeKey = "global",
                    theme = BubbleTheme.Light,
                    slotKey = BubbleSide.Mine.name,
                )
            }
        }

        composeRule.onNodeWithContentDescription(string(R.string.more_colors)).performClick()
        composeRule.onNodeWithText(string(R.string.custom_hex_color)).performTextReplacement("#00FF00")

        val hueRange =
            composeRule
                .onNodeWithContentDescription(string(R.string.color_picker_hue))
                .fetchSemanticsNode()
                .config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(ProgressBarRangeInfo(120f, 0f..360f, 359), hueRange)

        composeRule
            .onNodeWithContentDescription(string(R.string.color_picker_hue))
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                assertTrue(setProgress(240f))
            }
        composeRule.onNodeWithText("#0000FF").assertExists()
        composeRule.runOnIdle { assertEquals(0xFF0000FFL, latest) }
    }

    @Test
    fun amoledPickerNeverSelectsBlue() {
        var latest = 0xFFFF0000L
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                var selectedArgb by remember { mutableLongStateOf(latest) }
                TonalSwatchPicker(
                    selectedArgb = selectedArgb,
                    onColorSelected = {
                        latest = it
                        selectedArgb = it
                    },
                    scopeKey = "amoled",
                    theme = BubbleTheme.Amoled,
                    slotKey = BubbleSide.Mine.name,
                )
            }
        }

        composeRule.onNodeWithContentDescription(string(R.string.more_colors)).performClick()
        composeRule
            .onNodeWithContentDescription(string(R.string.color_picker_hue))
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                assertTrue(setProgress(240f))
            }

        composeRule.runOnIdle { assertEquals(0, latest and 0xFF) }
    }

    @Test
    fun amoledPickerRejectsInvisibleBlueFreeHueWithoutMovingControls() {
        val initialArgb = 0xFFFF0000L
        var latest = initialArgb
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                FullSpectrumColorPicker(
                    argb = initialArgb,
                    blueFree = true,
                    isColorAccepted = { it != 0xFF000000L },
                    onColorChanged = { latest = it },
                )
            }
        }

        composeRule
            .onNodeWithContentDescription(string(R.string.color_picker_hue))
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                assertTrue(setProgress(240f))
            }

        val hueRange =
            composeRule
                .onNodeWithContentDescription(string(R.string.color_picker_hue))
                .fetchSemanticsNode()
                .config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(0f, hueRange.current)
        assertEquals(initialArgb, latest)
    }

    @Test
    fun invalidExactHexCannotApply() {
        composeRule.setContent {
            WhiteNoiseTheme {
                TonalSwatchPicker(
                    selectedArgb = 0xFFFF0000L,
                    onColorSelected = {},
                    scopeKey = "global",
                    theme = BubbleTheme.Light,
                    slotKey = BubbleSide.Mine.name,
                )
            }
        }

        composeRule.onNodeWithContentDescription(string(R.string.more_colors)).performClick()
        composeRule.onNodeWithText(string(R.string.custom_hex_color)).performTextReplacement("#XYZ")
        composeRule.onNodeWithText(string(R.string.apply_color)).assertIsNotEnabled()
        composeRule.onNodeWithText(string(R.string.invalid_hex_color)).assertExists()
    }

    private fun setPickerContent(
        initialArgb: Long,
        onColorChanged: (Long) -> Unit,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                var argb by remember { mutableLongStateOf(initialArgb) }
                FullSpectrumColorPicker(
                    argb = argb,
                    onColorChanged = {
                        argb = it
                        onColorChanged(it)
                    },
                )
            }
        }
    }
}
