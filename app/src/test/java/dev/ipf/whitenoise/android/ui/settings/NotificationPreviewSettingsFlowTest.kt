package dev.ipf.whitenoise.android.ui.settings

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.ipf.marmotkit.NotificationTrafficClassFfi
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.LocalNotificationPresenter
import dev.ipf.whitenoise.android.notifications.NotificationGroupReconciler
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Drives the settings surface and verifies the same account-independent OS card. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h1200dp-mdpi")
class NotificationPreviewSettingsFlowTest {
    @get:Rule
    val composeRule = createComposeRule()

    @After
    fun cleanup() {
        NotificationGroupReconciler.shared(RuntimeEnvironment.getApplication()).close()
    }

    /** Disabling without an account silently scrubs a card; enabling alone cannot restore its content. */
    @Test
    fun switchPersistsPrivacyAndScrubsExistingCards() {
        val context: Context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
        preferences.edit().remove("show_notification_previews").commit()
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        val presenter = LocalNotificationPresenter(context, groupReconciliation = {})
        presenter.ensureChannels()
        assertTrue(runBlocking { presenter.show(update(), shortNpub = { "Private sender" }) })
        val originalCard = manager.activeNotifications.single()
        val original = originalCard.notification

        fun currentCard() =
            manager.activeNotifications
                .firstOrNull {
                    it.tag == originalCard.tag && it.id == originalCard.id
                }?.notification
        val state =
            WhiteNoiseAppState(
                context = context,
                draftStore = DraftStore.forContext(context),
                accountIdHexResolver = { null },
                accounts = emptyList(),
                activeAccountRef = "missing-account",
            )
        composeRule.setContent { WhiteNoiseTheme { NotificationsScreen(appState = state, onBack = {}) } }
        composeRule.onNodeWithText("Show notification previews").assertIsOn().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            !state.notificationPreviewSettings.busy &&
                !state.notificationPreviewSettings.enabled &&
                !preferences.getBoolean("show_notification_previews", true) &&
                currentCard()
                    ?.extras
                    ?.getCharSequence(Notification.EXTRA_TITLE)
                    ?.toString() == context.getString(R.string.app_name)
        }
        composeRule.onNodeWithText("Show notification previews").assertIsOff()
        val hidden = checkNotNull(currentCard())
        assertEquals(original.channelId, hidden.channelId)
        assertEquals(original.contentIntent, hidden.contentIntent)
        assertEquals(original.deleteIntent, hidden.deleteIntent)
        assertTrue(hidden.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        composeRule.onNodeWithText("Show notification previews").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            !state.notificationPreviewSettings.busy && preferences.getBoolean("show_notification_previews", false)
        }
        assertEquals(
            context.getString(R.string.app_name),
            checkNotNull(currentCard()).extras.getCharSequence(Notification.EXTRA_TITLE),
        )
    }

    /** Busy disables the whole switch row; failed writes have an accessible explicit Retry action. */
    @Test
    fun pendingAndFailedSettingsHaveClearActions() {
        val state = mutableStateOf(NotificationPreviewControlState(enabled = false, busy = true))
        var retries = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                NotificationPreviewControl(state = state.value, onChange = {}, onRetry = { retries++ })
            }
        }
        composeRule.onNodeWithText("Show notification previews").assertIsNotEnabled()
        composeRule.runOnIdle { state.value = NotificationPreviewControlState(enabled = false, failed = true) }
        composeRule.onNodeWithText("Show notification previews").assertIsEnabled().assertIsOff()
        composeRule.onNodeWithText("Retry").assertIsEnabled().performClick()
        assertEquals(1, retries)
    }

    /** Synthetic message with no account runtime dependency. */
    private fun update() =
        NotificationUpdateFfi(
            notificationKey = "key",
            conversationKey = "conversation",
            trigger = NotificationTriggerFfi.NEW_MESSAGE,
            trafficClass = NotificationTrafficClassFfi.STANDARD,
            accountRef = "account-a",
            accountIdHex = "account-a",
            groupIdHex = "group-a",
            groupName = "Private group",
            isDm = false,
            isMention = false,
            messageIdHex = "msg-a",
            sender = NotificationUserFfi("sender", "Private sender", null),
            receiver = NotificationUserFfi("self", "Private self", null),
            previewText = "Private text",
            reactionEmoji = null,
            reactedToPreview = null,
            timestampMs = 1000L,
            isFromSelf = false,
        )
}
