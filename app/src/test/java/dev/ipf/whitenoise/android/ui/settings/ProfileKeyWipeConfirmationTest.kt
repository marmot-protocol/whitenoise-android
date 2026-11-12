package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The production typed wipe gate, isolated from the account runtime and sheet transitions. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ProfileKeyWipeConfirmationTest {
    @get:Rule
    val composeRule = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Context>()

    /** The final destructive gate keeps its disabled action and Cancel readable. */
    @Ignore("Native SDK 36 dialog measurement does not idle; physical keyword/cancel check required before merge")
    @Test
    fun wipeConfirmationLight() = captureWipeConfirmation(largeRtl = false)

    /** Icon-bearing confirmation actions remain reachable at 200% RTL text. */
    @Ignore("Native SDK 36 dialog measurement does not idle; physical keyword/cancel check required before merge")
    @Test
    fun wipeConfirmationLargeRtl() = captureWipeConfirmation(largeRtl = true)

    private fun captureWipeConfirmation(largeRtl: Boolean) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = largeRtl, amoled = largeRtl, fontScale = if (largeRtl) 2f else 1f) {
                    ProfileKeyWipeConfirmationDialog(input = "", onInputChange = {}, onConfirm = {}, onDismiss = {})
                }
            }
        }
        composeRule.onNodeWithTag("profile_keys.wipe_confirm").assertIsDisplayed()
        val scale = if (largeRtl) "large_rtl" else "light"
        composeRule.onNode(isDialog()).captureRoboImage("src/test/snapshots/profile_keys_wipe_confirm_$scale.png")
    }

    /** The extracted dialog preserves its typed keyword gate and separate cancel action. */
    @Ignore("Native SDK 36 dialog measurement does not idle; physical keyword/cancel check required before merge")
    @Test
    fun wipeConfirmationRequiresKeywordAndRetainsCancel() {
        val input = mutableStateOf("")
        var confirmed = 0
        var dismissed = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                ProfileKeyWipeConfirmationDialog(
                    input = input.value,
                    onInputChange = { input.value = it },
                    onConfirm = { confirmed++ },
                    onDismiss = { dismissed++ },
                )
            }
        }
        composeRule.onNodeWithTag("profile_keys.wipe_confirm").assertIsNotEnabled()
        composeRule.onNodeWithText(app.getString(R.string.cancel)).performClick()
        assertEquals(1, dismissed)
        assertEquals(0, confirmed)
        composeRule
            .onNodeWithText(app.getString(R.string.sign_out_and_wipe_confirm_field_label))
            .performTextInput("  " + app.getString(R.string.sign_out_and_wipe_confirm_keyword).lowercase() + "  ")
        composeRule.onNodeWithTag("profile_keys.wipe_confirm").assertIsEnabled().performClick()
        assertEquals(1, confirmed)
    }
}
