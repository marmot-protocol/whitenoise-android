package dev.ipf.whitenoise.android.ui.profile

import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.notifications.ConversationVibrationPattern
import dev.ipf.whitenoise.android.notifications.ProfileNotificationMode
import dev.ipf.whitenoise.android.notifications.ProfileNotificationOverride
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Pure screen actions and published baselines cover the collapsed profile entry plus its dedicated settings page. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ProfileNotificationOverrideScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun defaultAndMuteActionsUpdateTheVisibleSelection() {
        val state = mutableStateOf(ProfileNotificationOverrideState())
        var systemClicks = 0
        var vibrationClicks = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                ProfileNotificationOverrideScreen(
                    state.value,
                    {},
                    onMode = { state.value = state.value.copy(selection = state.value.selection.copy(mode = it)) },
                    onSystemSettings = { systemClicks += 1 },
                    onChooseVibration = { vibrationClicks += 1 },
                )
            }
        }
        composeRule.onNodeWithTag("profile_notifications.mute").performClick()
        composeRule.onNodeWithText("Muted").assertIsDisplayed()
        composeRule.onNodeWithTag("profile_notifications.defaults").performClick()
        composeRule.onNodeWithText("Default").assertIsDisplayed()
        composeRule.onNodeWithTag("profile_notifications.system").performClick()
        composeRule.onNodeWithTag("profile_notifications.vibration").performClick()
        assertEquals(1, systemClicks)
        assertEquals(1, vibrationClicks)
    }

    @Test fun overviewRowIsSuppressedForLocalAndUnresolvedProfiles() {
        val self = mutableStateOf(false)
        val resolved = mutableStateOf(true)
        composeRule.setContent { profile(self.value, resolved.value) }
        composeRule.onNodeWithTag("person_profile.notifications").performScrollTo().assertIsDisplayed()
        composeRule.runOnIdle { self.value = true }
        composeRule.onNodeWithTag("person_profile.notifications").assertDoesNotExist()
        composeRule.runOnIdle {
            self.value = false
            resolved.value = false
        }
        composeRule.onNodeWithTag("person_profile.notifications").assertDoesNotExist()
    }

    @Test fun profileRowLight() {
        composeRule.setContent { profile() }
        composeRule.onNodeWithTag("person_profile.notifications").performScrollTo()
        capture("profile_notification_row_light")
    }

    @Test fun defaultScreenLight() = screen("profile_notifications_light")

    @Test fun customScreenDark() = screen("profile_notifications_dark", dark = true)

    @Test fun customScreenAmoled() = screen("profile_notifications_amoled", dark = true, amoled = true)

    @Test fun customScreenLargeRtl() = screen("profile_notifications_large_rtl", dark = true, rtl = true, scale = 2f)

    /** Scrollable large-text fixture keeps controls reachable without changing preference or channel state. */
    private fun screen(
        name: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        rtl: Boolean = false,
        scale: Float = 1f,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = scale) {
                    val selected =
                        if (dark) {
                            ProfileNotificationOverride(
                                ProfileNotificationMode.CUSTOM,
                                ConversationVibrationPattern.DOUBLE,
                            )
                        } else {
                            ProfileNotificationOverride()
                        }
                    ProfileNotificationOverrideScreen(ProfileNotificationOverrideState(selected), {}, {}, {}, {})
                }
            }
        }
        capture(name)
        composeRule.onNodeWithTag("profile_notifications.vibration").performScrollTo().assertIsDisplayed()
    }

    /** Real overview component with only presentation values and inert callbacks. */
    @androidx.compose.runtime.Composable
    private fun profile(
        self: Boolean = false,
        resolved: Boolean = true,
    ) {
        WhiteNoiseTheme {
            PersonProfileContent(
                PersonProfilePresentation(
                    "Alice",
                    "Alice",
                    "a".repeat(64),
                    null,
                    null,
                    false,
                    "Building things with friends.",
                    "npub1example",
                    null,
                    false,
                    null,
                    resolved,
                    self,
                ),
                scroll = rememberScrollState(),
                follow = ProfileFollowRowState(false, false, true),
                busy = false,
                fromGroup = false,
                canPromote = false,
                showSharedGroups = false,
                copied = false,
                onBack = {},
                onMessage = {},
                onFollow = {},
                onPrivateDetails = {},
                onStartGroup = {},
                onGroupEntry = {},
                onPromote = {},
                onCopy = {},
                onAvatar = {},
                onBanner = {},
                onCopyLightning = {},
                notificationSummary = "Default",
                onNotifications = {},
            )
        }
    }

    /** Committed image evidence uses the real themed Compose tree. */
    private fun capture(name: String) {
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }
}
