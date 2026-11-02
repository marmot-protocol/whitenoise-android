package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.ui.share.emptyAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.cancel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the production Home pencil entry and OS Back, rather than an injected navigation callback. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h1000dp-mdpi")
class NewMessageAgentDiscoveryTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test fun homePencilAgentSettingsAndSystemBackReturnThroughNewMessageToHome() {
        val app = emptyAppState()
        val controller = ChatsController(app, requireNotNull(app.activeAccountRef)) { _, _ -> emptyList() }
        val context = ApplicationProvider.getApplicationContext<Context>()
        try {
            composeRule.setContent { WhiteNoiseTheme { ChatsScreen(app, controller, {}, { _, _, _, _ -> }) } }
            composeRule.onNodeWithContentDescription(context.getString(R.string.new_message)).performClick()
            composeRule.onNodeWithTag("new_message.agentSettings").performClick()
            composeRule.onNodeWithText(context.getString(R.string.ai_agents)).assertIsDisplayed()
            composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
            composeRule.onNodeWithTag("new_message.agentSettings").assertIsDisplayed()
            composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
            composeRule.onNodeWithContentDescription(context.getString(R.string.new_message)).assertIsDisplayed()
        } finally {
            controller.onCleared()
            app.mutationsScope.cancel()
        }
    }
}
