package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.settings.ShareConnectContent
import dev.ipf.whitenoise.android.ui.settings.shareConnectFixture
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
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h640dp-mdpi")
class ProfileQrSaveScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun saveActionLight() = capture("profile_qr_save_light.png", false, false, false)

    @Test
    fun savingActionDark() = capture("profile_qr_saving_dark.png", true, true, false)

    @Test
    fun saveActionLargeRtl() = capture("profile_qr_save_large_rtl.png", true, false, true)

    /** Capture the production action and verify that a busy export cannot receive another gesture. */
    private fun capture(
        fileName: String,
        dark: Boolean,
        saving: Boolean,
        largeRtl: Boolean,
    ) {
        var clicks = 0
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, fontScale = if (largeRtl) 2f else 1f) {
                    Surface(Modifier.width(360.dp).testTag("save.frame")) {
                        Box {
                            ShareConnectContent(
                                profile = shareConnectFixture,
                                qrContent = "marmot://profile/${shareConnectFixture.npub}?from=qr",
                                copied = false,
                                scanInvalid = false,
                                pictureSaveInProgress = saving,
                                onSavePicture = { clicks++ },
                                onBack = {},
                                onShareUrl = {},
                                onSharePicture = {},
                                onCopy = {},
                                onOpenScanner = {},
                            )
                        }
                    }
                }
            }
        }
        val action = composeRule.onNodeWithTag("profile.qr.save").assertIsDisplayed()
        if (saving) action.assertIsNotEnabled() else action.performClick()
        assertEquals(if (saving) 0 else 1, clicks)
        composeRule.onNodeWithTag("save.frame").captureRoboImage("src/test/snapshots/$fileName")
    }
}
