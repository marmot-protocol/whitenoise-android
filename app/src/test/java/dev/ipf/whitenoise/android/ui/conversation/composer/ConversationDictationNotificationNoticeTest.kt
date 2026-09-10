package dev.ipf.whitenoise.android.ui.conversation.composer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.provider.Settings
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ipf.whitenoise.android.audio.ConversationDictationForegroundService
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConversationDictationNotificationNoticeTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Denial offers the app settings page and the notice refreshes when that Activity returns. */
    @Test
    fun appDenialOpensSettingsAndResumeRemovesRecoveredNotice() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(false)
        val owner = Owner()
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                WhiteNoiseTheme { ConversationDictationNotificationNotice() }
            }
        }
        composeRule.onNodeWithTag(DICTATION_NOTIFICATION_NOTICE_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Open settings").performClick()
        val intent = shadowOf(context).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(context.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        composeRule.runOnIdle {
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            shadowOf(manager).setNotificationsEnabled(true)
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
        }
        composeRule.onNodeWithTag(DICTATION_NOTIFICATION_NOTICE_TAG).assertDoesNotExist()
    }

    /** A blocked category opens that exact category instead of asking for an already granted permission. */
    @Test
    fun channelDenialOpensDictationCategory() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(true)
        manager.createNotificationChannel(
            NotificationChannel(
                ConversationDictationForegroundService.CHANNEL_ID,
                "Dictation",
                NotificationManager.IMPORTANCE_NONE,
            ),
        )
        composeRule.setContent { WhiteNoiseTheme { ConversationDictationNotificationNotice() } }
        composeRule.onNodeWithText("Open settings").performClick()
        val intent = shadowOf(context).nextStartedActivity
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(
            ConversationDictationForegroundService.CHANNEL_ID,
            intent.getStringExtra(Settings.EXTRA_CHANNEL_ID),
        )
    }

    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
    }
}
