package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.group.PinConversationActionRow
import dev.ipf.whitenoise.android.ui.settings.SettingsGroup
import dev.ipf.whitenoise.android.ui.settings.SettingsLink
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Production settings rows expose support and pending-request states without claiming launcher approval. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class PinConversationActionScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    /** The supported action is discoverable alongside the existing chat settings. */
    @Test
    fun supportedLight() {
        render()
        composeRule.onNodeWithTag(ACTION).assertIsEnabled()
        capture("supported_light")
    }

    /** Dark theme preserves the same touch target and icon contrast. */
    @Test
    fun supportedDark() {
        render(dark = true)
        capture("supported_dark")
    }

    /** Unsupported launchers explain the disabled action with wrapping text at large font scale in RTL. */
    @Test
    fun unsupportedLargeRtl() {
        var requests = 0
        render(supported = false, rtl = true, fontScale = 2f, onPin = { requests += 1 })
        composeRule.onNodeWithTag(ACTION).assertIsNotEnabled().performClick()
        composeRule.onNodeWithText("Your launcher does not support pinned shortcuts.").assertExists()
        composeRule.runOnIdle { assertEquals(0, requests) }
        capture("unsupported_large_rtl")
    }

    /** A queued request disables re-entry, and returning to idle allows another explicit request. */
    @Test
    fun busyActionPreventsDuplicatesAndRecovers() {
        val busy = mutableStateOf(false)
        var requests = 0
        render(busy = { busy.value }, onPin = {
            requests += 1
            busy.value = true
        })
        composeRule.onNodeWithTag(ACTION).performClick()
        composeRule.onNodeWithTag(ACTION).assertIsNotEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, requests) }
        composeRule.runOnIdle { busy.value = false }
        composeRule.onNodeWithTag(ACTION).assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(2, requests) }
    }

    /** Keep the action inside its real segmented settings group, including its neighboring row. */
    private fun render(
        supported: Boolean = true,
        dark: Boolean = false,
        rtl: Boolean = false,
        fontScale: Float = 1f,
        busy: () -> Boolean = { false },
        onPin: () -> Unit = {},
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark) {
                    Surface(Modifier.width(360.dp).testTag("pin_settings")) {
                        Column(Modifier.padding(vertical = 16.dp)) {
                            SettingsGroup {
                                row("pin_shortcut") { PinConversationActionRow(it, supported, busy(), onPin) }
                                row("notifications") { SettingsLink(it, "Notifications", onClick = {}) }
                            }
                        }
                    }
                }
            }
        }
    }

    /** Baselines are owned by this fixture and verified for both distributions. */
    private fun capture(state: String) {
        composeRule.onNodeWithTag("pin_settings").captureRoboImage("src/test/snapshots/pin_conversation_$state.png")
    }

    private companion object {
        const val ACTION = "chat_info.pin_shortcut"
    }
}
