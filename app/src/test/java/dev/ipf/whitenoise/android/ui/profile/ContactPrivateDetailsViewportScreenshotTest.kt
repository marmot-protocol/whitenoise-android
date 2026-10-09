package dev.ipf.whitenoise.android.ui.profile

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.SecureFlagPolicy
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseTextField
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A measured reduced viewport guards the footer; the instrumented counterpart opens a real IME. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h500dp-mdpi")
class ContactPrivateDetailsViewportScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun keyboardViewportLight() = capture("light")

    @Test fun keyboardViewportDark() = capture("dark", dark = true)

    @Test fun keyboardViewportAmoled() = capture("amoled", dark = true, amoled = true)

    @Test fun keyboardViewportLargeRtl() = capture("large_rtl", scale = 2f, rtl = true)

    private fun capture(
        name: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        scale: Float = 1f,
        rtl: Boolean = false,
    ) {
        var saved = 0
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, scale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled) {
                    ContactPrivateDetailsFrame(
                        SecureFlagPolicy.SecureOff,
                        {},
                        actions = {
                            TextButton({}, Modifier.testTag("cancel")) { Text("Cancel") }
                            TextButton({ saved++ }, Modifier.testTag("save")) { Text("Save") }
                        },
                        form = {
                            WhiteNoiseTextField(
                                remember { TextFieldState("A private nickname") },
                                Modifier.fillMaxWidth(),
                            )
                            WhiteNoiseTextField(
                                remember { TextFieldState("Long private notes.\n".repeat(12)) },
                                Modifier.fillMaxWidth(),
                            )
                        },
                        viewportInsets = WindowInsets(bottom = 210.dp),
                    )
                }
            }
        }
        composeRule.onNodeWithTag("cancel").assertIsDisplayed()
        composeRule.onNodeWithTag("save").assertIsDisplayed().performClick()
        assertEquals(1, saved)
        composeRule.onRoot().captureRoboImage("src/test/snapshots/private_contact_ime_$name.png")
    }
}
