package dev.ipf.whitenoise.android.ui.group

import android.content.Context
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

/** The real group/DM settings entry opens the shared sheet and returns without changing alerts. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ConversationNotificationSoundSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Group alerts remain reachable after closing and reopening the prepared sound drawer. */
    @Test
    fun groupSettingsOpenAndCloseSoundDrawer() = checkSheetFlow(isDm = false)

    /** Direct-message settings use the same repeatable sound-drawer dismissal path. */
    @Test
    fun directSettingsOpenAndCloseSoundDrawer() = checkSheetFlow(isDm = true)

    /** Exercises real settings entry points, including a fresh category load on each opening. */
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
        val primary = if (isDm) NotificationChannelSpec.DIRECT_MESSAGES else NotificationChannelSpec.GROUP_MESSAGES
        repeat(3) {
            openLoadedSoundDrawer()
            composeRule
                .onNodeWithContentDescription(context.getString(R.string.close))
                .assertIsDisplayed()
                .performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithTag("sheet.surface").fetchSemanticsNodes().isEmpty()
            }
            composeRule.onNodeWithTag("sheet.surface").assertDoesNotExist()
            composeRule.onNodeWithTag("conversation-alert-${primary.id}").performScrollTo().assertIsDisplayed()
        }
    }

    /** Waits for asynchronous categories and their layout before targeting the moving sheet header. */
    private fun openLoadedSoundDrawer() {
        composeRule.onNodeWithTag(SOUND_APPEARANCE_OPEN_TAG).performScrollTo().performClick()
        val lastCategoryTag = "open-conversation-notification-${NotificationChannelSpec.AGENT_ACTIVITY.id}"
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(lastCategoryTag).fetchSemanticsNodes().size == 1
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("sheet.surface").assertIsDisplayed()
        composeRule.onNodeWithTag("sheet.dragHandle", useUnmergedTree = true).assertIsDisplayed()
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
