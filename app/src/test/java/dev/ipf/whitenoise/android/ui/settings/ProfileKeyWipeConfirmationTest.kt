package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Real-window pixel baselines for the final typed wipe gate.
 * ProfileKeyWipeConfirmationAndroidTest owns platform text input, IME and physical action coverage.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ProfileKeyWipeConfirmationTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** The final destructive gate keeps its disabled action and Cancel readable. */
    @Test
    fun wipeConfirmationLight() = captureWipeConfirmation(largeRtl = false)

    /** Icon-bearing confirmation actions remain reachable at 200% RTL text. */
    @Test
    fun wipeConfirmationLargeRtl() = captureWipeConfirmation(largeRtl = true)

    private fun captureWipeConfirmation(largeRtl: Boolean) {
        // Native dialog traversal must not chase continuously scheduled Compose frames.
        // Advance deterministically, retaining the real platform dialog and its window insets.
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = largeRtl, amoled = largeRtl, fontScale = if (largeRtl) 2f else 1f) {
                    ProfileKeyWipeConfirmationDialog(input = "", onInputChange = {}, onConfirm = {}, onDismiss = {})
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.onNodeWithTag("profile_keys.wipe_confirm").assertIsDisplayed()
        val scale = if (largeRtl) "large_rtl" else "light"
        composeRule.onNode(isDialog()).captureRoboImage("src/test/snapshots/profile_keys_wipe_confirm_$scale.png")
    }
}
