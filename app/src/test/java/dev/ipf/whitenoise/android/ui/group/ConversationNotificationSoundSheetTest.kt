package dev.ipf.whitenoise.android.ui.group

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.ConversationVibrationPattern
import dev.ipf.whitenoise.android.notifications.NotificationChannelSpec
import dev.ipf.whitenoise.android.state.ChatNotifyMode
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The real group/DM settings entry opens the shared sheet and returns without changing alerts. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ConversationNotificationSoundSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun groupSettingsOpenAndCloseSoundDrawer() = checkSheetFlow(isDm = false)

    @Test
    fun directSettingsOpenAndCloseSoundDrawer() = checkSheetFlow(isDm = true)

    private fun checkSheetFlow(isDm: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appState = testAppState(context)
        composeRule.setContent {
            WhiteNoiseTheme {
                ConversationNotificationSettingsScreen(
                    appState = appState,
                    groupIdHex = "sound-sheet-chat",
                    conversationTitle = "Sound settings",
                    conversationAvatarUrl = null,
                    isDm = isDm,
                    isMuted = false,
                    muteCommandPending = false,
                    muteExpiryMillis = null,
                    notifyForMode = ChatNotifyMode.ALL,
                    vibrationPattern = ConversationVibrationPattern.SYSTEM_DEFAULT,
                    onBack = {},
                    onToggleMute = {},
                    onChooseVibrationPattern = {},
                )
            }
        }
        composeRule.onNodeWithTag(SOUND_APPEARANCE_OPEN_TAG).performScrollTo().performClick()
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("sheet.surface").assertIsDisplayed()
        composeRule.onNodeWithTag("sheet.dragHandle").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.close)).performClick()
        composeRule.onNodeWithTag("sheet.surface").assertDoesNotExist()
        val primary = if (isDm) NotificationChannelSpec.DIRECT_MESSAGES else NotificationChannelSpec.GROUP_MESSAGES
        composeRule.onNodeWithTag("conversation-alert-${primary.id}").performScrollTo().assertIsDisplayed()
    }

    private fun testAppState(context: Context): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(InMemoryDraftPersistence()),
            accountIdHexResolver = { "sound-sheet-self" },
            accounts = emptyList(),
            activeAccountRef = "sound-sheet-account",
            profileReader = { null },
        )

    private class InMemoryDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }
}
