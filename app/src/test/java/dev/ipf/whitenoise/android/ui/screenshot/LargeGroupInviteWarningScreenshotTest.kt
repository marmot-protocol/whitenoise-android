package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.group.LARGE_GROUP_INVITE_CONFIRMATION_TAG
import dev.ipf.whitenoise.android.ui.group.LARGE_GROUP_INVITE_WARNING_TAG
import dev.ipf.whitenoise.android.ui.group.LargeGroupInviteConfirmationDialog
import dev.ipf.whitenoise.android.ui.group.LargeGroupInviteWarningBanner
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h640dp-mdpi")
class LargeGroupInviteWarningScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Records the persistent warning against the light color scheme. */
    @Test
    fun warningLight() = captureWarning("large_group_invite_warning_light.png", dark = false, amoled = false)

    /** Records the persistent warning against the standard dark color scheme. */
    @Test
    fun warningDark() = captureWarning("large_group_invite_warning_dark.png", dark = true, amoled = false)

    /** Verifies large English fallback text keeps readable direction inside an RTL AMOLED layout. */
    @Test
    fun warningAmoledLargeRtl() {
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                WhiteNoiseTheme(darkTheme = true, amoled = true, fontScale = 2f) {
                    Surface(Modifier.fillMaxSize()) {
                        Box(Modifier.padding(top = 24.dp)) {
                            LargeGroupInviteWarningBanner()
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithTag(LARGE_GROUP_INVITE_WARNING_TAG).assertExists()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/large_group_invite_warning_amoled_large_rtl.png")
    }

    /** Verifies the explicit confirmation remains readable with large fallback text in RTL AMOLED mode. */
    @Test
    fun confirmationAmoledLargeRtl() {
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                WhiteNoiseTheme(darkTheme = true, amoled = true, fontScale = 2f) {
                    Surface(Modifier.fillMaxSize()) {
                        LargeGroupInviteConfirmationDialog(
                            onContinue = {},
                            onDismiss = {},
                        )
                    }
                }
            }
        }
        composeRule
            .onNodeWithTag(LARGE_GROUP_INVITE_CONFIRMATION_TAG)
            .captureRoboImage("src/test/snapshots/large_group_invite_confirmation_amoled_large_rtl.png")
    }

    /** Renders a bounded warning fixture with the requested theme for deterministic baseline capture. */
    private fun captureWarning(
        name: String,
        dark: Boolean,
        amoled: Boolean,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark, amoled = amoled) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.padding(top = 24.dp).testTag(ROOT_TAG)) {
                        LargeGroupInviteWarningBanner()
                    }
                }
            }
        }
        composeRule.onNodeWithTag(ROOT_TAG).captureRoboImage("src/test/snapshots/$name")
    }

    private companion object {
        const val ROOT_TAG = "large-group-warning-screenshot-root"
    }
}
