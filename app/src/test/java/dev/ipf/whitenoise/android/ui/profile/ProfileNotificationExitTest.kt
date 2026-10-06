package dev.ipf.whitenoise.android.ui.profile

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.NotificationChannels
import dev.ipf.whitenoise.android.notifications.ProfileNotificationChannels
import dev.ipf.whitenoise.android.notifications.ProfileNotificationMode
import dev.ipf.whitenoise.android.notifications.ProfileNotificationOverridePreferences
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** Animated exits retain composition, but their page no longer owns queued Android platform actions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProfileNotificationExitTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test fun backRevokesCustomizationWhileOutgoingPageIsStillMounted() = exit(reopen = false)

    /** Re-entering before the exit ends cannot revive the former page's pending customization. */
    @Test fun reopeningDuringExitCannotReviveTheOldCustomization() = exit(reopen = true)

    private fun exit(reopen: Boolean) {
        val context: Context = RuntimeEnvironment.getApplication()
        NotificationChannels.ensureChannels(context)
        val preferences = ProfileNotificationOverridePreferences(context)
        val io = StandardTestDispatcher()
        val notificationPage = mutableStateOf(true)
        val entry = mutableStateOf(Any())
        val author = "a".repeat(64)
        composeRule.setContent {
            WhiteNoiseTheme {
                AnimatedContent(notificationPage.value, label = "profile-page") { shown ->
                    if (shown) {
                        key(entry.value) {
                            val capturedEntry = entry.value
                            ProfileNotificationOverrideRoot(
                                preferences,
                                "personal",
                                author,
                                "Alice",
                                ownerIsCurrent = { notificationPage.value && entry.value === capturedEntry },
                                onBack = { notificationPage.value = false },
                                ioDispatcher = io,
                            )
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithTag("profile_notifications.system").performClick()
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        composeRule.mainClock.advanceTimeByFrame()
        // The exit has started but has not disposed the outgoing screen.
        composeRule.onNodeWithTag("profile_notifications.system").assertExists()
        if (reopen) {
            composeRule.runOnIdle {
                entry.value = Any()
                notificationPage.value = true
            }
        }
        io.scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        assertEquals(ProfileNotificationMode.DEFAULT, preferences.get("personal", author).mode)
        assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        assertEquals(
            0,
            context.getSystemService(android.app.NotificationManager::class.java).notificationChannels.count {
                it.id.startsWith(ProfileNotificationChannels.PREFIX)
            },
        )
        composeRule.mainClock.autoAdvance = true
    }
}
