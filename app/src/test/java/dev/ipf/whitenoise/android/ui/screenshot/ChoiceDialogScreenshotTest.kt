package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.common.ChoiceDialog
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Pins the choice dialog's row overhang, selected fill, and supporting copy across themes. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ChoiceDialogScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Light dialog with a tonal selected row and supporting copy beneath the choices. */
    @Test
    fun choiceDialogLight() {
        render(darkTheme = false)
        capture("light")
    }

    /** Dark dialog keeps the selected row legible on the dark container. */
    @Test
    fun choiceDialogDark() {
        render(darkTheme = true)
        capture("dark")
    }

    /** The current AMOLED palette marks the selected row with a faint content wash instead of a tonal fill. */
    @Test
    fun choiceDialogAmoled() {
        render(darkTheme = true, amoled = true)
        capture("amoled")
    }

    /** Compose the dialog directly with resolved labels so no screen chrome affects the baseline. */
    private fun render(
        darkTheme: Boolean,
        amoled: Boolean = false,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                ChoiceDialog(
                    title = "Font size",
                    values = listOf("Small", "Default", "Large", "Extra large"),
                    selected = "Large",
                    label = { it },
                    supportingText = "Scales all app text together with the system font size.",
                    onDismiss = {},
                    onSelect = {},
                )
            }
        }
    }

    /** Capture only the dialog window. */
    private fun capture(variant: String) {
        composeRule.onNode(isDialog()).captureRoboImage("src/test/snapshots/choice_dialog_$variant.png")
    }
}
