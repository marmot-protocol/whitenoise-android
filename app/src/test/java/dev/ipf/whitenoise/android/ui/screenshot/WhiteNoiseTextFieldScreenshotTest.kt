package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseTextField
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Pins the field's geometry, state rings, and directional label/supporting alignment. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h900dp-mdpi")
class WhiteNoiseTextFieldScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Rest, error, disabled, and nested multiline surfaces share the same light-theme geometry. */
    @Test
    fun tonalFieldLight() {
        render(darkTheme = false)
        capture("light")
    }

    /** Dark palette roles must retain visible error and disabled content without literal light fills. */
    @Test
    fun tonalFieldDark() {
        render(darkTheme = true)
        capture("dark")
    }

    /** The existing AMOLED preference keeps an outline around the black resting field. */
    @Test
    fun tonalFieldAmoled() {
        render(darkTheme = true, amoled = true)
        capture("amoled")
    }

    /** Above labels and supporting text align to the directional content inset in RTL layouts. */
    @Test
    fun tonalFieldRtl() {
        render(darkTheme = false, layoutDirection = LayoutDirection.Rtl)
        capture("rtl")
    }

    /** Labels, error guidance, and multiline content grow without a fixed-height capsule clipping them. */
    @Test
    fun tonalFieldLargeFont() {
        render(darkTheme = false, fontScale = 2f)
        capture("large_font")
    }

    /** A focused field uses the full 2 dp state ring while preserving the above-label placement. */
    @Test
    fun tonalFieldFocused() {
        render(darkTheme = false)
        composeRule.onNodeWithTag(INPUT).performClick()
        composeRule.mainClock.advanceTimeBy(300)
        composeRule.mainClock.autoAdvance = false
        capture("focused")
    }

    /** Use the active app theme and real Material slots. */
    @OptIn(ExperimentalMaterial3Api::class)
    private fun render(
        darkTheme: Boolean,
        amoled: Boolean = false,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        fontScale: Float = 1f,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides layoutDirection,
            ) {
                WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                    Surface(modifier = Modifier.width(360.dp).testTag(TAG)) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            WhiteNoiseTextField(
                                state = rememberTextFieldState(),
                                modifier = Modifier.fillMaxWidth().testTag(INPUT),
                                label = { Text("Display name") },
                                placeholder = { Text("Your name") },
                                lineLimits = TextFieldLineLimits.SingleLine,
                            )
                            WhiteNoiseTextField(
                                state = rememberTextFieldState("relay.example"),
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Relay address") },
                                supportingText = { Text(ERROR) },
                                errorMessage = ERROR,
                                lineLimits = TextFieldLineLimits.SingleLine,
                            )
                            WhiteNoiseTextField(
                                state = rememberTextFieldState("Saved account"),
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Account") },
                                enabled = false,
                                lineLimits = TextFieldLineLimits.SingleLine,
                            )
                            WhiteNoiseTextField(
                                state = rememberTextFieldState("Community updates\nand local meetups"),
                                modifier = Modifier.fillMaxWidth(),
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                                label = { Text("About") },
                                readOnly = true,
                                lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 2),
                            )
                        }
                    }
                }
            }
        }
    }

    /** Capture only the field group so unrelated window chrome cannot change its baseline. */
    private fun capture(variant: String) {
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/white_noise_text_field_$variant.png")
    }

    private companion object {
        const val TAG = "white-noise-text-fields"
        const val INPUT = "white-noise-text-field-input"
        const val ERROR = "Enter a secure relay URL beginning with wss://."
    }
}
