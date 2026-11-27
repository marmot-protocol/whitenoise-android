package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.DeviceLinkPromptFfi
import dev.ipf.marmotkit.DeviceLinkPromptStateFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.devices.DEVICE_LINK_WITH_ME_TAG
import dev.ipf.whitenoise.android.ui.devices.DeviceLinkConsentGate
import dev.ipf.whitenoise.android.ui.devices.DeviceLinkConsentPage
import dev.ipf.whitenoise.android.ui.devices.DeviceLinkConsentProfile
import dev.ipf.whitenoise.android.ui.devices.fixtureCode
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h900dp-mdpi")
class DeviceLinkConsentScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun noScreenLockCannotRevealTheComparison() {
        compose.setContent {
            WhiteNoiseTheme {
                DeviceLinkConsentGate(
                    prompt =
                        DeviceLinkPromptFfi(
                            promptId = "request",
                            slotIdHex = null,
                            requestedAt = 1u,
                            state = DeviceLinkPromptStateFfi.Comparing(fixtureCode()),
                        ),
                    profile = DeviceLinkConsentProfile("Work profile", "work", null),
                    stillCurrent = { true },
                    onClose = {},
                    onReport = {},
                ) { Text("Comparison") }
            }
        }
        compose.onNodeWithText("Comparison").assertDoesNotExist()
        compose.onNodeWithTag(DEVICE_LINK_WITH_ME_TAG).performClick()
        compose.onNodeWithTag(DEVICE_LINK_WITH_ME_TAG).assertIsDisplayed()
        compose.onNodeWithText("Comparison").assertDoesNotExist()
    }

    @Test
    fun beforeCredential() {
        compose.setContent {
            WhiteNoiseTheme {
                DeviceLinkConsentPage(
                    profile = DeviceLinkConsentProfile("Work profile", "work", null),
                    time = "2:18 PM",
                    onCall = false,
                    canContinue = true,
                    error = null,
                    onContinue = {},
                    onClose = {},
                    onReport = {},
                )
            }
        }
        compose.onRoot().captureRoboImage("src/test/snapshots/device_link_consent_light.png")
    }

    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w360dp-h1000dp-mdpi")
    fun onCallRtlAtLargeFont() {
        compose.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f, fontScale = 1.8f),
                    LocalLayoutDirection provides LayoutDirection.Rtl,
                ) {
                    DeviceLinkConsentPage(
                        profile = DeviceLinkConsentProfile("Work profile", "work", null),
                        time = "2:18 PM",
                        onCall = true,
                        canContinue = false,
                        error = R.string.link_consent_lock_required,
                        onContinue = {},
                        onClose = {},
                        onReport = {},
                    )
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/snapshots/device_link_consent_dark_rtl_large_font.png")
    }
}
