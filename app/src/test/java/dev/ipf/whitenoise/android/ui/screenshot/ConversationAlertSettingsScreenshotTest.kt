package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.notifications.NotificationChannelSpec
import dev.ipf.whitenoise.android.ui.group.ConversationAlertSetting
import dev.ipf.whitenoise.android.ui.group.ConversationAlertSettingsRows
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
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ConversationAlertSettingsScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun groupAlertsLight() {
        render(isDm = false)
        composeRule.onNodeWithTag("alerts").captureRoboImage("src/test/snapshots/conversation_alerts_group_light.png")
    }

    @Test
    fun directAlertsDark() {
        render(isDm = true, dark = true)
        composeRule.onNodeWithTag("alerts").captureRoboImage("src/test/snapshots/conversation_alerts_dm_dark.png")
    }

    @Test
    fun blockedAlertsLargeRtl() {
        render(isDm = false, blocked = true, rtl = true, fontScale = 2f)
        composeRule.onNodeWithTag("alerts").captureRoboImage(
            "src/test/snapshots/conversation_alerts_blocked_large_rtl.png",
        )
    }

    @Test
    fun independentSwitchesReportTheirCategory() {
        var changed: Pair<NotificationChannelSpec, Boolean>? = null
        render(isDm = true, onChange = { channel, enabled -> changed = channel to enabled })
        composeRule.onNodeWithTag("conversation-alert-messages_dm").assertIsOff().performClick()
        composeRule.runOnIdle { assertEquals(NotificationChannelSpec.DIRECT_MESSAGES to true, changed) }
        composeRule.onNodeWithTag("conversation-alert-mentions").assertIsOn().performClick()
        composeRule.runOnIdle { assertEquals(NotificationChannelSpec.MENTIONS to false, changed) }
        composeRule.onNodeWithTag("conversation-alert-reactions_v2").assertIsOff()
    }

    private fun render(
        isDm: Boolean,
        dark: Boolean = false,
        blocked: Boolean = false,
        rtl: Boolean = false,
        fontScale: Float = 1f,
        onChange: (NotificationChannelSpec, Boolean) -> Unit = { _, _ -> },
    ) {
        val primary = if (isDm) NotificationChannelSpec.DIRECT_MESSAGES else NotificationChannelSpec.GROUP_MESSAGES
        val settings = listOf(primary, NotificationChannelSpec.MENTIONS, NotificationChannelSpec.REACTIONS)
            .map { ConversationAlertSetting(it, it == NotificationChannelSpec.MENTIONS, blocked) }
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
            ) {
                WhiteNoiseTheme(darkTheme = dark) {
                    Surface(Modifier.width(360.dp).testTag("alerts")) {
                        ConversationAlertSettingsRows(settings, busy = false, onChange = onChange)
                    }
                }
            }
        }
    }
}
