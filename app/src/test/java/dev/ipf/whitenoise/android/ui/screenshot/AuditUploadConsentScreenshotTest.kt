package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.settings.AuditUploadConsentContent
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class AuditUploadConsentScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun light() = render("light", false)

    @Test fun dark() = render("dark", true)

    @Test fun largeRtl() = render("large_rtl", true, 1.6f, LayoutDirection.Rtl)

    private fun render(
        name: String,
        dark: Boolean,
        fontScale: Float = 1f,
        direction: LayoutDirection = LayoutDirection.Ltr,
    ) {
        var confirmations = 0
        var cancellations = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale), LocalLayoutDirection provides direction) {
                WhiteNoiseTheme(darkTheme = dark, fontScale = fontScale) {
                    AuditUploadConsentContent(onDismiss = { cancellations++ }, onConfirm = { confirmations++ })
                }
            }
        }
        composeRule.onNodeWithText("Enable automatic sharing").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/audit_upload_consent_$name.png")
        composeRule.onNodeWithText("Cancel").performClick()
        assertEquals(0, confirmations)
        assertEquals(1, cancellations)
        composeRule.onNodeWithText("Enable automatic sharing").performClick()
        assertEquals(1, confirmations)
    }
}
