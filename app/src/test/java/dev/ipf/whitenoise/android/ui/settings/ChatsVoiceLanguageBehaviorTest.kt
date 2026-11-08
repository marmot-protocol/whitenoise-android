package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.EnterKeyBehavior
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.navigation.SettingsDetail
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Behaviour of the Chats and Voice & language hubs that took over the former Appearance rows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h1200dp-mdpi")
class ChatsVoiceLanguageBehaviorTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun freshState(): WhiteNoiseAppState {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context
            .getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        return WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore.forContext(context),
            accountIdHexResolver = { null },
            accounts = emptyList(),
            activeAccountRef = "missing-account",
        )
    }

    /** The Enter key dialog explains Shift+Enter, applies on tap, and Cancel keeps the stored choice. */
    @Test
    fun enterKeyDialogAppliesOnTapAndCancelKeepsIt() {
        val appState = freshState()
        composeRule.setContent { WhiteNoiseTheme { ChatsScreen(appState, {}, {}) } }
        composeRule.onNodeWithText("Enter key behavior").performClick()
        composeRule.onNodeWithText("Shift+Enter always adds a new line on a hardware keyboard.").assertExists()
        composeRule.onNodeWithText("Send message").performClick()
        composeRule.runOnIdle { assertEquals(EnterKeyBehavior.SendMessage, appState.enterKeyBehavior) }
        composeRule.onNodeWithText("Enter key behavior").performClick()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.runOnIdle { assertEquals(EnterKeyBehavior.SendMessage, appState.enterKeyBehavior) }
        composeRule.onNodeWithText("New line").assertDoesNotExist()
    }

    /** Chats rows open their existing destinations exactly once per tap. */
    @Test
    fun chatsRowsOpenTheirDestinations() {
        val opened = mutableListOf<SettingsDetail?>()
        composeRule.setContent { WhiteNoiseTheme { ChatsScreen(freshState(), {}, { opened += it }) } }
        composeRule.onNodeWithText("Custom emoji").performClick()
        composeRule.runOnIdle { assertEquals(listOf<SettingsDetail?>(SettingsDetail.CustomEmoji), opened) }
    }

    /** The language row shows the current choice and is a destination, so no list opens in place. */
    @Test
    fun languageRowShowsTheCurrentChoiceAndOpensNothingInPlace() {
        val opened = mutableListOf<SettingsDetail?>()
        composeRule.setContent { WhiteNoiseTheme { VoiceLanguageScreen(freshState(), {}, { opened += it }) } }
        composeRule.onNode(hasText("Language") and hasText("System default")).assertExists()
        composeRule.onNodeWithText("Language").performClick()
        composeRule.onNodeWithText("Deutsch").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(listOf<SettingsDetail?>(SettingsDetail.Language), opened) }
    }
}
