package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Contract of the colour editor's controls: presets, sliders and the hex field all agree on one colour. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h1200dp-mdpi")
class FullSpectrumColorPickerTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var latest: Long? = null
    private val validity = mutableListOf<Boolean>()

    /** A preset swatch reports its colour, marks itself selected and rewrites the hex field. */
    @Test
    fun presetSwatchSelectsAndFillsHex() {
        setPicker(0xFFFF0000L)
        composeRule.onNodeWithContentDescription("Color #1D4ED8").performClick()
        composeRule.runOnIdle {
            assertEquals(0xFF1D4ED8L, latest)
            assertEquals(listOf(true), validity)
        }
        composeRule.onNodeWithContentDescription("Color #1D4ED8").assertIsSelected()
        composeRule.onNodeWithTag("color.hex").assertTextContains("#1D4ED8")
    }

    /** Hue, saturation and brightness drive the colour without touch and label their current values. */
    @Test
    fun slidersDriveTheColour() {
        setPicker(0xFFFF0000L)
        setProgress("color.hue", 120f)
        composeRule.runOnIdle { assertEquals(0xFF00FF00L, latest) }
        composeRule.onNodeWithContentDescription("Hue: 120°").assertExists()
        setProgress("color.saturation", 0f)
        composeRule.runOnIdle { assertEquals(0xFFFFFFFFL, latest) }
        setProgress("color.saturation", 1f)
        setProgress("color.brightness", 0.5f)
        composeRule.runOnIdle { assertEquals(0xFF008000L, latest) }
        composeRule.onNodeWithTag("color.hex").assertTextContains("#008000")
    }

    /** A valid hex applies the colour and moves the sliders; an invalid one flags the field and reports invalidity. */
    @Test
    fun hexFieldAppliesValidColoursAndFlagsInvalidOnes() {
        setPicker(0xFFFF0000L)
        composeRule.onNodeWithTag("color.hex").performTextReplacement("#00FF00")
        composeRule.runOnIdle {
            assertEquals(0xFF00FF00L, latest)
            assertEquals(true, validity.last())
        }
        val hue =
            composeRule.onNodeWithTag("color.hue").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(120f, hue.current)
        composeRule.onNodeWithTag("color.hex").performTextReplacement("#XYZ")
        composeRule.runOnIdle {
            assertEquals(false, validity.last())
            assertEquals(0xFF00FF00L, latest)
        }
        composeRule.onNodeWithText("Enter six hexadecimal digits, such as #1D4ED8.").assertExists()
    }

    /** Without a saved colour the controls start from the fallback and report nothing until touched. */
    @Test
    fun fallbackSeedsTheControlsWithoutReporting() {
        setPicker(selected = null, fallback = 0xFF1D4ED8L)
        composeRule.onNodeWithTag("color.hex").assertTextContains("#1D4ED8")
        composeRule.onNodeWithContentDescription("Color #1D4ED8").assertIsSelected()
        composeRule.runOnIdle {
            assertNull(latest)
            assertTrue(validity.isEmpty())
        }
    }

    /** Moves the tagged slider through its accessibility action, asserting the slider accepted the value. */
    private fun setProgress(
        tag: String,
        value: Float,
    ) {
        composeRule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(value)) }
    }

    /** Renders one picker in the light theme, recording every reported colour and validity change. */
    private fun setPicker(
        selected: Long?,
        fallback: Long = 0xFF000000L,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                FullSpectrumColorPicker(
                    selectedArgb = selected,
                    fallbackArgb = fallback,
                    onColorSelected = { latest = it },
                    onValidityChanged = { validity += it },
                )
            }
        }
    }
}
