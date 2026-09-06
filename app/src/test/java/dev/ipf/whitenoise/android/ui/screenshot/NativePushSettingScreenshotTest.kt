package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.notifications.NativePushCapability
import dev.ipf.whitenoise.android.ui.common.SettingsGroup
import dev.ipf.whitenoise.android.ui.settings.NativePushSettingRow
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class NativePushSettingScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Records the configuration-free build explanation at compact phone width. */
    @Test
    fun missingPushServerLight() {
        render(NativePushCapability.MissingPushServerConfiguration)
        capture("native_push_missing_server_light.png")
    }

    /** Exercises Play-services copy under RTL, dark theme, and enlarged text. */
    @Test
    fun unavailableGooglePlayServicesDarkLargeRtl() {
        render(
            capability = NativePushCapability.GooglePlayServicesUnavailable,
            darkTheme = true,
            rtl = true,
            fontScale = 1.4f,
        )
        capture("native_push_google_play_unavailable_dark_large_rtl.png")
    }

    /** Records both missing-configuration and failed-initialization causes for unavailable Firebase. */
    @Test
    fun missingFirebaseLight() {
        render(NativePushCapability.FirebaseUnavailable)
        capture("native_push_firebase_unavailable_light.png")
    }

    /** Verifies Italian Firebase recovery copy remains readable with enlarged text. */
    @Test
    @Config(qualifiers = "it-w360dp-h780dp-mdpi")
    fun unavailableFirebaseItalianLarge() {
        render(NativePushCapability.FirebaseUnavailable, fontScale = 1.4f)
        capture("native_push_firebase_unavailable_italian_large_light.png")
    }

    /** Renders the production setting row under the requested accessibility conditions. */
    private fun render(
        capability: NativePushCapability,
        darkTheme: Boolean = false,
        rtl: Boolean = false,
        fontScale: Float = 1f,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = darkTheme) {
                    Surface(modifier = Modifier.width(360.dp).testTag(TAG)) {
                        SettingsGroup {
                            item {
                                NativePushSettingRow(
                                    capability = capability,
                                    accountReady = true,
                                    checked = false,
                                    onCheckedChange = {},
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /** Captures the tagged production row into its tracked Roborazzi baseline. */
    private fun capture(fileName: String) {
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/$fileName")
    }

    private companion object {
        const val TAG = "native-push-setting"
    }
}
