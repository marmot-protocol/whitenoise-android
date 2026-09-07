package dev.ipf.whitenoise.android.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.NativePushCapability
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
@Config(sdk = [36], qualifiers = "en")
class NotificationsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    /** Verifies every unsupported cause remains off, disabled, and explicitly named. */
    @Test
    fun unsupportedCapabilitiesAreDisabledAndExplainTheirSpecificCause() {
        var toggleCalls = 0
        val cases =
            listOf(
                NativePushCapability.MissingPushServerConfiguration to
                    R.string.native_push_missing_server_subtitle,
                NativePushCapability.GooglePlayServicesUnavailable to
                    R.string.native_push_google_play_unavailable_subtitle,
                NativePushCapability.FirebaseUnavailable to
                    R.string.native_push_firebase_unavailable_subtitle,
            )

        composeRule.setContent {
            WhiteNoiseTheme {
                Column {
                    cases.forEach { (capability, _) ->
                        NativePushSettingRow(
                            capability = capability,
                            accountReady = true,
                            checked = true,
                            onCheckedChange = { toggleCalls += 1 },
                        )
                    }
                }
            }
        }

        cases.forEach { (_, subtitle) ->
            composeRule.onNodeWithText(app.getString(subtitle)).assertIsDisplayed()
        }
        cases.indices.forEach { index ->
            composeRule
                .onAllNodes(isToggleable())[index]
                .assertIsNotEnabled()
                .assertIsOff()
                .performTouchInput { click() }
        }
        composeRule.runOnIdle { assertEquals(0, toggleCalls) }
    }

    /** Enables an already-selected switch only after capability and account readiness agree. */
    @Test
    fun availableCapabilityEnablesCheckedSwitchAfterAccountReadiness() {
        composeRule.setContent {
            WhiteNoiseTheme {
                NativePushSettingRow(
                    capability = NativePushCapability.Available,
                    accountReady = true,
                    checked = true,
                    onCheckedChange = {},
                )
            }
        }

        composeRule.onNodeWithText(app.getString(R.string.native_push_subtitle)).assertIsDisplayed()
        composeRule.onNode(isToggleable()).assertIsEnabled().assertIsOn()
    }

    /** Preserves the selected value while preventing interaction without an active account. */
    @Test
    fun availableCapabilityStaysDisabledUntilAnAccountIsReady() {
        composeRule.setContent {
            WhiteNoiseTheme {
                NativePushSettingRow(
                    capability = NativePushCapability.Available,
                    accountReady = false,
                    checked = true,
                    onCheckedChange = {},
                )
            }
        }

        composeRule.onNode(isToggleable()).assertIsNotEnabled().assertIsOn()
    }
}
