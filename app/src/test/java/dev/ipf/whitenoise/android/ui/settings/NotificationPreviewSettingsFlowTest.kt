package dev.ipf.whitenoise.android.ui.settings

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.ipf.marmotkit.NotificationTrafficClassFfi
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.LocalNotificationPresenter
import dev.ipf.whitenoise.android.notifications.NotificationGroupReconciler
import dev.ipf.whitenoise.android.notifications.NotificationPreviewMode
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
        composeRule.onNodeWithTag("notification-preview.full").assertIsSelected()
        composeRule.onNodeWithTag("notification-preview.none").assertIsNotSelected().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            !state.notificationPreviewSettings.busy &&
                state.notificationPreviewSettings.mode == NotificationPreviewMode.NONE &&
                !preferences.getBoolean("show_notification_previews", true) &&
                currentCard()
                    ?.extras
                    ?.getCharSequence(Notification.EXTRA_TITLE)
                    ?.toString() == context.getString(R.string.app_name)
        }
        composeRule.onNodeWithTag("notification-preview.none").assertIsSelected()
        composeRule.onNodeWithTag("notification-preview.full").assertIsNotSelected()
        val hidden = checkNotNull(currentCard())
        assertEquals(original.channelId, hidden.channelId)
        assertEquals(original.contentIntent, hidden.contentIntent)
        assertEquals(original.deleteIntent, hidden.deleteIntent)
        assertTrue(hidden.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        composeRule.onNodeWithTag("notification-preview.full").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            !state.notificationPreviewSettings.busy && preferences.getBoolean("show_notification_previews", false)
        }
        assertEquals(
            context.getString(R.string.app_name),
            checkNotNull(currentCard()).extras.getCharSequence(Notification.EXTRA_TITLE),
        )
    }

    /** Busy disables every choice; failed writes keep the saved choice and offer an accessible explicit Retry. */
    @Test
    fun pendingAndFailedSettingsHaveClearActions() {
        val state = mutableStateOf(NotificationPreviewControlState(mode = NotificationPreviewMode.NONE, busy = true))
        var retries = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                NotificationPreviewControl(state = state.value, onChange = {}, onRetry = { retries++ })
            }
        }
        NotificationPreviewMode.entries.forEach { mode ->
            composeRule.onNodeWithTag("notification-preview.${mode.storedValue}").assertIsNotEnabled()
        }
        composeRule.runOnIdle {
            state.value = NotificationPreviewControlState(mode = NotificationPreviewMode.NONE, failed = true)
        }
        composeRule.onNodeWithTag("notification-preview.none").assertIsEnabled().assertIsSelected()
        composeRule.onNodeWithText("Retry").assertIsEnabled().performClick()
        assertEquals(1, retries)
    }

    /** Each choice reports its own mode once; tapping the saved choice changes nothing. */
    @Test
    fun choicesReportTheirModeOnce() {
        val changes = mutableListOf<NotificationPreviewMode>()
        composeRule.setContent {
            WhiteNoiseTheme {
                NotificationPreviewControl(
                    state = NotificationPreviewControlState(mode = NotificationPreviewMode.FULL),
                    onChange = { changes += it },
                    onRetry = {},
                )
            }
        }
        composeRule.onNodeWithText("Name and message").performClick()
        composeRule.onNodeWithText("Name only").performClick()
        composeRule.onNodeWithText("Hidden").performClick()
        assertEquals(listOf(NotificationPreviewMode.NAME_ONLY, NotificationPreviewMode.NONE), changes)
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
            sticker = null,
        )
}
