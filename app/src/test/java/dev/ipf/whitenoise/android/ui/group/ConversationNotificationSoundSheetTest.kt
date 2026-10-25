package dev.ipf.whitenoise.android.ui.group

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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

/** The real group/DM settings entry keeps alerts and customization on one repeatable screen. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ConversationNotificationSoundSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Group categories reload correctly after navigating Back and reopening settings. */
    @Test
    fun groupSettingsKeepControlsOnOneScreen() = checkSettingsFlow(isDm = false)

    /** Direct-message categories use the same repeatable settings path. */
    @Test
    fun directSettingsKeepControlsOnOneScreen() = checkSettingsFlow(isDm = true)

    /** Exercises real settings entry points, including a fresh category load on each opening. */
    private fun checkSettingsFlow(isDm: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appState = testAppState(context)
        val visible = mutableStateOf(true)
        composeRule.setContent {
            WhiteNoiseTheme {
                if (visible.value) {
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
                        onBack = { visible.value = false },
                        onToggleMute = {},
                        onChooseVibrationPattern = {},
                    )
                }
            }
        }
        val primary = if (isDm) NotificationChannelSpec.DIRECT_MESSAGES else NotificationChannelSpec.GROUP_MESSAGES
        val primaryTag = "open-conversation-notification-${primary.id}"
        repeat(3) {
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithTag(primaryTag).fetchSemanticsNodes().size == 1
            }
            composeRule.onNodeWithTag(primaryTag).performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag("conversation-alert-${primary.id}", useUnmergedTree = true).assertIsDisplayed()
            composeRule.onNodeWithTag("sheet.surface").assertDoesNotExist()
            composeRule.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
            composeRule.onNodeWithTag(primaryTag).assertDoesNotExist()
            composeRule.runOnIdle { visible.value = true }
        }
    }

    /** Keeps account and draft state local while exercising production notification preparation. */
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
